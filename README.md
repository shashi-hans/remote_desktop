# rdp-mobile

Android app that shows and controls one Linux laptop's desktop over RDP, plus a
small laptop agent for status, lock and keep-awake. Built for personal use on
one phone and one laptop, connected through Tailscale. The phone needs no
separate Tailscale app: the app embeds a Tailscale node.

How to use the app once connected (gestures, key bar, shortcuts, copy and
paste): [docs/USING-THE-APP.md](docs/USING-THE-APP.md).

- `app/`: Kotlin + Jetpack Compose UI.
- `rdpcore/`: FreeRDP 3.32.2 native libraries (arm64-v8a) and the Java JNI binding `LibFreeRDP`.
- `tsbridge/`: Go package wrapping Tailscale `tsnet` (v1.104.1), built into `app/libs/tsbridge.aar` with gomobile.
- `agent/`: `rdpagent`, a Go (stdlib only) HTTP service on the laptop.
- `scripts/`: fetch and build FreeRDP, build tsbridge, install the agent.

## How it connects

```
FreeRDP / AgentClient ──▶ 127.0.0.1:<port> ──tsbridge (tsnet, WireGuard)──▶ laptop 100.x.y.z
   ├─ RDP over TLS, port 3389 ──▶ gnome-remote-desktop
   └─ HTTP + bearer token, 8765 ──▶ rdpagent (lock, status, keep-awake)
```

The app joins the tailnet as its own device (hostname `rdp-mobile`). tsbridge
opens a short-lived 127.0.0.1 port for each use and relays it over the
tailnet, so FreeRDP and the agent client use plain local sockets and no
Android VPN permission is needed. An agent request gets a port that accepts
one connection within 10 seconds; an RDP connect gets one that accepts up to
3 connections within 30 seconds. The port then closes, so other apps on the
phone get no lasting way in. Screen data travels phone to laptop
directly, or through Tailscale's encrypted DERP relays when a direct path is
not possible. Tailscale's coordination server sees device names and IPs, not
traffic. Log uploads to Tailscale are disabled (`TS_NO_LOGS_NO_SUPPORT`).

Android 11+ blocks Go's netlink interface lookup for apps, so the app passes
`java.net.NetworkInterface` data to tsbridge (`InterfaceSource`), the same
approach as Tailscale's own Android app.

## Laptop setup (Ubuntu 22.04, GNOME 42)

1. Install Tailscale and log in: `curl -fsSL https://tailscale.com/install.sh | sh`, then `sudo tailscale up`.
   Without sudo, run the static binaries in userspace mode as a user service
   (`tailscaled --tun=userspace-networking --socket=$XDG_RUNTIME_DIR/tailscale/tailscaled.sock`)
   and `loginctl enable-linger`. In that mode tailscaled forwards tailnet
   connections to 127.0.0.1, so RDP and the agent are reached on localhost,
   and every agent client shares one lockout counter.
2. Settings → Sharing → Remote Desktop: turn on Remote Desktop and Remote Control,
   and set a username and a strong password. These are RDP-only credentials,
   separate from your Linux login. To set them from a terminal, do not run
   `grdctl` inside a snap app's terminal (for example VS Code from snap): it
   saves to a store the daemon never reads, and logins then fail with
   "MIC verification failed". Use a clean environment, then restart the daemon:
   ```
   env -i HOME="$HOME" PATH=/usr/bin:/bin XDG_RUNTIME_DIR="$XDG_RUNTIME_DIR" \
     DBUS_SESSION_BUS_ADDRESS="$DBUS_SESSION_BUS_ADDRESS" grdctl rdp set-credentials <user> <password>
   systemctl --user restart gnome-remote-desktop
   ```
3. Allow RDP only through Tailscale. gnome-remote-desktop listens on all
   interfaces, so without this it is open to every Wi-Fi network you join:
   ```
   sudo ufw allow in on tailscale0 to any port 3389 proto tcp
   sudo ufw deny 3389/tcp
   sudo ufw enable
   ```
4. Stop the laptop sleeping while you need it: Settings → Power → Automatic
   Suspend off, or use the app's keep-awake switch.
5. Install the agent (optional, enables status, lock and keep-awake):
   ```
   GO=<path-to-go> scripts/install-agent.sh
   # userspace Tailscale: TS_SOCKET=$XDG_RUNTIME_DIR/tailscale/tailscaled.sock GO=<path-to-go> scripts/install-agent.sh
   ```
   It prints a token once. Enter it in the app under Settings → Agent token.

### Locked screen on GNOME 42

GNOME 42 Remote Desktop shares the logged-in session and stops while that
session is locked. Three ways around it:

| Option | Effect |
| --- | --- |
| Upgrade to Ubuntu 24.04 (GNOME 46) | "Remote Login" works at the lock and login screen. Change the app's credentials to the Remote Login ones. |
| GNOME extension "Allow Locked Remote Desktop" | Keeps sharing on while locked. Third-party code running inside GNOME Shell; review before installing. |
| Turn off automatic screen lock | Works, but anyone at the laptop gets an unlocked session. Not recommended. |

The app asks for confirmation before it locks the laptop for this reason.

## Phone setup

1. Install the APK: `adb install app/build/outputs/apk/release/app-release.apk`.
2. Open the app and sign in to Tailscale with the same account as the laptop.
   This is needed once; the node key stays in the app's private storage.
   - **Sign in to Tailscale** opens the login page as a Custom Tab over the
     app (Google and Microsoft sign-in block plain WebViews). The same link
     also works from any other browser.
   - Settings → **Sign out of Tailscale** logs the device out, for example to
     switch accounts.
3. Enter the laptop's Tailscale IP (`tailscale ip -4` on the laptop), the RDP
   username and password, and the agent token.
4. On the first connect the app shows the laptop's certificate fingerprint.
   Compare it with:
   ```
   openssl x509 -noout -fingerprint -sha256 -in ~/.local/share/gnome-remote-desktop/rdp-tls.crt
   ```
   and tap Trust. A changed certificate later shows a warning instead of
   connecting silently.

## Laptop notifications on the phone

The home screen switch "Laptop notifications on this phone" starts
`NotifyService`, a foreground service that long-polls the agent's
`GET /v1/notifications?after=<id>&wait=25` over the embedded Tailscale node
and posts each laptop notification with the default sound.

- The agent reads `org.freedesktop.Notifications.Notify` calls with
  `dbus-monitor` and keeps the last 100 in memory only. Turn it off with
  `rdpagent serve -notifications=false`.
- Each note carries app name, title and up to 300 characters of body, with
  markup stripped. They travel only inside the WireGuard tunnel.
- On the phone, the lock screen shows "Laptop notification" without the
  text unless Android's lock-screen settings allow sensitive content.
- While the session screen is in view, a laptop pop-up is already on screen,
  so the phone only plays the tone: a 4-second notification on the "Sound
  during remote session" channel (GNOME 42 sends no RDP audio). Notes the
  laptop never showed (Claude Code events), and any note while the session
  screen is in the background, get the full notification.
- The service needs the notification permission and the battery-optimization
  exemption (both requested when the switch is turned on). On MIUI also set
  App info → Battery saver → No restrictions and enable Autostart, or MIUI
  stops the service.

### Claude Code events

Claude Code's own alerts (including the Claude Notifier VS Code extension)
show only inside VS Code, so `dbus-monitor` never sees them. `rdpagent
claude-hook` is a Claude Code hook that posts them straight to the agent
(`POST /v1/notifications`) without a laptop pop-up. Add it to
`~/.claude/settings.json` for the `Stop`, `PermissionRequest` and
`PreToolUse` (matcher `AskUserQuestion`) events:

```json
{ "type": "command", "command": "\"$HOME/.local/bin/rdpagent\" claude-hook", "timeout": 5 }
```

The phone shows "Claude finished", "Claude has a question" (with the
question text) or "Claude needs permission" (tool name and its short
description only; commands and file contents are never sent). The hook gives
up after 2 seconds and always exits 0, so it cannot block a Claude turn.

## Touch controls

| Gesture | Action |
| --- | --- |
| One-finger drag | Move cursor (trackpad style) |
| Tap | Left click |
| Two-finger tap | Right click |
| Two-finger drag | Scroll |
| Pinch | Zoom 1x to 4x; the view follows the cursor |
| Long press, then drag | Hold left button (select, move windows) |

The bottom bar has keyboard, Esc, Tab, sticky Ctrl/Alt/Shift/Super, arrows,
Delete, paste (phone clipboard to laptop) and disconnect.

## Build

Requirements: JDK 17, Android SDK with NDK 29.0.13113456 and CMake 4.1.2,
`local.properties` with `sdk.dir`.

```
scripts/fetch-freerdp.sh      # clone FreeRDP 3.32.2 into third_party/ and verify the commit
scripts/build-native.sh       # applies scripts/patches/, builds OpenSSL + FreeRDP for arm64-v8a
GO=<path-to-go> scripts/build-tsbridge.sh   # Go fetches toolchain 1.27.1; writes app/libs/tsbridge.aar
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk (~34 MB)
./gradlew testDebugUnitTest
(cd agent && go test ./...)
(cd tsbridge && GOTOOLCHAIN=go1.27.1 go test ./...)
```

`scripts/patches/0001-android-event-queue-lock.patch` adds a lock to FreeRDP's
Android input event queue: the UI thread pushes key and pointer events while
FreeRDP's thread pops them, and the upstream queue has no lock.

The release APK is signed with the local debug key, which is fine for your own
phone. Use a real keystore before giving the APK to anyone.

## Security notes

- RDP password and agent token are encrypted with an AES-256-GCM key in the
  Android Keystore. App backup and device transfer are off.
- The session screen sets `FLAG_SECURE`: no screenshots, blank in recent apps.
- The clipboard channel is phone-to-laptop only (`/clipboard:direction-to:remote`),
  so laptop clipboard data never reaches the phone. Paste is explicit.
- The agent binds only to a Tailscale (100.64.0.0/10) or loopback address,
  compares tokens in constant time, answers 429 to further bad tokens for 1
  minute after 5 in a row, and limits request bodies to 4 KiB. A valid token
  is never blocked, since every client appears as 127.0.0.1 behind userspace
  Tailscale.
- The laptop address must be a literal 100.64.0.0/10 IP, and the agent token
  only goes to tsbridge's 127.0.0.1 relay for that IP. The network security
  config allows plain HTTP to 127.0.0.1 only; the relay carries it inside
  WireGuard.
- The embedded node's key lives in the app's private files directory, excluded
  from backup. Remove the `rdp-mobile` device in the Tailscale admin console
  if the phone is lost.
- A work laptop holding client or regulated data needs IT approval before
  remote access is enabled.

## Limits

- A sleeping laptop cannot be woken over Wi-Fi. Keep-awake must be on before you leave.
- arm64 phones only. Run `scripts/build-native.sh x86_64` and add the ABI to
  both `build.gradle.kts` files for an emulator.
- No audio, file transfer, or multi-monitor. GNOME 42 cannot send RDP audio,
  so notification sounds come from the phone's own notification channel.

## License

FreeRDP is Apache License 2.0. `rdpcore/.../LibFreeRDP.java` keeps the class
name and JNI signatures from FreeRDP's Android client.
