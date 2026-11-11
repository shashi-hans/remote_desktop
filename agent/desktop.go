package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Desktop is the set of laptop actions the HTTP API exposes. The interface
// exists so handlers can be tested without a GNOME session.
type Desktop interface {
	Lock(ctx context.Context) error
	Locked(ctx context.Context) (bool, error)
	SetKeepAwake(on bool) error
	KeepAwake() bool
	Battery() (pct int, charging bool, ok bool)
}

// gnomeDesktop drives GNOME through gdbus and systemd-inhibit. It must run
// inside the user's session (a systemd --user service) so it can reach the
// session D-Bus.
type gnomeDesktop struct {
	mu      sync.Mutex
	inhibit *inhibitor
}

// inhibitor is a running systemd-inhibit child. Only its reaper goroutine
// calls Wait; done closes when the process has exited.
type inhibitor struct {
	cmd  *exec.Cmd
	done chan struct{}
	err  error // exit error, valid after done closes
}

// inhibitStartCheck is how long a new inhibitor must stay running before
// keep-awake is reported as on; polkit or logind refusals exit sooner.
const inhibitStartCheck = 300 * time.Millisecond

// inhibitStopWait is how long systemd-inhibit gets to exit on SIGTERM before
// it is killed.
const inhibitStopWait = 2 * time.Second

func runQuiet(ctx context.Context, name string, args ...string) (string, error) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, name, args...).Output()
	return strings.TrimSpace(string(out)), err
}

func (g *gnomeDesktop) Lock(ctx context.Context) error {
	_, err := runQuiet(ctx, "gdbus", "call", "--session",
		"--dest", "org.gnome.ScreenSaver",
		"--object-path", "/org/gnome/ScreenSaver",
		"--method", "org.gnome.ScreenSaver.Lock")
	return err
}

func (g *gnomeDesktop) Locked(ctx context.Context) (bool, error) {
	out, err := runQuiet(ctx, "gdbus", "call", "--session",
		"--dest", "org.gnome.ScreenSaver",
		"--object-path", "/org/gnome/ScreenSaver",
		"--method", "org.gnome.ScreenSaver.GetActive")
	if err != nil {
		return false, err
	}
	// gdbus prints a GVariant tuple: "(true,)" or "(false,)".
	return strings.Contains(out, "true"), nil
}

// SetKeepAwake holds a systemd sleep+idle inhibitor while on. The inhibitor
// is a child process started with a parent-death signal, so it is released
// even if the agent is killed.
func (g *gnomeDesktop) SetKeepAwake(on bool) error {
	g.mu.Lock()
	defer g.mu.Unlock()
	if on == (g.inhibit != nil) {
		return nil
	}
	if !on {
		inh := g.inhibit
		g.inhibit = nil
		_ = inh.cmd.Process.Signal(syscall.SIGTERM)
		select {
		case <-inh.done:
		case <-time.After(inhibitStopWait):
			// Never block status and shutdown on a stuck child.
			_ = inh.cmd.Process.Kill()
			<-inh.done
		}
		return nil
	}
	cmd := exec.Command("systemd-inhibit",
		"--what=sleep:idle", "--who=rdpagent", "--why=Remote session", "--mode=block",
		"sleep", "infinity")
	cmd.SysProcAttr = &syscall.SysProcAttr{Pdeathsig: syscall.SIGTERM}
	if err := cmd.Start(); err != nil {
		return err
	}
	inh := &inhibitor{cmd: cmd, done: make(chan struct{})}
	go func() {
		inh.err = cmd.Wait()
		close(inh.done)
		g.mu.Lock()
		if g.inhibit == inh {
			g.inhibit = nil
		}
		g.mu.Unlock()
	}()
	select {
	case <-inh.done:
		return fmt.Errorf("systemd-inhibit exited at once: %v", inh.err)
	case <-time.After(inhibitStartCheck):
	}
	g.inhibit = inh
	return nil
}

func (g *gnomeDesktop) KeepAwake() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.inhibit != nil
}

func (g *gnomeDesktop) Battery() (int, bool, bool) {
	dirs, _ := filepath.Glob("/sys/class/power_supply/BAT*")
	if len(dirs) == 0 {
		return 0, false, false
	}
	capacity, err := os.ReadFile(filepath.Join(dirs[0], "capacity"))
	if err != nil {
		return 0, false, false
	}
	pct, err := strconv.Atoi(strings.TrimSpace(string(capacity)))
	if err != nil {
		return 0, false, false
	}
	status, _ := os.ReadFile(filepath.Join(dirs[0], "status"))
	s := strings.TrimSpace(string(status))
	return pct, s == "Charging" || s == "Full", true
}
