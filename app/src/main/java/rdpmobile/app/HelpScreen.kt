package rdpmobile.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** In-app version of docs/USING-THE-APP.md. Keep the two in step. */
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("How to use", style = MaterialTheme.typography.headlineSmall)
        }

        Section(
            "After you connect",
            "The phone turns sideways and shows the laptop screen. A small arrow is the mouse pointer. " +
                "The key bar along the bottom has keys a phone keyboard lacks; swipe it sideways to see them all.",
        )

        Section("Moving the mouse", "The screen works like a touchpad. Clicks happen where the pointer is, not where your finger is.") {
            Rows(
                "Slide one finger" to "Move the pointer",
                "Tap" to "Left click",
                "Tap twice quickly" to "Double click",
                "Two-finger tap" to "Right click",
                "Slide two fingers" to "Scroll",
                "Pinch" to "Zoom in or out",
                "Hold, then slide" to "Drag (select text, move windows)",
            )
        }

        Section(
            "Typing",
            "Tap the keyboard button on the key bar to open the phone keyboard, and again to close it. " +
                "Autocorrect is off so the laptop gets exactly what you type.",
        )

        Section("Key bar") {
            Rows(
                "Keyboard" to "Open or close the phone keyboard",
                "Esc, Tab, Del, arrows" to "The same keys as on the laptop",
                "Ctrl, Alt, Shift, Super" to "Stay pressed until the next key-bar key",
                "Paste" to "Paste your phone's copied text into the laptop",
                "✕" to "Disconnect",
            )
        }

        Section("Shortcuts on the laptop") {
            Rows(
                "Super, Super" to "Open Activities (find apps and windows)",
                "Alt, then Tab" to "Switch to the last window",
                "Ctrl, Alt, then ← or →" to "Switch workspace",
                "Super, type l, Super" to "Lock the laptop",
            )
            Text(
                "A hold key lets go by itself after a key-bar key. After a key typed on the phone keyboard, tap it again to let go.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section(
            "Copy and paste",
            "Phone to laptop: copy on the phone, click the spot on the laptop, tap Paste. " +
                "Laptop to phone is not supported, so laptop data stays on the laptop.",
        )

        Section(
            "Screen size",
            "Settings → Screen size. 1280 × 720 is easiest to read on a phone. " +
                "1920 × 1080 fits more but needs zoom. Connect again after changing it.",
        )

        Section(
            "Locked laptop",
            "Type your Linux password with the phone keyboard and press Enter. " +
                "This needs the Allow Locked Remote Desktop extension on the laptop.",
        )

        Section(
            "Notifications",
            "Turn on \"Laptop notifications\" on the home screen. While the session screen is open, " +
                "laptop pop-ups only play the tone, since you can already see them. Claude Code alerts always show in full.",
        )

        Section(
            "Disconnecting",
            "Tap ✕ or press Back, then Disconnect. The laptop stays signed in and nothing closes. " +
                "Use Lock laptop now on the home screen to lock it.",
        )

        Section("If something goes wrong") {
            Rows(
                "Trust this laptop?" to "Check the fingerprint matches the laptop, then Trust",
                "Certificate changed" to "Do not trust unless you reset Remote Desktop yourself",
                "Tailscale is not connected" to "Check the Tailscale line on the home screen; sign in again if asked",
                "Disconnected by the laptop" to "It locked, slept, or another device connected",
                "Transport layer failed" to "Usually a wrong RDP username or password",
                "Laptop Offline" to "It is asleep or off; turn on Keep awake before you leave",
            )
        }
    }
}

@Composable
private fun Section(title: String, text: String? = null, extra: @Composable () -> Unit = {}) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            text?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            extra()
        }
    }
}

@Composable
private fun Rows(vararg rows: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { (action, result) ->
            Row {
                Text(action, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.45f))
                Text(result, modifier = Modifier.weight(0.55f))
            }
        }
    }
}
