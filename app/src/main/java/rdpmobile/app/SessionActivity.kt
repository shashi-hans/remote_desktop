package rdpmobile.app

import android.content.ClipboardManager
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.PaddingValues
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class SessionActivity : ComponentActivity() {

    private lateinit var store: ConfigStore
    private lateinit var config: LaptopConfig
    private lateinit var session: RdpSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The remote screen may show private data; keep it out of screenshots
        // and the recent-apps thumbnail.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        store = ConfigStore(this)
        config = store.loadOrNull() ?: run {
            // The keystore cannot decrypt the settings right now.
            Toast.makeText(this, "Settings could not be read. Try again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Tailnet.start(this)
        val existing = Sessions.current
        if (existing == null && savedInstanceState != null) {
            // Android killed the app while this screen was open and is now
            // restoring it. Do not reconnect behind the user's back; go home.
            finish()
            return
        }
        session = existing ?: RdpSession(applicationContext, config, store).also {
            Sessions.current = it
            val (w, h) = desktopSize()
            it.connect(w, h)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SessionScreen()
            }
        }
    }

    private fun desktopSize(): Pair<Int, Int> = when (config.resolution) {
        Resolution.HD -> 1280 to 720
        Resolution.FHD -> 1920 to 1080
        Resolution.PHONE -> {
            // displayMetrics works on API 29; currentWindowMetrics needs API 30.
            val m = resources.displayMetrics
            // RDP wants a width that is a multiple of 4.
            (maxOf(m.widthPixels, m.heightPixels) and 3.inv()) to minOf(m.widthPixels, m.heightPixels)
        }
    }

    override fun onResume() {
        super.onResume()
        Sessions.visible = true
    }

    override fun onPause() {
        Sessions.visible = false
        super.onPause()
    }

    override fun onDestroy() {
        // session is unset when onCreate finished early after process death.
        if (isFinishing && ::session.isInitialized) {
            session.disconnect()
            Sessions.current = null
        }
        super.onDestroy()
    }

    @Composable
    private fun SessionScreen() {
        val state by session.state.collectAsState()
        val cert by session.certPrompt.collectAsState()
        var confirmExit by remember { mutableStateOf(false) }

        BackHandler { confirmExit = true }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (val s = state) {
                SessionState.Connecting -> Centered {
                    CircularProgressIndicator()
                    Text("Connecting to ${config.name}…", color = Color.White, modifier = Modifier.padding(top = 16.dp))
                }
                SessionState.Connected -> Column(
                    Modifier.fillMaxSize()
                        // Keep the desktop and key bar clear of a camera cutout
                        // (edge-to-edge is forced from Android 15).
                        .windowInsetsPadding(WindowInsets.displayCutout)
                        .imePadding(),
                ) {
                    var view by remember { mutableStateOf<RemoteView?>(null) }
                    AndroidView(
                        factory = { ctx -> RemoteView(ctx, session).also { view = it } },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    KeyBar(onKeyboard = { view?.let(::toggleKeyboard) }, onClose = { confirmExit = true })
                }
                is SessionState.Failed -> Centered {
                    Text("Connection ended", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text(s.reason, color = Color.LightGray, modifier = Modifier.padding(vertical = 12.dp))
                    Button(onClick = { finish() }) { Text("Back") }
                }
                SessionState.Closed -> Centered {
                    Text("Disconnected", color = Color.White)
                    Button(onClick = { finish() }, modifier = Modifier.padding(top = 12.dp)) { Text("Back") }
                }
            }
        }

        cert?.let { CertDialog(it) }

        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("Disconnect?") },
                text = { Text("The laptop session stays signed in. Lock it from the home screen if needed.") },
                confirmButton = { TextButton(onClick = { finish() }) { Text("Disconnect") } },
                dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Stay") } },
            )
        }
    }

    @Composable
    private fun CertDialog(p: CertPrompt) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (p.changed) "⚠ Certificate changed" else "Trust this laptop?") },
            text = {
                Column {
                    if (p.changed) {
                        Text(
                            "The laptop's RDP certificate is not the one you trusted before. " +
                                "This happens after reinstalling or resetting Remote Desktop, " +
                                "but it can also mean someone is intercepting the connection.",
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    Text("Host: ${p.host}")
                    Text("Subject: ${p.subject}")
                    Text("SHA-256:", modifier = Modifier.padding(top = 8.dp))
                    Text(p.fingerprint, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text(
                        "Compare it with the laptop's value from:\n" +
                            "openssl x509 -noout -fingerprint -sha256 -in ~/.local/share/gnome-remote-desktop/rdp-tls.crt",
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { p.answer(true) }) { Text("Trust") } },
            dismissButton = { TextButton(onClick = { p.answer(false); finish() }) { Text("Cancel") } },
        )
    }

    @Composable
    private fun KeyBar(onKeyboard: () -> Unit, onClose: () -> Unit) {
        var held by remember { mutableStateOf(setOf<Int>()) }

        fun toggleMod(vk: Int) {
            val down = vk !in held
            session.key(vk, down)
            held = if (down) held + vk else held - vk
        }

        fun tap(vk: Int) {
            session.tapKey(vk)
            held.forEach { session.key(it, false) }
            held = emptySet()
        }

        // Leaving the connected screen must not leave Ctrl or Alt pressed on the laptop.
        DisposableEffect(Unit) {
            onDispose { held.forEach { session.key(it, false) } }
        }

        Row(
            Modifier.fillMaxWidth().background(Color(0xFF1E1E1E)).horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onKeyboard) { Icon(Icons.Default.Keyboard, "Keyboard", tint = Color.White) }
            KeyButton("Esc") { tap(VK.ESCAPE) }
            KeyButton("Tab") { tap(VK.TAB) }
            ModButton("Ctrl", VK.LCONTROL in held) { toggleMod(VK.LCONTROL) }
            ModButton("Alt", VK.LMENU in held) { toggleMod(VK.LMENU) }
            ModButton("Shift", VK.LSHIFT in held) { toggleMod(VK.LSHIFT) }
            ModButton("Super", VK.LWIN in held) { toggleMod(VK.LWIN) }
            KeyButton("←") { tap(VK.LEFT) }
            KeyButton("↑") { tap(VK.UP) }
            KeyButton("↓") { tap(VK.DOWN) }
            KeyButton("→") { tap(VK.RIGHT) }
            KeyButton("Del") { tap(VK.DELETE) }
            IconButton(onClick = { pasteToLaptop(ctrlHeld = VK.LCONTROL in held) }) {
                Icon(Icons.Default.ContentPaste, "Paste phone clipboard", tint = Color.White)
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Disconnect", tint = Color.White) }
        }
    }

    /** Puts the phone clipboard on the laptop clipboard, then sends Ctrl+V. */
    private fun pasteToLaptop(ctrlHeld: Boolean) {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Phone clipboard is empty", Toast.LENGTH_SHORT).show()
            return
        }
        session.sendClipboard(text)
        window.decorView.postDelayed({
            if (ctrlHeld) {
                // Ctrl is already down from the key bar; leave it that way.
                session.tapKey('V'.code)
            } else {
                session.key(VK.LCONTROL, true)
                session.tapKey('V'.code)
                session.key(VK.LCONTROL, false)
            }
        }, 150)
    }

    private fun toggleKeyboard(v: RemoteView) {
        v.requestFocus()
        val imm = getSystemService(InputMethodManager::class.java)
        val visible = ViewCompat.getRootWindowInsets(v)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (visible) imm.hideSoftInputFromWindow(v.windowToken, 0) else imm.showSoftInput(v, 0)
    }
}

private val KeyPadding = PaddingValues(horizontal = 10.dp)

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, contentPadding = KeyPadding) {
        Text(label)
    }
}

@Composable
private fun ModButton(label: String, active: Boolean, onClick: () -> Unit) {
    // Held state is shown by colour, so it is also announced to screen readers.
    val a11y = Modifier.semantics { stateDescription = if (active) "held" else "not held" }
    if (active) {
        Button(onClick = onClick, contentPadding = KeyPadding, modifier = a11y) {
            Text(label)
        }
    } else {
        FilledTonalButton(onClick = onClick, contentPadding = KeyPadding, modifier = a11y) {
            Text(label)
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}
