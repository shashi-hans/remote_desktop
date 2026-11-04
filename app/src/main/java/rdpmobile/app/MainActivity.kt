package rdpmobile.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

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
                    var config by remember { mutableStateOf(store.load()) }
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
                            config = saved
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

private sealed interface AgentView {
    data object None : AgentView
    data object Loading : AgentView
    data class Online(val s: AgentStatus) : AgentView
    data class Offline(val reason: String) : AgentView
}

@Composable
private fun HomeScreen(config: LaptopConfig, onEdit: () -> Unit, onHelp: () -> Unit, onConnect: () -> Unit) {
    val scope = rememberCoroutineScope()
    val client = remember(config) { AgentClient(config) }
    var agent by remember { mutableStateOf<AgentView>(if (config.hasAgent) AgentView.Loading else AgentView.None) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmLock by remember { mutableStateOf(false) }
    val tailnet by Tailnet.status.collectAsState()

    suspend fun refresh() {
        if (!config.hasAgent) return
        agent = AgentView.Loading
        agent = runCatching { client.status() }
            .fold({ AgentView.Online(it) }, { AgentView.Offline(it.message ?: "Not reachable") })
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
                IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Default.Refresh, "Refresh") }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Settings, "Settings") }
        }

        TailnetCard(tailnet)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(config.host, style = MaterialTheme.typography.bodyMedium)
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
                                "Remote Desktop is not listening on the laptop. Turn it on in GNOME Settings → Sharing.",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Keep awake", modifier = Modifier.weight(1f))
                            Switch(checked = s.keepAwake, onCheckedChange = { on ->
                                scope.launch {
                                    runCatching { client.setKeepAwake(on) }
                                        .onFailure { message = it.message }
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

        if (config.hasAgent) NotificationsCard()

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
                                .onSuccess { message = "Laptop locked" }
                                .onFailure { message = it.message }
                            refresh()
                        }
                    }) { Text("Lock") }
                },
                dismissButton = { TextButton(onClick = { confirmLock = false }) { Text("Cancel") } },
            )
        }

        if (agent is AgentView.Online) {
            OutlinedButton(
                onClick = { confirmLock = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Lock, null)
                Spacer(Modifier.padding(4.dp))
                Text("Lock laptop now")
            }
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Switch for forwarding laptop notifications to this phone (NotifyService). */
@Composable
private fun NotificationsCard() {
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

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) turnOn()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Laptop notifications on this phone", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { on ->
                    when {
                        !on -> {
                            NotifyService.setEnabled(context, false)
                            enabled = false
                        }
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                            PackageManager.PERMISSION_GRANTED -> turnOn()
                        else -> permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                })
            }
            Text(
                "Shows the laptop's pop-ups here with a sound, even when you are not connected. " +
                    "Message previews are hidden on the lock screen.",
                style = MaterialTheme.typography.bodySmall,
            )
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
            rdpPort = rdpPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 3389,
            agentPort = agentPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8765,
        )
        Button(onClick = { onSave(saved) }, enabled = saved.isComplete, modifier = Modifier.fillMaxWidth()) {
            Text("Save")
        }

        TailscaleSignOut()
    }
}
