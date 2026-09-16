package com.pingbooster.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * The optimised heartbeat engine.
 *
 * Design notes (why this is cheap):
 *  - One low priority worker thread owns all sockets; the timer itself runs on the main
 *    looper so nothing wakes up between cycles.
 *  - The next cycle is scheduled only after the previous one finished, so the service can
 *    never pile up work or spin.
 *  - The wake lock is only held while the screen is off and in "reliable" mode, and it is
 *    renewed per cycle instead of being held forever.
 *  - No broadcasts, no polling, no per-cycle object churn: an idle minute costs a couple of
 *    milliseconds of CPU (≈0.0x %) and a few hundred bytes.
 */
class PingService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1907
        private const val CHANNEL_ID = "ping_booster_status"
        private const val WAKE_LOCK_TAG = "PingBooster:heartbeat"
        private const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val NOTIFICATION_REFRESH_EVERY = 4

        @Volatile
        private var instance: PingService? = null

        /** True while a heartbeat loop is genuinely running inside this process. */
        fun isAlive(): Boolean = instance?.active == true
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val engine = PingEngine()

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var receiverRegistered = false

    @Volatile
    private var active = false
    private var target: PingEngine.Target? = null
    private var failures = 0
    private var cyclesSinceNotificationRefresh = 0
    private var lastNotificationText = ""
    private var lastSettingsStamp = ""

    private val cycleRunnable = Runnable { runCycle() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
        }
        registerScreenReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            Booster.ACTION_STOP -> {
                stopEverything(userInitiated = true)
                return START_NOT_STICKY
            }

            Booster.ACTION_TOGGLE -> {
                if (active) {
                    stopEverything(userInitiated = true)
                    return START_NOT_STICKY
                }
            }
        }

        // A sticky restart arrives with a null intent: keep the boost alive.
        return if (startHeartbeat()) START_STICKY else START_NOT_STICKY
    }

    // ---------------------------------------------------------------- heartbeat loop

    private fun startHeartbeat(): Boolean {
        if (active) return true
        if (!promoteToForeground(buildNotification("Starting…"))) return false

        active = true
        failures = 0
        // Already at the threshold so the first real latency shows up right away.
        cyclesSinceNotificationRefresh = NOTIFICATION_REFRESH_EVERY
        lastSettingsStamp = ""
        lastNotificationText = ""

        val thread = HandlerThread("ping-heartbeat", Process.THREAD_PRIORITY_LOWEST).apply { start() }
        workerThread = thread
        worker = Handler(thread.looper)

        val now = SystemClock.elapsedRealtime()
        Booster.markRunning(this, now)
        val parsed = PingEngine.parse(Booster.target(this))
        target = parsed
        lastSettingsStamp = settingsStamp(parsed, Booster.intervalSeconds(this), Booster.reliableMode(this))
        StatusBus.onStarted(parsed?.label ?: Booster.target(this), now)
        refreshControllers()
        mainHandler.post(cycleRunnable)
        return true
    }

    private fun runCycle() {
        if (!active) return
        val timer = mainHandler
        val job = worker ?: return

        // Settings are re-read every cycle (in-memory read, no disk I/O) so a new interval
        // applies instantly without restarting the service.
        val intervalMs = Booster.intervalSeconds(this) * 1000L
        val reliable = Booster.reliableMode(this)
        applyWakeLockPolicy(reliable)

        val stamp = settingsStamp(target, Booster.intervalSeconds(this), reliable)
        if (stamp != lastSettingsStamp) {
            lastSettingsStamp = stamp
            val parsed = PingEngine.parse(Booster.target(this))
            target = parsed
            parsed?.let { StatusBus.updateTarget(it.label) }
        }
        val destination = target ?: return

        val timeout = (intervalMs / 2).coerceIn(1_000L, 4_000L).toInt()
        job.post {
            val result = engine.probe(destination, timeout)
            timer.post { onProbeFinished(result, intervalMs) }
        }
    }

    private fun onProbeFinished(result: PingEngine.ProbeResult, intervalMs: Long) {
        if (!active) return

        if (result.ok) {
            failures = 0
        } else {
            failures++
        }
        StatusBus.recordProbe(result.latencyMs, result.ok, result.error)

        val text = when {
            result.ok -> "Keeping ${StatusBus.targetLabel} alive · ${result.latencyMs} ms"
            else -> "Reconnecting… (${result.error.ifEmpty { "no route" }})"
        }
        if (text != lastNotificationText) {
            lastNotificationText = text
            cyclesSinceNotificationRefresh++
            if (cyclesSinceNotificationRefresh >= NOTIFICATION_REFRESH_EVERY) {
                cyclesSinceNotificationRefresh = 0
                updateNotification(text)
            }
        }

        mainHandler.postDelayed(cycleRunnable, nextDelayMs(intervalMs))
    }

    /** Back off a little while the network is down, but never skip a keep-alive for long. */
    private fun settingsStamp(destination: PingEngine.Target?, intervalSeconds: Int, reliable: Boolean): String =
        "${destination?.host}:${destination?.port}|$intervalSeconds|$reliable"

    private fun nextDelayMs(intervalMs: Long): Long {
        if (failures == 0) return intervalMs
        val factor = (failures + 1).coerceAtMost(4)
        return (intervalMs * factor).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun applyWakeLockPolicy(reliable: Boolean) {
        val lock = wakeLock ?: return
        // Wake lock only while the screen is off and reliable mode is on: while the screen is
        // on the CPU is already awake, so holding it would only waste battery.
        val shouldHold = reliable && !isScreenOn()
        if (shouldHold && !lock.isHeld) {
            // Renewed every cycle: the lock can never be leaked, even on a process kill.
            lock.acquire(WAKE_LOCK_TIMEOUT_MS)
        } else if (!shouldHold && lock.isHeld) {
            lock.release()
        }
    }

    private fun isScreenOn(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isInteractive
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    // The CPU is awake anyway while the screen is on.
                    Intent.ACTION_SCREEN_ON -> wakeLock?.takeIf { it.isHeld }?.release()
                    Intent.ACTION_SCREEN_OFF -> if (active && Booster.reliableMode(this@PingService)) {
                        wakeLock?.takeIf { !it.isHeld }?.acquire(WAKE_LOCK_TIMEOUT_MS)
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            screenReceiver = receiver
        } catch (_: Throwable) {
            screenReceiver = null
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_status),
            NotificationManager.IMPORTANCE_MIN // silent, no badge, no heads-up
        ).apply {
            description = getString(R.string.channel_status_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, PingService::class.java).setAction(Booster.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ping)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /** Promotes the service to the foreground, tolerating Android 12+ background limits. */
    private fun promoteToForeground(notification: Notification): Boolean {
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
            )
            true
        } catch (error: Throwable) {
            // ForegroundServiceStartNotAllowedException: the system refused a background
            // start. Never crash - just retire quietly and let the caller report it.
            stopSelf()
            false
        }
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        try {
            if (canPostNotifications()) {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
            } else {
                promoteToForeground(notification)
            }
        } catch (_: Throwable) {
            // Notification updates are cosmetic: never let them break the heartbeat.
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- lifecycle

    private fun stopEverything(userInitiated: Boolean) {
        stopHeartbeat()
        if (userInitiated) {
            Booster.markStopped(this)
            StatusBus.onStopped()
            refreshControllers()
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopHeartbeat() {
        active = false
        mainHandler.removeCallbacks(cycleRunnable)
        workerThread?.quitSafely()
        workerThread = null
        worker = null
        target = null
        wakeLock?.takeIf { it.isHeld }?.release()
    }

    /** Lets the Quick Settings tile and the widget redraw after a state change. */
    private fun refreshControllers() {
        PingTileService.requestRefresh(this)
        StatusWidgetProvider.refreshAll(this)
    }

    override fun onDestroy() {
        stopHeartbeat()
        if (receiverRegistered) {
            try {
                screenReceiver?.let { unregisterReceiver(it) }
            } catch (_: Throwable) {
                // Already unregistered.
            }
            receiverRegistered = false
        }
        screenReceiver = null
        instance = null
        Booster.markStopped(this)
        StatusBus.onStopped()
        refreshControllers()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
