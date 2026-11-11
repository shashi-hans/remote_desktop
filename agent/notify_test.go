package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

const monitorSample = `signal time=1.0 sender=org.freedesktop.DBus -> destination=:1.9 serial=2 path=/org/freedesktop/DBus; interface=org.freedesktop.DBus; member=NameAcquired
   string ":1.9"
method call time=2.0 sender=:1.10 -> destination=:1.52 serial=8 path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; member=Notify
   string "Slack"
   uint32 0
   string ""
   string "<b>Asha</b>"
   string "Line one with "quotes"
second line"
   array [
   ]
   int32 -1
method call time=3.0 sender=:1.11 -> destination=:1.52 serial=9 path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; member=Notify
   string "Calendar"
   uint32 4
   string "icon"
   string "Standup in 5 min"
   string ""
   array [
   ]
`

func TestParseMonitor(t *testing.T) {
	var got [][3]string
	parseMonitor(strings.NewReader(monitorSample), func(a, ti, b string) {
		got = append(got, [3]string{a, ti, b})
	})
	want := [][3]string{
		{"Slack", "Asha", "Line one with \"quotes\"\nsecond line"},
		{"Calendar", "Standup in 5 min", ""},
	}
	if len(got) != len(want) {
		t.Fatalf("got %d notes: %q", len(got), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("note %d = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestCleanTruncates(t *testing.T) {
	if got := clean(strings.Repeat("é", 10), 5); got != "éééé…" {
		t.Fatalf("got %q", got)
	}
}

func TestNoteStoreDedupeAndCursor(t *testing.T) {
	s := newNoteStore()
	now := time.Unix(1_800_000_000, 0)
	s.now = func() time.Time { return now }

	s.add("a", "t", "b", true)
	s.add("a", "t", "b", true) // GNOME's duplicate within the window
	now = now.Add(5 * time.Second)
	s.add("a", "t", "b", true) // same text later is a new notification

	items, latest, _ := s.since(0)
	if len(items) != 2 || latest != 2 {
		t.Fatalf("items=%d latest=%d", len(items), latest)
	}
	if items, _, _ := s.since(-1); items != nil {
		t.Fatal("after=-1 must return no items")
	}
	if items, _, _ := s.since(1); len(items) != 1 || items[0].ID != 2 {
		t.Fatalf("since(1) = %+v", items)
	}
}

func TestNoteStoreCap(t *testing.T) {
	s := newNoteStore()
	for i := 0; i < maxNotes+20; i++ {
		s.add("a", "t", string(rune('a'+i%26))+strings.Repeat("x", i), true)
	}
	items, latest, _ := s.since(0)
	if len(items) != maxNotes || latest != maxNotes+20 || items[0].ID != 21 {
		t.Fatalf("len=%d latest=%d first=%d", len(items), latest, items[0].ID)
	}
}

func TestNoteStoreWaitWakes(t *testing.T) {
	s := newNoteStore()
	go func() {
		time.Sleep(50 * time.Millisecond)
		s.add("a", "hello", "", true)
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	items, _ := s.wait(ctx, 0)
	if len(items) != 1 || items[0].Title != "hello" {
		t.Fatalf("wait returned %+v", items)
	}
}

func TestNotificationsEndpoint(t *testing.T) {
	srv := newServer(&fakeDesktop{}, testToken, "127.0.0.1:1")
	h := srv.routes()
	if rec := do(t, h, "GET", "/v1/notifications?after=0", testToken, ""); rec.Code != http.StatusNotFound {
		t.Fatalf("disabled: got %d", rec.Code)
	}

	srv.notes = newNoteStore()
	srv.notes.add("Mail", "New message", "hi", true)
	if rec := do(t, h, "GET", "/v1/notifications?after=x", testToken, ""); rec.Code != http.StatusBadRequest {
		t.Fatalf("bad after: got %d", rec.Code)
	}

	var resp notesResp
	rec := do(t, h, "GET", "/v1/notifications?after=-1", testToken, "")
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil || resp.Latest != 1 || len(resp.Items) != 0 || resp.Epoch == "" {
		t.Fatalf("cursor only: %s", rec.Body)
	}
	rec = do(t, h, "GET", "/v1/notifications?after=0&wait=1", testToken, "")
	if err := json.Unmarshal(rec.Body.Bytes(), &resp); err != nil || len(resp.Items) != 1 || resp.Items[0].App != "Mail" {
		t.Fatalf("items: %s", rec.Body)
	}

	start := time.Now()
	rec = do(t, h, "GET", "/v1/notifications?after=1&wait=1", testToken, "")
	if time.Since(start) < 900*time.Millisecond || !strings.Contains(rec.Body.String(), `"items":[]`) {
		t.Fatalf("long poll did not wait: %v %s", time.Since(start), rec.Body)
	}
}

func TestParseMonitorEmitsWithoutNextMessage(t *testing.T) {
	// A single Notify that is the last thing in the stream so far: the note
	// must be emitted when its body ends, not when the next message arrives.
	pr, pw := io.Pipe()
	got := make(chan string, 1)
	go func() { _ = parseMonitor(pr, func(_, title, _ string) { got <- title }) }()
	_, _ = io.WriteString(pw, "method call time=1 sender=:1.1 -> destination=:1.2 serial=1 path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; member=Notify\n"+
		"   string \"App\"\n   uint32 0\n   string \"\"\n   string \"Now\"\n   string \"body\"\n   array [\n")
	select {
	case title := <-got:
		if title != "Now" {
			t.Fatalf("title %q", title)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("note not emitted until the next message")
	}
	pw.Close()
}

func TestStaleCursorAfterRestart(t *testing.T) {
	s := newNoteStore()
	s.add("a", "first", "", true)
	if items, _, _ := s.since(500); len(items) != 1 {
		t.Fatalf("cursor beyond latest must replay from start, got %d", len(items))
	}
}

func TestCleanDecodesEntities(t *testing.T) {
	if got := clean("<b>Tom &amp; Jerry</b> &lt;3", 50); got != "Tom & Jerry <3" {
		t.Fatalf("got %q", got)
	}
}

func TestDedupeUsesFullPrecision(t *testing.T) {
	// GNOME's duplicate arrives 1.5 s after the original at x.9 s, inside the
	// 2 s window. Rounding the stored time down to whole seconds made the gap
	// look like 2.5 s, so the duplicate was kept; full precision drops it.
	s := newNoteStore()
	now := time.Unix(1_800_000_000, 900_000_000)
	s.now = func() time.Time { return now }
	s.add("a", "t", "b", true)
	now = now.Add(1500 * time.Millisecond)
	s.add("a", "t", "b", true)
	if items, _, _ := s.since(0); len(items) != 1 {
		t.Fatalf("duplicate within 2 s kept: %d items", len(items))
	}
}
