# Using Remote Desktop after you connect

This guide covers what you see and what you can do once the laptop screen is
open on your phone. For setup, see the [main README](../README.md).

A shorter version is inside the app: on the home screen, scroll to **How to
use** and tap **Full guide**. The in-app text lives in `HelpScreen.kt`; update
both when the controls change.

## The session screen

After you tap **Connect**, the phone turns sideways and shows the laptop
screen. It has two parts:

- **The laptop screen** fills most of the phone. A small arrow shows where the
  mouse pointer is.
- **The key bar** runs along the bottom. It has keys that a phone keyboard
  does not have. Swipe it left and right to see all the buttons.

The phone screen stays on while you are connected. Screenshots and screen
recording are blocked, and the app shows blank in the recent-apps list, so
nothing from the laptop is saved on the phone by accident.

## Moving the mouse

The phone screen works like a laptop touchpad, not like a touch screen. You
move the pointer, and clicks happen where the **pointer** is, not where your
finger is.

| What you do | What happens on the laptop |
| --- | --- |
| Slide one finger | Moves the pointer |
| Tap once | Left click |
| Tap twice quickly | Double click |
| Tap with two fingers at the same time | Right click |
| Slide two fingers up or down | Scroll |
| Pinch out / pinch in | Zoom in / out (1x to 4x) |
| Press and hold, then slide | Hold the left button and drag (select text, move a window) |

Tips:

- Small movements are easier when zoomed in. When you zoom, the view follows
  the pointer, so it never goes off screen.
- To drag, wait for the short vibration after pressing, then slide. Lift your
  finger to let go.
- Scrolling moves the page the same way your fingers move, like on the phone.

## Typing

1. Tap the **keyboard** button (first button on the key bar). The phone
   keyboard opens.
2. Type normally. With a normal keyboard each letter goes to the laptop as
   you type it. Swipe and voice typing send a whole word once the keyboard
   confirms it.
3. Tap the keyboard button again to close it.

Suggestions and autocorrect are turned off on purpose, so the laptop gets
exactly what you type. Enter, Backspace and the arrow keys on the phone
keyboard work. A Bluetooth or USB keyboard connected to the phone also works,
including Ctrl, Alt, Shift, the Windows/Super key and F1 to F12.

## The key bar

| Button | What it does |
| --- | --- |
| ⌨ Keyboard | Opens or closes the phone keyboard |
| Esc | Escape key: close menus and dialogs |
| Tab | Tab key: move to the next field |
| Ctrl, Alt, Shift, Super | Hold keys (see below) |
| ← ↑ ↓ → | Arrow keys |
| Del | Delete key |
| 📋 Paste | Pastes your phone's copied text into the laptop |
| ✕ Close | Disconnect |

### Ctrl, Alt, Shift and Super

These keys stay pressed after you tap them, and the button changes color while
held. To press a shortcut:

1. Tap the hold key, for example **Ctrl**. It changes color.
2. Tap the second key **on the key bar**, for example **Tab** or **←**.
3. The hold key lets go by itself after that key.

If you type the second key on the phone keyboard instead (for example the
letter `c` for Ctrl+C), the hold key stays pressed; tap it again to let go.
Phone-keyboard letters are sent as typed characters, so whether the laptop
treats Ctrl plus a typed letter as a shortcut depends on the app. For letter
shortcuts, a Bluetooth or USB keyboard is the most reliable.

Useful shortcuts on the Ubuntu laptop:

| Shortcut | How to press it | What it does |
| --- | --- | --- |
| Super | Tap **Super** twice | Opens Activities (search apps and windows) |
| Alt+Tab | **Alt**, then **Tab** | Switches to the last window |
| Ctrl+C / Ctrl+V | **Ctrl**, type `c` or `v`, tap **Ctrl** again | Copy / paste on the laptop |
| Ctrl+Alt+← / → | **Ctrl**, **Alt**, then **←** or **→** | Switches workspace |
| Super+L | **Super**, type `l`, tap **Super** again | Locks the laptop screen |

## Copy and paste

- **Phone to laptop:** copy text on the phone first. Then click where you
  want it on the laptop and tap the **📋 Paste** button. The app puts the text
  on the laptop's clipboard and presses Ctrl+V for you.
- **Laptop to phone:** not supported. Text you copy on the laptop stays on the
  laptop.

## Screen size

The laptop screen size is set before you connect, in **Settings → Screen
size**:

| Option | Best for |
| --- | --- |
| 1280 × 720 | Easy reading on a phone; bigger text and buttons |
| 1920 × 1080 | More fits on screen, but text is small; use pinch zoom |
| Match phone | Uses the phone's own shape; sharpest, but the smallest text |

Change it in Settings, then connect again.

## Home screen status and locking

Before you connect, the home screen card shows the laptop's live state: online
or offline, **Screen locked** or **Screen unlocked**, battery, and Keep awake.
Next to the address it shows when it was last checked; tap the refresh button
(it spins while checking) to update it.

The button below the notification switch changes with the lock state:

- **🔒 Lock laptop now** when the laptop is unlocked. It asks first, then locks
  the laptop's screen.
- **🔓 Unlock laptop** when it is locked. It opens the remote session on the
  laptop's lock screen; type your Linux password with the phone keyboard and
  press Enter, the same as sitting at the laptop.

Unlocking from the phone needs the "Allow Locked Remote Desktop" extension
loaded on the laptop (log out and in once after installing it). Without it,
Remote Desktop pauses while the laptop is locked: the card then says to unlock
it at the laptop, and the Unlock button is hidden.

## Notifications while connected

If the laptop shows a pop-up while you are looking at the session screen, you
see it on the laptop screen and the phone plays the notification tone. The
phone does not add a second copy to its own notification list.

Claude Code's "finished", "question" and "needs permission" alerts never
appear as pop-ups on the laptop, so they always show as normal phone
notifications. The same happens for any laptop notification while the app is
in the background, even if the session is still connected.

## Disconnecting

- Tap **✕** on the key bar, or press the phone's **Back** button.
- Confirm with **Disconnect**.

Disconnecting does not log you out or lock the laptop. Everything stays open.
To lock the laptop, use **Lock laptop now** on the home screen after you
disconnect.

## When something goes wrong

| What you see | What to do |
| --- | --- |
| "Trust this laptop?" | Shows the first time, or after the laptop's certificate changes. Check the fingerprint matches the laptop (see README) and tap **Trust**. |
| "⚠ Certificate changed" | Do not trust it unless you reset Remote Desktop on the laptop yourself. |
| "Tailscale is not connected" | Open the home screen and check the Tailscale line. Sign in again if it asks. |
| "Disconnected by the laptop" | The laptop locked without the extension, went to sleep, or someone connected from another device. |
| "The connection transport layer failed" | Usually a wrong RDP username or password. Check Settings. |
| Laptop shows **Offline** | The laptop is asleep or off. It cannot be woken remotely. Turn on **Keep awake** before you leave. |
| The pointer feels slow on mobile data | The connection is going through a Tailscale relay. Use 1280 × 720 for a faster screen. |
