package rdpmobile.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import rdpmobile.ts.tsbridge.InterfaceSource
import rdpmobile.ts.tsbridge.Tsbridge
import java.io.File
import java.net.NetworkInterface

sealed interface TailnetStatus {
    data object Starting : TailnetStatus
    /** [error] explains a failed attempt, for example no sign-in link. */
    data class NeedsLogin(val url: String, val error: String) : TailnetStatus
    data object Running : TailnetStatus
    data class Error(val message: String) : TailnetStatus
}

/**
 * The embedded Tailscale node (tsbridge, built from ../tsbridge with gomobile).
 * The app joins the tailnet as its own device; traffic to the laptop goes
 * through 127.0.0.1 ports that the bridge relays over the tailnet.
 */
object Tailnet {
    private const val TAG = "Tailnet"
    private const val HOSTNAME = "rdp-mobile"
    private const val MIN_RETRY_MS = 10_000L
    private const val MAX_RETRY_MS = 300_000L
    private const val RUNNING_POLL_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<TailnetStatus>(TailnetStatus.Starting)
    val status: StateFlow<TailnetStatus> = _status

    @Volatile private var started = false
    @Volatile private var loginTabOpen = false
    private lateinit var appContext: Context

    /** Starts the node once per process and keeps [status] updated. */
    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        appContext = context.applicationContext
        val dir = File(appContext.filesDir, "tailscale").path
        scope.launch {
            var retryMs = MIN_RETRY_MS
            var nextStartAt = 0L
            while (true) {
                val state = Tsbridge.state()
                val now = SystemClock.elapsedRealtime()
                when (state) {
                    // A healthy node: the next error starts a fresh backoff.
                    "Running", "NeedsLogin" -> {
                        retryMs = MIN_RETRY_MS
                        nextStartAt = 0L
                    }
                    // First sight of an error: show it, retry after retryMs.
                    "Error" -> if (nextStartAt == 0L) nextStartAt = now + retryMs
                }
                // Start only when no node exists ("Stopped") or when an errored
                // node is due for a retry. Starting blindly would replace an
                // errored node at once and hide its error behind "Starting".
                if (state == "Stopped" || (state == "Error" && now >= nextStartAt)) {
                    try {
                        Tsbridge.start(dir, HOSTNAME, AndroidInterfaces)
                    } catch (e: Exception) {
                        // Includes "still stopping" while the old node closes;
                        // that is not a new failure, so the backoff stays put.
                        Log.w(TAG, "start failed: ${e.message}")
                        if (state == "Stopped") _status.value = TailnetStatus.Error(e.message ?: "Tailscale failed to start")
                        delay(2000)
                        continue
                    }
                    if (state == "Error") {
                        // Schedule the next retry now: a node that fails again
                        // within one poll must still wait, twice as long.
                        retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                        nextStartAt = now + retryMs
                    }
                }
                _status.value = when (Tsbridge.state()) {
                    "Running" -> {
                        if (loginTabOpen) returnFromLoginTab()
                        TailnetStatus.Running
                    }
                    "NeedsLogin" -> TailnetStatus.NeedsLogin(Tsbridge.loginURL(), Tsbridge.lastError())
                    "NeedsMachineAuth" -> TailnetStatus.Error("Approve this device in the Tailscale admin console")
                    "Error" -> TailnetStatus.Error(Tsbridge.lastError().ifBlank { "Tailscale error" })
                    else -> TailnetStatus.Starting
                }
                delay(
                    when (_status.value) {
                        TailnetStatus.Running -> RUNNING_POLL_MS
                        is TailnetStatus.Error -> 2000
                        else -> 700
                    },
                )
            }
        }
    }

    /** Waits until the node is running; false on timeout. */
    suspend fun awaitRunning(timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) { status.first { it == TailnetStatus.Running } } != null

    /**
     * Opens a 127.0.0.1 port that relays to [host]:[port] on the tailnet. It
     * accepts at most [maxConns] connections within [windowSec] seconds and
     * then closes, so open a new one for each use.
     */
    suspend fun localPort(host: String, port: Int, maxConns: Int, windowSec: Int): Int =
        withContext(Dispatchers.IO) {
            Tsbridge.forward("$host:$port", maxConns.toLong(), windowSec.toLong()).toInt()
        }

    suspend fun logout() = withContext(Dispatchers.IO) { Tsbridge.logout() }

    /**
     * Called when MainActivity is back in front, which means the login tab is
     * no longer on top. A sign-in finished elsewhere (for example in another
     * browser) must not pull the app forward or clear the activity stack.
     */
    fun loginTabGone() {
        loginTabOpen = false
    }

    /**
     * Closes the login Custom Tab once sign-in succeeds. The tab runs in the
     * app's own task above MainActivity, so bringing MainActivity back with
     * CLEAR_TOP removes it.
     */
    private fun returnFromLoginTab() {
        loginTabOpen = false
        runCatching {
            appContext.startActivity(
                Intent(appContext, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }.onFailure { Log.w(TAG, "could not close login tab: ${it.message}") }
    }

    /**
     * Opens the Tailscale login page as a Custom Tab over the app, falling back
     * to the browser when no Custom Tabs provider is installed.
     */
    fun openLogin(context: Context, url: String) {
        val uri = Uri.parse(url)
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
            loginTabOpen = true
        } catch (e: ActivityNotFoundException) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "No web browser found to sign in", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Network interfaces for the Go side. Android 11+ blocks Go's netlink
     * lookup for apps, but java.net.NetworkInterface still works.
     */
    private object AndroidInterfaces : InterfaceSource {
        override fun interfacesJSON(): String {
            val out = JSONArray()
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }
                .getOrNull().orEmpty()
            for (nif in ifaces) {
                runCatching {
                    val addrs = JSONArray()
                    for (ia in nif.interfaceAddresses) {
                        val ip = ia.address?.hostAddress ?: continue
                        addrs.put(JSONObject().put("ip", ip).put("prefix", ia.networkPrefixLength.toInt()))
                    }
                    out.put(
                        JSONObject()
                            .put("name", nif.name)
                            .put("index", nif.index)
                            .put("mtu", nif.mtu)
                            .put("up", nif.isUp)
                            .put("loopback", nif.isLoopback)
                            .put("p2p", nif.isPointToPoint)
                            .put("multicast", nif.supportsMulticast())
                            .put("addrs", addrs),
                    )
                }
            }
            return out.toString()
        }
    }

    /** True when [host] is a literal IPv4 address in 100.64.0.0/10. Does no DNS lookup. */
    fun isTailnetLiteral(host: String): Boolean {
        val parts = host.trim().split('.')
        if (parts.size != 4 || parts.any { p -> p.isEmpty() || p.length > 3 || !p.all(Char::isDigit) }) {
            return false
        }
        val bytes = parts.map { it.toInt() }
        if (bytes.any { it > 255 }) return false
        return bytes[0] == 100 && (bytes[1] and 0xc0) == 64
    }
}
