package rdpmobile.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.freerdp.freerdpcore.services.LibFreeRDP
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

sealed interface SessionState {
    data object Connecting : SessionState
    data object Connected : SessionState
    data class Failed(val reason: String) : SessionState
    data object Closed : SessionState
}

/** A certificate the user must accept before the connection continues. */
data class CertPrompt(
    val host: String,
    val subject: String,
    val issuer: String,
    val fingerprint: String,
    val changed: Boolean,
    val answer: (Boolean) -> Unit,
)

/** Remote cursor image as sent by the server; null pixels means hidden. */
class RemotePointer(val bitmap: Bitmap?, val hotX: Int, val hotY: Int)

/**
 * One RDP connection. FreeRDP runs it on its own native thread and calls the
 * Listener methods from there; UI code observes the StateFlows and is told
 * to redraw through [onFrame].
 */
class RdpSession(
    private val appContext: Context,
    private val config: LaptopConfig,
    private val store: ConfigStore,
) : LibFreeRDP.Listener {

    private val _state = MutableStateFlow<SessionState>(SessionState.Connecting)
    val state: StateFlow<SessionState> = _state

    private val _certPrompt = MutableStateFlow<CertPrompt?>(null)
    val certPrompt: StateFlow<CertPrompt?> = _certPrompt

    private val _pointer = MutableStateFlow<RemotePointer?>(null)
    val pointer: StateFlow<RemotePointer?> = _pointer

    @Volatile var bitmap: Bitmap? = null
        private set

    /** Set by the view; called on the native thread after pixels change. */
    @Volatile var onFrame: (() -> Unit)? = null

    /**
     * The native instance. Every native call from outside FreeRDP's own thread
     * holds the read lock and reads inst once; release() clears it under the
     * write lock, so no caller can use an instance that is about to be freed.
     */
    private var inst = 0L
    private val instLock = ReentrantReadWriteLock()
    @Volatile private var userClosed = false
    @Volatile private var wasConnected = false

    /** Runs the connection setup; owned by the session, not by an activity. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null

    private inline fun withInst(block: (Long) -> Unit) {
        instLock.read {
            val i = inst
            if (i != 0L) block(i)
        }
    }

    /**
     * Waits for the embedded Tailscale node, opens the RDP relay port and
     * starts FreeRDP. Safe to call again; only the first call does anything.
     */
    fun connect(width: Int, height: Int) {
        if (connectJob != null) return
        connectJob = scope.launch {
            if (!Tailnet.awaitRunning(TAILNET_WAIT_MS)) {
                _state.value = SessionState.Failed("Tailscale is not connected. Check the sign-in card on the home screen.")
                return@launch
            }
            // FreeRDP may retry the TCP connection during negotiation, so the
            // relay allows a few connections for a short time.
            val port = try {
                Tailnet.localPort(config.host.trim(), config.rdpPort, maxConns = 3, windowSec = 30)
            } catch (e: CancellationException) {
                throw e // disconnect() cancelled us; not a failure
            } catch (e: Exception) {
                _state.value = SessionState.Failed(e.message ?: "Could not open the Tailscale relay")
                return@launch
            }
            start(width, height, port)
        }
    }

    private fun start(width: Int, height: Int, localPort: Int) {
        val i = LibFreeRDP.newInstance(appContext, this)
        if (i == 0L) {
            _state.value = SessionState.Failed("Could not create RDP instance")
            return
        }
        if (!LibFreeRDP.parseArguments(i, buildArgs(width, height, localPort))) {
            _state.value = SessionState.Failed(LibFreeRDP.lastError(i) ?: "Could not start RDP")
            LibFreeRDP.freeInstance(i)
            return
        }
        // Publish inst and start FreeRDP's thread under the same lock that
        // disconnect() takes to set userClosed: either disconnect() runs first
        // and this never connects, or it runs after and sees a live inst to
        // stop. Nothing can be left running without a screen. Holding the
        // lock here is safe: connect only spawns the thread, and a callback
        // that needs the lock (release) just waits until it is dropped.
        val started = instLock.write {
            if (userClosed) {
                null
            } else {
                inst = i // set before the thread exists, so its callbacks see it
                LibFreeRDP.connect(i).also { ok -> if (!ok) inst = 0L }
            }
        }
        when (started) {
            null -> {
                LibFreeRDP.freeInstance(i)
                _state.value = SessionState.Closed
            }
            false -> {
                _state.value = SessionState.Failed(LibFreeRDP.lastError(i) ?: "Could not start RDP")
                LibFreeRDP.freeInstance(i)
            }
            true -> {}
        }
    }

    private fun buildArgs(width: Int, height: Int, localPort: Int): Array<String> = arrayOf(
        "rdpmobile",
        "/gdi:sw",
        "/v:127.0.0.1",
        "/port:$localPort",
        "/u:${config.username}",
        "/p:${config.password}",
        "/size:${width}x$height",
        "/bpp:32",
        "/gfx",
        "/network:auto",
        "/disp",
        // Phone-to-laptop paste only: laptop clipboard data never reaches the phone.
        "/clipboard:direction-to:remote",
        "/kbd:unicode:on",
        "/audio-mode:2",
        "/client-hostname:rdp-mobile",
        "/log-level:WARN",
    )

    fun disconnect() {
        instLock.write { userClosed = true }
        connectJob?.cancel()
        withInst { LibFreeRDP.disconnect(it) }
        // A pending certificate prompt would otherwise block the native thread.
        _certPrompt.value?.answer?.invoke(false)
        if (_state.value == SessionState.Connecting && inst == 0L) _state.value = SessionState.Closed
    }

    // ---- input ----

    fun pointer(x: Int, y: Int, flags: Int) {
        if (_state.value != SessionState.Connected) return
        withInst { LibFreeRDP.sendCursorEvent(it, x, y, flags) }
    }

    fun key(vk: Int, down: Boolean) {
        withInst { LibFreeRDP.sendKeyEvent(it, vk, down) }
    }

    fun tapKey(vk: Int) {
        key(vk, true)
        key(vk, false)
    }

    fun text(s: String) = withInst { i ->
        s.codePoints().forEach { cp ->
            if (cp == '\n'.code) {
                LibFreeRDP.sendKeyEvent(i, VK.RETURN, true)
                LibFreeRDP.sendKeyEvent(i, VK.RETURN, false)
            } else {
                LibFreeRDP.sendUnicodeKeyEvent(i, cp, true)
                LibFreeRDP.sendUnicodeKeyEvent(i, cp, false)
            }
        }
    }

    fun sendClipboard(text: String) = withInst { LibFreeRDP.sendClipboardData(it, text) }

    // ---- LibFreeRDP.Listener (native thread) ----

    override fun onPreConnect() {}

    override fun onConnectionSuccess() {
        wasConnected = true
        _state.value = SessionState.Connected
    }

    /**
     * FreeRDP reports a user's own disconnect and a server-side drop through
     * this callback too (its run loop exits with a non-zero status).
     */
    override fun onConnectionFailure() {
        _state.value = when {
            userClosed -> SessionState.Closed
            wasConnected -> SessionState.Failed("Disconnected by the laptop")
            else -> SessionState.Failed(LibFreeRDP.lastError(inst)?.ifBlank { null } ?: "Connection failed")
        }
        release()
    }

    override fun onDisconnecting() {}

    override fun onDisconnected() {
        if (!userClosed && _state.value == SessionState.Connected) {
            _state.value = SessionState.Failed("Disconnected by the laptop")
        } else if (_state.value !is SessionState.Failed) {
            _state.value = SessionState.Closed
        }
        release()
    }

    /**
     * Called from the last callback on FreeRDP's thread, which exits right
     * after it returns. freerdp_free does not join that thread, so the free
     * runs on another thread after a delay that outlasts the thread's exit.
     */
    private fun release() {
        val i = instLock.write {
            val old = inst
            inst = 0L
            old
        }
        scope.cancel()
        if (i != 0L) {
            Thread {
                Thread.sleep(FREE_DELAY_MS)
                LibFreeRDP.freeInstance(i)
            }.start()
        }
    }

    override fun onSettingsChanged(width: Int, height: Int, bpp: Int) = newBitmap(width, height)

    override fun onGraphicsResize(width: Int, height: Int, bpp: Int) = newBitmap(width, height)

    /**
     * Runs on FreeRDP's native thread. An exception here would stay pending in
     * JNI while FreeRDP keeps calling into Java, so failures are logged and
     * the old bitmap is kept.
     */
    private fun newBitmap(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .onSuccess { bitmap = it }
            .onFailure { Log.w(TAG, "desktop bitmap ${width}x$height: ${it.message}") }
        onFrame?.invoke()
    }

    override fun onGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
        val b = bitmap ?: return
        // The native copy does no bounds check; keep it inside the bitmap.
        val x0 = x.coerceAtLeast(0)
        val y0 = y.coerceAtLeast(0)
        val w = (x + width).coerceAtMost(b.width) - x0
        val h = (y + height).coerceAtMost(b.height) - y0
        if (w <= 0 || h <= 0) return
        LibFreeRDP.updateGraphics(inst, b, x0, y0, w, h)
        onFrame?.invoke()
    }

    /** Credentials are passed as arguments; a second prompt means they were wrong. */
    override fun onAuthenticate(
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean = false

    /**
     * Trust on first use, confirmed by the user. The fingerprint is pinned per
     * host:port; a later mismatch is shown as a warning, never accepted silently.
     */
    override fun onVerifyCertificate(
        host: String,
        port: Long,
        commonName: String,
        subject: String,
        issuer: String,
        fingerprint: String,
        flags: Long,
    ): Int {
        // The user already left: answer at once instead of waiting for a
        // prompt nobody will see.
        if (userClosed) return LibFreeRDP.CERT_REJECT
        val fp = runCatching { normalizeFingerprint(fingerprint) }.getOrElse {
            Log.w(TAG, "unreadable certificate fingerprint")
            return LibFreeRDP.CERT_REJECT
        }
        val pinned = store.pin(config.pinKey)
        if (pinned == fp) return LibFreeRDP.CERT_ACCEPT_SESSION

        val latch = CountDownLatch(1)
        var accepted = false
        // FreeRDP sees 127.0.0.1 (the relay); show the laptop's tailnet address.
        _certPrompt.value = CertPrompt(config.host, subject, issuer, fp, changed = pinned != null) { ok ->
            accepted = ok
            _certPrompt.value = null
            latch.countDown()
        }
        if (!latch.await(2, TimeUnit.MINUTES) || !accepted) {
            _certPrompt.value = null
            return LibFreeRDP.CERT_REJECT
        }
        store.setPin(config.pinKey, fp)
        return LibFreeRDP.CERT_ACCEPT_SESSION
    }

    /** Remote-to-phone clipboard is not copied, so laptop data stays on the laptop. */
    override fun onRemoteClipboardChanged(data: String) {}

    override fun onPointerSet(pixels: IntArray, width: Int, height: Int, hotX: Int, hotY: Int) {
        // Native thread: never let a bad cursor image throw into JNI.
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        runCatching { Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888) }
            .onSuccess { _pointer.value = RemotePointer(it, hotX, hotY) }
            .onFailure { Log.w(TAG, "cursor bitmap: ${it.message}") }
        onFrame?.invoke()
    }

    override fun onPointerSetNull() {
        _pointer.value = RemotePointer(null, 0, 0)
        onFrame?.invoke()
    }

    override fun onPointerSetDefault() {
        _pointer.value = null
        onFrame?.invoke()
    }

    private companion object {
        const val TAG = "RdpSession"
        const val FREE_DELAY_MS = 2000L
        const val TAILNET_WAIT_MS = 20_000L
    }
}

/** Windows virtual-key codes used by the on-screen key bar and hardware keys. */
object VK {
    const val BACK = 0x08
    const val TAB = 0x09
    const val RETURN = 0x0D
    const val ESCAPE = 0x1B
    const val PRIOR = 0x21
    const val NEXT = 0x22
    const val END = 0x23
    const val HOME = 0x24
    const val LEFT = 0x25
    const val UP = 0x26
    const val RIGHT = 0x27
    const val DOWN = 0x28
    const val DELETE = 0x2E
    const val LWIN = 0x5B
    const val F1 = 0x70
    const val LSHIFT = 0xA0
    const val LCONTROL = 0xA2
    const val LMENU = 0xA4
}

/** Holds the live session so it survives SessionActivity being recreated. */
object Sessions {
    @Volatile var current: RdpSession? = null

    /** True while SessionActivity is in the foreground, so the laptop screen is in view. */
    @Volatile var visible = false
}
