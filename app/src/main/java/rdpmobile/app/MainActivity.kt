package rdpmobile.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        Tailnet.loginTabGone()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)
        Tailnet.start(this)
        NotifyService.startIfEnabled(this)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    // null: the keystore could not decrypt the settings right now.
                    var loaded by remember { mutableStateOf(store.loadOrNull()) }
                    val config = loaded
                    if (config == null) {
                        SettingsUnavailable(onRetry = { loaded = store.loadOrNull() })
                        return@Surface
                    }
                    // Saveable so rotation keeps the current screen. Field values
                    // (including the password) stay in plain remember on purpose.
                    var editing by rememberSaveable { mutableStateOf(!config.isComplete) }
                    var help by rememberSaveable { mutableStateOf(false) }
                    if (help) {
                        BackHandler { help = false }
                        HelpScreen(onBack = { help = false })
                    } else if (editing) {
                        BackHandler(enabled = config.isComplete) { editing = false }
                        SettingsScreen(config, store) { saved ->
                            store.save(saved)
                            loaded = saved
                            editing = false
                        }
                    } else {
                        HomeScreen(
                            config,
                            onEdit = { editing = true },
                            onHelp = { help = true },
                            onConnect = { startActivity(Intent(this, SessionActivity::class.java)) },
                        )
                    }
                }
            }
        }
    }
}

private const val LOCK_SETTLE_MS = 1500L

/** Shown when the keystore cannot decrypt the settings right now. */
@Composable
private fun SettingsUnavailable(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Your saved settings could not be read right now.")
        Button(onClick = onRetry) { Text("Try again") }
    }
}
private const val STATUS_RETRY_MS = 2000L

private sealed interface AgentView {
    data object None : AgentView
    data object Loading : AgentView
    data class Online(val s: AgentStatus) : AgentView
    data class Offline(val reason: String) : AgentView
}

@Composable
private fun HomeScreen(config: LaptopConfig, onEdit: () -> Unit, onHelp: () -> Unit, onConnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(config) { AgentClient(config) }
    var agent by remember { mutableStateOf<AgentView>(if (config.hasAgent) AgentView.Loading else AgentView.None) }
    // Action results are brief toasts: a line that stays on screen goes stale
    // (for example "Laptop locked" after the laptop is unlocked again). The
    // status card is the only place that shows the current state.
    fun notify(text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    var confirmLock by remember { mutableStateOf(false) }
    val tailnet by Tailnet.status.collectAsState()

    // Each refresh gets a number; only the newest one may update the card, so
    // a slow old reply cannot overwrite a newer one.
    var refreshSeq by remember { mutableStateOf(0) }
    // Number of refreshes in flight: the spinner shows while any runs and
    // clears even if a newer one supersedes or a cancellation ends one.
    var inFlight by remember { mutableStateOf(0) }
    val refreshing = inFlight > 0
    var checkedAt by remember { mutableStateOf<String?>(null) }
    // Bumped only by a manual Refresh, so the notification details close then
    // and not on the automatic refresh after returning from a dialog.
    var collapseKey by remember { mutableStateOf(0) }

    suspend fun refreshOnce(seq: Int) {
        // Show "Checking…" only before the first answer; later refreshes keep
        // the current card so the switches and buttons do not flicker. The
        // spinner on the Refresh button shows that a check is running.
        if (agent !is AgentView.Online) agent = AgentView.Loading
        // The first request after the app starts can time out while Tailscale
        // is still finding a path to the laptop, so a network failure gets one
        // retry. Agent answers (wrong token, rate limit) are final: retrying a
        // bad token would only add to the agent's failure count.
        var attempt = runCatching { client.status() }
        val err = attempt.exceptionOrNull()
        if (err != null && err !is AgentException) {
            delay(STATUS_RETRY_MS)
            attempt = runCatching { client.status() }
        }
        val result = attempt.fold({ AgentView.Online(it) }, { AgentView.Offline(it.message ?: "Not reachable") })
        if (seq == refreshSeq) {
            agent = result
            // Seconds included, so a refresh within the same minute still
            // visibly updates the time.
            val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm:ss" else "h:mm:ss a"
            checkedAt = SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        }
    }

    suspend fun refresh() {
        if (!config.hasAgent) return
        val seq = ++refreshSeq
        inFlight++
        try {
            refreshOnce(seq)
        } finally {
            inFlight--
        }
    }

    // Refresh when the screen returns to the foreground and when Tailscale comes up.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val running = tailnet == TailnetStatus.Running
    LaunchedEffect(config, running) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh() }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(config.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (config.hasAgent) {
                IconButton(onClick = { collapseKey++; scope.launch { refresh() } }, enabled = !refreshing) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, "Refresh")
                    }
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Settings, "Settings") }
        }

        TailnetCard(tailnet)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    config.host + (checkedAt?.let { "  ·  checked $it" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (val a = agent) {
                    AgentView.None -> Text("Agent not set up: status, lock and keep-awake are off.")
                    AgentView.Loading -> Text("Checking…")
                    is AgentView.Offline -> {
                        Text("● Offline", color = MaterialTheme.colorScheme.error)
                        Text(a.reason, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "A sleeping laptop cannot be woken over Wi-Fi. Turn on keep-awake before you leave.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is AgentView.Online -> {
                        val s = a.s
                        Text("● Online (${s.hostname})", color = MaterialTheme.colorScheme.primary)
                        Text(
                            when (s.locked) {
                                true -> "Screen locked"
                                false -> "Screen unlocked"
                                null -> "Lock state unknown"
                            },
                        )
                        s.batteryPct?.let { Text("Battery $it%" + if (s.charging) " (charging)" else "") }
                        if (!s.rdpListening) {
                            Text(
                                if (s.locked == true) {
                                    // GNOME 42 pauses Remote Desktop while locked
                                    // unless the Allow Locked Remote Desktop extension runs.
                                    "Remote Desktop is paused while the laptop is locked. Unlock it at the laptop."
                                } else {
                                    "Remote Desktop is not listening on the laptop. Turn it on in GNOME Settings → Sharing."
                                },
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep awake", modifier = Modifier.weight(1f))
                            Switch(checked = s.keepAwake, onCheckedChange = { on ->
                                scope.launch {
                                    runCatching { client.setKeepAwake(on) }
                                        .onFailure { notify(it.message ?: "Keep awake failed") }
                                    refresh()
                                }
                            })
                        }
                    }
                }
            }
        }

        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Connect")
        }

        // Also shown while forwarding is on without an agent, so it can be turned off.
        if (config.hasAgent || NotifyService.isEnabled(context)) NotificationsCard(collapseKey = collapseKey)

        if (confirmLock) {
            AlertDialog(
                onDismissRequest = { confirmLock = false },
                title = { Text("Lock the laptop?") },
                text = {
                    Text(
                        "On Ubuntu 22.04 (GNOME 42), Remote Desktop stops while the screen is locked. " +
                            "You can connect again only after someone unlocks it at the laptop, unless " +
                            "the \"Allow Locked Remote Desktop\" extension is installed.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLock = false
                        scope.launch {
                            runCatching { client.lock() }
                                .onSuccess {
                                    notify("Lock sent")
                                    // GNOME takes a moment to report the new state.
                                    delay(LOCK_SETTLE_MS)
                                }
                                .onFailure { notify(it.message ?: "Lock failed") }
                            refresh()
                        }
                    }) { Text("Lock") }
                },
                dismissButton = { TextButton(onClick = { confirmLock = false }) { Text("Cancel") } },
            )
        }

        (agent as? AgentView.Online)?.let { online ->
            // The button shows the action for the current state. A locked
            // laptop is unlocked by connecting and typing the password on its
            // lock screen, so "Unlock" opens the remote session.
            val locked = online.s.locked == true
            // Unlocking remotely needs Remote Desktop to stay up while locked.
            // When it does not, the card above says to unlock at the laptop.
            if (locked && !online.s.rdpListening) return@let
            OutlinedButton(
                onClick = { if (locked) onConnect() else confirmLock = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(if (locked) Icons.Default.LockOpen else Icons.Default.Lock, null)
                Spacer(Modifier.padding(4.dp))
                Text(if (locked) "Unlock laptop" else "Lock laptop now")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("How to use", style = MaterialTheme.typography.titleMedium)
                Text("Slide one finger to move the pointer, tap to click, two-finger tap to right-click.")
                Text("Slide two fingers to scroll, pinch to zoom, hold then slide to drag.")
                Text("Use the key bar at the bottom for the keyboard, Esc, Ctrl, Alt and paste.")
                OutlinedButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) {
                    Text("Full guide")
                }
            }
        }
    }
}

/** Switch for forwarding laptop notifications to this phone (NotifyService). */
@Composable
private fun NotificationsCard(collapseKey: Int) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotifyService.isEnabled(context)) }

    fun turnOn() {
        NotifyService.setEnabled(context, true)
        enabled = true
        // Without the exemption, Doze cuts the connection while the screen is off.
        val pm = context.getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
            )
        }
    }

    // Set while the user is sent to the app's notification settings, so the
    // switch turns on by itself if they allow notifications there.
    var waitingForSettings by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> turnOn()
            // Android shows a rationale only between the first and the final
            // refusal. No rationale after a refusal means the prompt will not
            // appear again, so the app's notification settings are the only
            // way left to allow it.
            context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == false -> {
                waitingForSettings = true
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            }
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (waitingForSettings) {
                waitingForSettings = false
                if (NotificationManagerCompat.from(context).areNotificationsEnabled()) turnOn()
            }
        }
    }

    // Details start hidden and close again on each manual Refresh.
    var showInfo by remember(collapseKey) { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Laptop notifications", modifier = Modifier.weight(1f))
                IconButton(onClick = { showInfo = !showInfo }) {
                    Icon(Icons.Outlined.Info, if (showInfo) "Hide details" else "Show details")
                }
                Switch(checked = enabled, onCheckedChange = { on ->
                    when {
                        !on -> {
                            NotifyService.setEnabled(context, false)
                            enabled = false
                        }
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED -> turnOn()
                        else -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            }
            if (showInfo) {
                Text(
                    "Shows the laptop's pop-ups here with a sound, even when you are not connected. " +
                        "Message previews are hidden on the lock screen.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsScreen(initial: LaptopConfig, store: ConfigStore, onSave: (LaptopConfig) -> Unit) {
    var c by remember { mutableStateOf(initial) }
    var rdpPort by remember { mutableStateOf(initial.rdpPort.toString()) }
    var agentPort by remember { mutableStateOf(initial.agentPort.toString()) }
    var pinCleared by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Laptop", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(c.name, { c = c.copy(name = it) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            c.host, { c = c.copy(host = it.trim()) },
            label = { Text("Tailscale IP (100.x.y.z)") },
            singleLine = true,
            isError = c.host.isNotBlank() && !Tailnet.isTailnetLiteral(c.host),
            supportingText = { Text("On the laptop: tailscale ip -4") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Remote Desktop", style = MaterialTheme.typography.titleMedium)
        Text(
            "Use the Remote Desktop username and password from the laptop's GNOME Settings → Sharing, not your Linux password.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(rdpPort, { rdpPort = it.filter(Char::isDigit) }, label = { Text("RDP port") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(c.username, { c = c.copy(username = it) }, label = { Text("RDP username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            c.password, { c = c.copy(password = it) },
            label = { Text("RDP password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Screen size", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Resolution.entries.forEach { r ->
                FilterChip(selected = c.resolution == r, onClick = { c = c.copy(resolution = r) }, label = { Text(r.label) })
            }
        }
        if (store.pin(initial.pinKey) != null && !pinCleared) {
            TextButton(onClick = { store.clearPin(initial.pinKey); pinCleared = true }) {
                Text("Forget trusted certificate")
            }
        }

        Text("Laptop agent (optional)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Run 'rdpagent init' on the laptop and paste the token here. Leave empty to skip status, lock and keep-awake.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(agentPort, { agentPort = it.filter(Char::isDigit) }, label = { Text("Agent port") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            c.agentToken, { c = c.copy(agentToken = it.trim()) },
            label = { Text("Agent token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        val saved = c.copy(
            rdpPort = rdpPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: LaptopConfig.DEFAULT_RDP_PORT,
            agentPort = agentPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: LaptopConfig.DEFAULT_AGENT_PORT,
        )
        Button(onClick = { onSave(saved) }, enabled = saved.isComplete, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }

        TailscaleSignOut()
    }
}

/** The Activity behind a Compose context, if there is one. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
