package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"log"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	maxBodyBytes  = 4 << 10 // fits a note: 300-rune body, escaped
	maxAuthFails  = 5
	authBlockTime = time.Minute
	maxNoteWait   = 25 // seconds; must stay below the server WriteTimeout
)

type server struct {
	desktop   Desktop
	notes     *noteStore // nil when notification forwarding is off
	tokenHash [32]byte
	rdpAddr   string
	started   time.Time
	now       func() time.Time

	mu    sync.Mutex
	fails map[string]*authFail
}

type authFail struct {
	count        int
	blockedUntil time.Time
}

func newServer(d Desktop, token, rdpAddr string) *server {
	return &server{
		desktop:   d,
		tokenHash: sha256.Sum256([]byte(token)),
		rdpAddr:   rdpAddr,
		started:   time.Now(),
		now:       time.Now,
		fails:     map[string]*authFail{},
	}
}

func (s *server) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/status", s.status)
	mux.HandleFunc("POST /v1/lock", s.lock)
	mux.HandleFunc("PUT /v1/keep-awake", s.keepAwake)
	mux.HandleFunc("GET /v1/notifications", s.notifications)
	mux.HandleFunc("POST /v1/notifications", s.addNotification)
	return s.auth(mux)
}

// addNotification lets local tools (the Claude Code hook) send a note to the
// phone without showing a desktop pop-up on the laptop.
func (s *server) addNotification(w http.ResponseWriter, r *http.Request) {
	if s.notes == nil {
		writeErr(w, http.StatusNotFound, "notification forwarding is off")
		return
	}
	var req struct {
		App   string `json:"app"`
		Title string `json:"title"`
		Body  string `json:"body"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || strings.TrimSpace(req.Title) == "" {
		writeErr(w, http.StatusBadRequest, `body must be {"app","title","body"} with a title`)
		return
	}
	s.notes.add(clean(req.App, 80), clean(req.Title, 200), clean(req.Body, maxBodyRunes), false)
	w.WriteHeader(http.StatusNoContent)
}

type notesResp struct {
	Epoch  string `json:"epoch"`
	Latest int64  `json:"latest"`
	Items  []Note `json:"items"`
}

// notifications returns notes with id > after, waiting up to `wait` seconds
// (max maxNoteWait) for one to arrive. after=-1 returns only the cursor, so
// a new client starts from "now" instead of replaying old notes.
func (s *server) notifications(w http.ResponseWriter, r *http.Request) {
	if s.notes == nil {
		writeErr(w, http.StatusNotFound, "notification forwarding is off")
		return
	}
	q := r.URL.Query()
	after, err := strconv.ParseInt(q.Get("after"), 10, 64)
	if err != nil {
		writeErr(w, http.StatusBadRequest, "after must be an integer")
		return
	}
	wait, _ := strconv.Atoi(q.Get("wait"))
	wait = min(max(wait, 0), maxNoteWait)

	ctx, cancel := context.WithTimeout(r.Context(), time.Duration(wait)*time.Second)
	defer cancel()
	items, latest := s.notes.wait(ctx, after)
	if items == nil {
		items = []Note{}
	}
	writeJSON(w, http.StatusOK, notesResp{Epoch: s.notes.epoch, Latest: latest, Items: items})
}

// auth checks the bearer token in constant time. After maxAuthFails bad
// tokens in a row from one IP, further bad tokens from it get 429 for
// authBlockTime. A valid token is always accepted: with userspace Tailscale
// every client shows as 127.0.0.1, so blocking by IP would let anyone lock
// out the phone.
func (s *server) auth(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ip, _, _ := net.SplitHostPort(r.RemoteAddr)

		got, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		gotHash := sha256.Sum256([]byte(got))
		// A valid token skips the block entirely. The failure record is left
		// alone: behind userspace Tailscale the phone shares 127.0.0.1 with
		// everyone, so clearing it here would make the limit meaningless.
		if ok && subtle.ConstantTimeCompare(gotHash[:], s.tokenHash[:]) == 1 {
			r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
			next.ServeHTTP(w, r)
			return
		}

		s.mu.Lock()
		f := s.fails[ip]
		if f == nil {
			f = &authFail{}
			s.fails[ip] = f
		}
		blocked := s.now().Before(f.blockedUntil)
		if !blocked {
			f.count++
			if f.count >= maxAuthFails {
				f.count = 0
				f.blockedUntil = s.now().Add(authBlockTime)
			}
		}
		s.mu.Unlock()
		if blocked {
			writeErr(w, http.StatusTooManyRequests, "too many failed attempts")
			return
		}
		log.Printf("auth failed from %s", ip)
		writeErr(w, http.StatusUnauthorized, "unauthorized")
	})
}

type statusResp struct {
	Hostname   string `json:"hostname"`
	UptimeSec  int64  `json:"agent_uptime_sec"`
	Locked     *bool  `json:"locked"`
	KeepAwake  bool   `json:"keep_awake"`
	BatteryPct *int   `json:"battery_pct"`
	Charging   bool   `json:"charging"`
	RDPUp      bool   `json:"rdp_listening"`
}

func (s *server) status(w http.ResponseWriter, r *http.Request) {
	host, _ := os.Hostname()
	resp := statusResp{
		Hostname:  host,
		UptimeSec: int64(s.now().Sub(s.started).Seconds()),
		KeepAwake: s.desktop.KeepAwake(),
		RDPUp:     rdpListening(s.rdpAddr),
	}
	if locked, err := s.desktop.Locked(r.Context()); err == nil {
		resp.Locked = &locked
	}
	if pct, charging, ok := s.desktop.Battery(); ok {
		resp.BatteryPct = &pct
		resp.Charging = charging
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *server) lock(w http.ResponseWriter, r *http.Request) {
	if err := s.desktop.Lock(r.Context()); err != nil {
		log.Printf("lock: %v", err)
		writeErr(w, http.StatusBadGateway, "lock failed")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *server) keepAwake(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Enabled *bool `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.Enabled == nil {
		writeErr(w, http.StatusBadRequest, `body must be {"enabled": true|false}`)
		return
	}
	if err := s.desktop.SetKeepAwake(*req.Enabled); err != nil {
		log.Printf("keep-awake: %v", err)
		writeErr(w, http.StatusBadGateway, "keep-awake failed")
		return
	}
	writeJSON(w, http.StatusOK, map[string]bool{"keep_awake": s.desktop.KeepAwake()})
}

func rdpListening(addr string) bool {
	c, err := net.DialTimeout("tcp", addr, 300*time.Millisecond)
	if err != nil {
		return false
	}
	c.Close()
	return true
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, code int, msg string) {
	writeJSON(w, code, map[string]string{"error": msg})
}
