package rdpmobile.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that long-polls the laptop agent over the embedded
 * Tailscale node and re-posts each laptop desktop notification on the phone
 * with the default notification sound.
 */
class NotifyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        try {
            startForeground(
                ONGOING_ID,
                Notification.Builder(this, CHANNEL_WATCH)
                    .setSmallIcon(R.drawable.ic_stat_desktop)
                    .setContentTitle("Watching laptop notifications")
                    .setContentIntent(openApp(this))
                    .setOngoing(true)
                    .build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } catch (e: Exception) {
            // Android 12+ refuses a background restart without the battery
            // exemption. Stop quietly; app launch or boot starts it again.
            Log.w(TAG, "cannot start in foreground: ${e.message}")
            stopSelf()
            return
        }
        Tailnet.start(this)
        scope.launch { pollLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var backoffMs = MIN_BACKOFF_MS
        val store = ConfigStore(this)
        while (scope.isActive) {
            val version = store.version()
            // null: settings unreadable for now; wait rather than treat an
            // empty config as a different laptop.
            val config = store.loadOrNull()
            if (config == null || !config.hasAgent || !Tailnet.awaitRunning(30_000)) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                continue
            }
            // The saved epoch and cursor belong to one laptop. For a different
            // one, forget them, so it starts from "now" instead of replaying
            // up to 100 old notes, each with a sound.
            val target = "${config.host.trim()}:${config.agentPort}"
            if (prefs.getString(KEY_TARGET, null) != target) {
                prefs.edit().remove(KEY_EPOCH).remove(KEY_CURSOR).putString(KEY_TARGET, target).apply()
            }
            try {
                val client = AgentClient(config)
                val epoch = prefs.getString(KEY_EPOCH, null)
                var cursor = prefs.getLong(KEY_CURSOR, -1)
                // A new agent process (new epoch) restarts ids at 1. If we were
                // following an older one, replay everything it has had since
                // it started; on first use, start from "now".
                val first = client.notifications(-1, 0)
                if (first.epoch != epoch) cursor = if (epoch == null) first.latest else 0L
                saveCursor(first.epoch, cursor)
                backoffMs = MIN_BACKOFF_MS

                while (scope.isActive) {
                    val r = client.notifications(cursor, WAIT_SEC)
                    if (r.epoch != first.epoch) break // agent restarted mid-loop
                    r.items.forEach(::post)
                    cursor = maxOf(r.latest, r.items.maxOfOrNull { it.id } ?: cursor)
                    saveCursor(r.epoch, cursor)
                    // Settings were saved again (maybe a new laptop or token):
                    // start over. Comparing a counter avoids a decrypt per poll.
                    if (store.version() != version) break
                }
            } catch (e: Exception) {
                Log.w(TAG, "poll: ${e.message}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun saveCursor(epoch: String, cursor: Long) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_EPOCH, epoch).putLong(KEY_CURSOR, cursor).apply()
    }

    private fun post(n: LaptopNote) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return

        // While the session screen is in view, a laptop pop-up is already on
        // screen, but GNOME 42 sends no RDP audio. Play only the tone through a
        // short-lived notification on its own channel, so Do Not Disturb and
        // the user's sound settings still apply. Notes the laptop never showed
        // (Claude Code hook events) and any note while the session screen is
        // in the background get the full notification.
        val inView = Sessions.visible && Sessions.current?.state?.value == SessionState.Connected
        if (inView && n.popup) {
            nm.notify(
                CHIME_ID,
                Notification.Builder(this, CHANNEL_CHIME)
                    .setSmallIcon(R.drawable.ic_stat_desktop)
                    .setContentTitle(n.app.ifBlank { "Laptop" })
                    .setTimeoutAfter(CHIME_TIMEOUT_MS)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build(),
            )
            return
        }

        val title = if (n.title.isBlank()) n.app else n.title
        val public = Notification.Builder(this, CHANNEL_LAPTOP)
            .setSmallIcon(R.drawable.ic_stat_desktop)
            .setContentTitle("Laptop notification")
            .build()
        val note = Notification.Builder(this, CHANNEL_LAPTOP)
            .setSmallIcon(R.drawable.ic_stat_desktop)
            .setContentTitle(title)
            .setContentText(n.body)
            .setStyle(Notification.BigTextStyle().bigText(n.body))
            .setSubText(n.app.ifBlank { null })
            .setWhen(n.timeSec * 1000)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openApp(this))
            // Lock screen shows "Laptop notification" unless the user allows
            // sensitive content in Android's own settings.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setGroup(GROUP)
            .build()
        nm.notify(nextNoteId(), note)
    }

    /** Phone-side id, so notes from a restarted agent never replace unread ones. */
    private fun nextNoteId(): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val n = (prefs.getInt(KEY_NOTE_SEQ, 0) + 1) % NOTE_ID_SPAN
        prefs.edit().putInt(KEY_NOTE_SEQ, n).apply()
        return NOTE_ID_BASE + n
    }

    companion object {
        private const val TAG = "NotifyService"
        private const val CHANNEL_WATCH = "watch"
        private const val CHANNEL_LAPTOP = "laptop"
        private const val CHANNEL_CHIME = "laptop_session"
        private const val GROUP = "laptop"
        private const val ONGOING_ID = 1
        private const val CHIME_ID = 2
        private const val CHIME_TIMEOUT_MS = 4_000L
        private const val NOTE_ID_BASE = 1000
        private const val NOTE_ID_SPAN = 10_000
        private const val KEY_NOTE_SEQ = "note_seq"
        private const val WAIT_SEC = 25
        private const val MIN_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val PREFS = "notify"
        private const val KEY_EPOCH = "epoch"
        private const val KEY_CURSOR = "cursor"
        private const val KEY_TARGET = "target"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
            val intent = Intent(context, NotifyService::class.java)
            if (on) context.startForegroundService(intent) else context.stopService(intent)
        }

        /** Starts the service if the user turned it on; safe to call repeatedly. */
        fun startIfEnabled(context: Context) {
            if (isEnabled(context)) {
                runCatching { context.startForegroundService(Intent(context, NotifyService::class.java)) }
            }
        }

        private fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_WATCH, "Background connection", NotificationManager.IMPORTANCE_MIN)
                    .apply { description = "Shown while the app listens for laptop notifications" },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_LAPTOP, "Laptop notifications", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "Desktop notifications forwarded from the laptop" },
            )
            // Default importance: plays the sound without a heads-up banner.
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_CHIME, "Sound during remote session", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Tone for laptop notifications while you are connected" },
            )
        }

        /**
         * Brings the app's existing task to the front as it is (like the
         * launcher icon), so a running session screen is not duplicated.
         */
        private fun openApp(context: Context): PendingIntent {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?: Intent(context, MainActivity::class.java)
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            return PendingIntent.getActivity(
                context, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}

/** Restarts notification forwarding after a reboot or an app update if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                NotifyService.startIfEnabled(context)
        }
    }
}
