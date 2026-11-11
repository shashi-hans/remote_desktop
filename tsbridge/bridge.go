// Package tsbridge embeds a Tailscale node (tsnet, userspace networking) in
// the Android app and exposes tailnet TCP services on short-lived 127.0.0.1
// ports, so FreeRDP and the agent client can use plain local sockets. No VPN
// service or separate Tailscale app is needed.
//
// The API uses only gomobile-compatible types (string, int, error).
package tsbridge

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "golang.org/x/mobile/bind" // keeps gomobile's bind package in go.mod
	"tailscale.com/ipn"
	"tailscale.com/tsnet"
)

var (
	mu       sync.Mutex
	srv      *tsnet.Server
	loginURL string
	state    = "Stopped"
	lastErr  string

	// closing is true while fail() shuts a node down. Start and Forward
	// refuse to run until it ends, so nothing uses or replaces a half-closed node.
	closing bool
)

// Start launches the node with its state kept in stateDir. It returns at once;
// poll State and LoginURL. Calling it again while running is a no-op; after
// State reports Error, calling it again starts a fresh node.
// ifaces supplies the device's network interfaces (see InterfaceSource).
func Start(stateDir, hostname string, ifaces InterfaceSource) error {
	mu.Lock()
	defer mu.Unlock()
	if closing {
		return errors.New("tailscale is still stopping")
	}
	if srv != nil {
		return nil
	}
	if ifaces == nil {
		return errors.New("tsbridge: interface source required")
	}
	registerInterfaceSource(ifaces)
	// No log uploads to Tailscale's log service.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")

	// Android has no /var/lib, user cache dir or writable /tmp for Go, and
	// logpolicy panics without a log-state directory, so both point inside
	// the app's private stateDir.
	logsDir := filepath.Join(stateDir, "logs")
	tmpDir := filepath.Join(stateDir, "tmp")
	for _, d := range []string{stateDir, logsDir, tmpDir} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			return err
		}
	}
	os.Setenv("TS_LOGS_DIR", logsDir)
	os.Setenv("TMPDIR", tmpDir)
	s := &tsnet.Server{
		Dir:       stateDir,
		Hostname:  hostname,
		Ephemeral: false,
		Logf:      func(string, ...any) {},
		// Discarded too: it prints the one-time login URL, which would land
		// in logcat. State and LoginURL expose everything the app needs.
		UserLogf: func(string, ...any) {},
	}
	srv = s
	state = "Starting"
	lastErr = ""
	loginURL = ""
	go run(s)
	return nil
}

func run(s *tsnet.Server) {
	if err := s.Start(); err != nil {
		fail(s, err)
		return
	}
	lc, err := s.LocalClient()
	if err != nil {
		fail(s, err)
		return
	}
	ctx := context.Background()
	w, err := lc.WatchIPNBus(ctx, ipn.NotifyInitialState)
	if err != nil {
		fail(s, err)
		return
	}
	defer w.Close()
	loginRequested := false
	for {
		n, err := w.Next()
		if err != nil {
			fail(s, err)
			return
		}
		mu.Lock()
		if n.State != nil {
			state = n.State.String()
			switch *n.State {
			case ipn.NeedsLogin:
				if !loginRequested {
					loginRequested = true
					go requestLoginURL(s, lc)
				}
			case ipn.Running:
				loginURL = ""
				lastErr = "" // errors from before sign-in no longer apply
				loginRequested = false
			}
		}
		if n.BrowseToURL != nil {
			loginURL = *n.BrowseToURL
		}
		if n.ErrMessage != nil {
			lastErr = *n.ErrMessage
		}
		mu.Unlock()
	}
}

// loginRetry is the pause between attempts to get a sign-in link.
const loginRetry = 10 * time.Second

// requestLoginURL asks the control server for a login URL, which arrives on
// the IPN bus as BrowseToURL. It retries while node s still needs a login, so
// a failed request does not leave the UI waiting for a link forever.
func requestLoginURL(s *tsnet.Server, lc interface {
	StartLoginInteractive(context.Context) error
}) {
	// stillNeeded reports whether s is still the live node waiting for a
	// link. mu must be held.
	stillNeeded := func() bool {
		return srv == s && state == ipn.NeedsLogin.String() && loginURL == ""
	}
	for {
		err := lc.StartLoginInteractive(context.Background())
		if err != nil {
			mu.Lock()
			if stillNeeded() { // never show an old node's error on a new one
				lastErr = "sign-in link: " + err.Error()
			}
			mu.Unlock()
		}
		// Retry both after an error and when the call succeeded but no URL
		// arrived within loginRetry.
		time.Sleep(loginRetry)
		mu.Lock()
		again := stillNeeded()
		mu.Unlock()
		if !again {
			return
		}
	}
}

// fail records err and tears the node down so a later Start can retry. It
// does nothing if s was already replaced or closed. srv is cleared only after
// Close returns, so a new node never opens the state directory while the old
// one is still shutting down.
func fail(s *tsnet.Server, err error) {
	mu.Lock()
	if srv != s {
		mu.Unlock()
		return
	}
	state = "Error"
	lastErr = err.Error()
	closing = true
	mu.Unlock()
	_ = s.Close()
	mu.Lock()
	if srv == s {
		srv = nil
	}
	closing = false
	mu.Unlock()
}

// State is the backend state: Starting, NeedsLogin, NeedsMachineAuth,
// Running, Stopped or Error.
func State() string {
	mu.Lock()
	defer mu.Unlock()
	return state
}

// LoginURL is the URL to open in a browser while State is NeedsLogin, or "".
func LoginURL() string {
	mu.Lock()
	defer mu.Unlock()
	return loginURL
}

// LastError is the most recent error message, or "".
func LastError() string {
	mu.Lock()
	defer mu.Unlock()
	return lastErr
}

// Forward opens a 127.0.0.1 listener on a free port that relays to target
// (host:port on the tailnet) and returns the port. The listener accepts at
// most maxConns connections and closes after windowSec seconds, whichever
// comes first, so other apps on the phone get only a brief window to reach
// it. Open a new one for each connection attempt.
func Forward(target string, maxConns, windowSec int) (int, error) {
	mu.Lock()
	s, ok := srv, srv != nil && !closing && state != "Error"
	mu.Unlock()
	if !ok {
		return 0, errors.New("tailscale is not running")
	}
	if maxConns < 1 || windowSec < 1 {
		return 0, fmt.Errorf("maxConns and windowSec must be positive")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	go acceptLimited(s, ln.(*net.TCPListener), target, maxConns, time.Duration(windowSec)*time.Second)
	return ln.Addr().(*net.TCPAddr).Port, nil
}

func acceptLimited(s *tsnet.Server, ln *net.TCPListener, target string, maxConns int, window time.Duration) {
	acceptLimitedFn(ln, maxConns, window, func(c net.Conn) { relay(s, c, target) })
}

// acceptLimitedFn accepts up to maxConns connections within window, hands
// each to handle on its own goroutine, then closes ln.
func acceptLimitedFn(ln *net.TCPListener, maxConns int, window time.Duration, handle func(net.Conn)) {
	defer ln.Close()
	_ = ln.SetDeadline(time.Now().Add(window))
	for i := 0; i < maxConns; i++ {
		c, err := ln.Accept()
		if err != nil {
			return // deadline passed or listener closed
		}
		go handle(c)
	}
}

func relay(s *tsnet.Server, local net.Conn, target string) {
	defer local.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	remote, err := s.Dial(ctx, "tcp", target)
	cancel()
	if err != nil {
		log.Printf("tsbridge: dial %s: %v", target, err)
		return
	}
	defer remote.Close()
	done := make(chan struct{}, 2)
	go func() { _, _ = io.Copy(remote, local); closeWrite(remote); done <- struct{}{} }()
	go func() { _, _ = io.Copy(local, remote); closeWrite(local); done <- struct{}{} }()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if cw, ok := c.(interface{ CloseWrite() error }); ok {
		_ = cw.CloseWrite()
	}
}

// Logout signs this device out of the tailnet and forgets its key.
func Logout() error {
	mu.Lock()
	s := srv
	mu.Unlock()
	if s == nil {
		return nil
	}
	lc, err := s.LocalClient()
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return lc.Logout(ctx)
}

// Version reports the embedded Tailscale version, for the settings screen.
func Version() string {
	return fmt.Sprintf("tsnet %s", tsVersion())
}
