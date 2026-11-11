package main

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"html"
	"io"
	"log"
	"os/exec"
	"regexp"
	"strings"
	"sync"
	"time"
)

const (
	maxNotes     = 100
	maxBodyRunes = 300
	dedupeWindow = 2 * time.Second
)

// Note is one desktop notification as served to the phone.
type Note struct {
	ID    int64  `json:"id"`
	App   string `json:"app"`
	Title string `json:"title"`
	Body  string `json:"body"`
	Time  int64  `json:"time"` // Unix seconds
	// Popup is true when the laptop itself showed this as a desktop pop-up,
	// so someone viewing the laptop screen has already seen it.
	Popup bool `json:"popup"`
}

// noteStore keeps the last maxNotes notifications in memory and wakes
// long-poll waiters when one arrives. Nothing is written to disk.
type noteStore struct {
	mu      sync.Mutex
	items   []Note
	lastAdd time.Time // full-precision time of the newest note, for dedupe
	next    int64
	epoch   string // changes on agent restart so clients reset their cursor
	changed chan struct{}
	now     func() time.Time
}

func newNoteStore() *noteStore {
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	return &noteStore{
		next:    1,
		epoch:   hex.EncodeToString(b),
		changed: make(chan struct{}),
		now:     time.Now,
	}
}

func (s *noteStore) add(app, title, body string, popup bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	// GNOME Shell re-sends each Notify to itself, so the monitor sees it twice.
	if n := len(s.items); n > 0 {
		last := s.items[n-1]
		if last.App == app && last.Title == title && last.Body == body &&
			now.Sub(s.lastAdd) <= dedupeWindow {
			return
		}
	}
	s.lastAdd = now
	s.items = append(s.items, Note{ID: s.next, App: app, Title: title, Body: body, Time: now.Unix(), Popup: popup})
	s.next++
	if len(s.items) > maxNotes {
		s.items = s.items[len(s.items)-maxNotes:]
	}
	close(s.changed)
	s.changed = make(chan struct{})
}

// since returns notes with ID > after, the latest ID, and a channel that
// closes on the next add. after < 0 means "only tell me the cursor". A cursor
// beyond latest comes from before an agent restart and is treated as 0.
func (s *noteStore) since(after int64) ([]Note, int64, <-chan struct{}) {
	s.mu.Lock()
	defer s.mu.Unlock()
	latest := s.next - 1
	if after > latest {
		after = 0
	}
	var out []Note
	if after >= 0 {
		for _, n := range s.items {
			if n.ID > after {
				out = append(out, n)
			}
		}
	}
	return out, latest, s.changed
}

// wait blocks until there are notes after `after` or ctx ends.
func (s *noteStore) wait(ctx context.Context, after int64) ([]Note, int64) {
	for {
		notes, latest, ch := s.since(after)
		if len(notes) > 0 || after < 0 {
			return notes, latest
		}
		select {
		case <-ch:
		case <-ctx.Done():
			return nil, latest
		}
	}
}

// watchNotifications runs dbus-monitor on the session bus and feeds every
// org.freedesktop.Notifications.Notify call into store, restarting the
// monitor if it exits.
func watchNotifications(ctx context.Context, store *noteStore) {
	emit := func(app, title, body string) { store.add(app, title, body, true) }
	for ctx.Err() == nil {
		// A per-run context kills dbus-monitor if parsing stops early (for
		// example on an over-long line); otherwise Wait would block forever
		// on a process stuck writing to a pipe nobody reads.
		runCtx, cancel := context.WithCancel(ctx)
		cmd := exec.CommandContext(runCtx, "dbus-monitor", "--session",
			"type='method_call',interface='org.freedesktop.Notifications',member='Notify'")
		out, err := cmd.StdoutPipe()
		if err == nil {
			err = cmd.Start()
		}
		if err != nil {
			log.Printf("notifications: %v", err)
		} else {
			if err := parseMonitor(out, emit); err != nil {
				log.Printf("notifications: reading dbus-monitor: %v", err)
			}
			cancel()
			_ = cmd.Wait()
		}
		cancel()
		select {
		case <-ctx.Done():
		case <-time.After(5 * time.Second):
		}
	}
}

var markup = regexp.MustCompile(`<[^>]*>`)

// argLine matches the start of a dbus-monitor argument line, such as
// `   string "x"`, `   uint32 0` or `   array [`.
var argLine = regexp.MustCompile(`^ {3,}(string |u?int(16|32|64) |byte |boolean |double |array \[|dict entry\(|variant |struct \{|object path |\]|\))`)

func isHeader(line string) bool {
	for _, p := range []string{"method call ", "method return ", "signal ", "error "} {
		if strings.HasPrefix(line, p) {
			return true
		}
	}
	return false
}

// parseMonitor reads dbus-monitor's text output. A Notify call prints
// app_name, replaces_id, app_icon, summary and body as its first arguments.
// Strings are printed between quotes without escaping and may span lines,
// so a string ends only where the next argument or message line begins.
// A note is emitted as soon as its body ends (at the actions array that
// follows it), not at the next message, so it reaches the phone at once.
func parseMonitor(r io.Reader, emit func(app, title, body string)) error {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	inCall := false
	emitted := false
	var args []string
	var pending *strings.Builder // string argument still being read

	maybeEmit := func() {
		if inCall && !emitted && len(args) >= 5 {
			emit(clean(args[0], 80), clean(args[3], 200), clean(args[4], maxBodyRunes))
			emitted = true
		}
	}
	endString := func() {
		if pending != nil {
			args = append(args, strings.TrimSuffix(pending.String(), `"`))
			pending = nil
			maybeEmit()
		}
	}
	flush := func() {
		endString()
		maybeEmit()
		inCall = false
		emitted = false
		args = args[:0]
	}

	for sc.Scan() {
		line := sc.Text()
		if isHeader(line) {
			flush()
			inCall = strings.HasPrefix(line, "method call ") && strings.Contains(line, "member=Notify")
			continue
		}
		if !inCall {
			continue
		}
		if pending != nil && !argLine.MatchString(line) {
			pending.WriteString("\n" + line)
			continue
		}
		endString()
		if len(args) >= 5 {
			continue
		}
		t := strings.TrimSpace(line)
		switch {
		case strings.HasPrefix(t, `string "`):
			pending = &strings.Builder{}
			pending.WriteString(strings.TrimPrefix(t, `string "`))
		case strings.HasPrefix(t, "uint32 "):
			args = append(args, strings.TrimPrefix(t, "uint32 "))
		}
	}
	flush()
	return sc.Err()
}

// clean strips notification markup (<b>, <a href>…), decodes entities such
// as &amp;, and truncates to maxRunes.
func clean(s string, maxRunes int) string {
	s = strings.TrimSpace(html.UnescapeString(markup.ReplaceAllString(s, "")))
	r := []rune(s)
	if len(r) > maxRunes {
		return string(r[:maxRunes-1]) + "…"
	}
	return s
}
