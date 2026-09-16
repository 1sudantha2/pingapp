package com.pingbooster.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared, allocation-light application state.
 *
 * Everything the UI needs is kept in memory inside the app process: there are no polling
 * loops, no wakeups while idle and no system broadcasts, so an idle booster costs ~0% CPU
 * and shows the live state instantly when the screen comes back.
 */
object StatusBus {

    /** Number of latency samples kept for the sparkline. */
    const val HISTORY = 40
    const val NO_VALUE = -1

    interface Listener {
        fun onStatusChanged()
    }

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var targetLabel: String = ""
        private set

    @Volatile
    var latencyMs: Int = NO_VALUE
        private set

    @Volatile
    var checks: Long = 0L
        private set

    @Volatile
    var successes: Long = 0L
        private set

    @Volatile
    var runningSince: Long = 0L
        private set

    @Volatile
    var lastError: String = ""
        private set

    /** Ring buffer with the most recent probe latencies; 0f marks a failed probe. */
    val history = FloatArray(HISTORY)

    @Volatile
    var historyHead: Int = 0
        private set

    @Volatile
    var historyCount: Int = 0
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Called by the service when it (re)starts a loop. */
    fun onStarted(label: String, sinceElapsedRealtime: Long) {
        running = true
        targetLabel = label
        runningSince = sinceElapsedRealtime
        latencyMs = NO_VALUE
        lastError = ""
        history.fill(0f)
        historyHead = 0
        historyCount = 0
        publish()
    }

    /** Keeps the label in sync when the target changes while running. */
    fun updateTarget(label: String) {
        targetLabel = label
        publish()
    }

    fun onStopped() {
        running = false
        latencyMs = NO_VALUE
        publish()
    }

    fun recordProbe(latency: Int, ok: Boolean, error: String) {
        checks++
        if (ok) {
            successes++
            latencyMs = latency
        } else {
            latencyMs = NO_VALUE
        }
        lastError = error

        history[historyHead] = if (ok) latency.toFloat() else 0f
        historyHead = (historyHead + 1) % HISTORY
        if (historyCount < HISTORY) historyCount++
        publish()
    }

    private fun publish() {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (listener in listeners) {
                listener.onStatusChanged()
            }
        }
    }

    /** Uptime of the current session in milliseconds (0 when idle). */
    fun uptimeMs(): Long =
        if (running && runningSince > 0L) SystemClock.elapsedRealtime() - runningSince else 0L
}

/**
 * Preferences + start/stop plumbing shared by the activity, the Quick Settings tile
 * and the home screen widget.
 */
object Booster {

    const val PREFS_FILE = "PingPrefs"
    const val KEY_TARGET = "target"
    const val KEY_INTERVAL = "interval"
    const val KEY_RELIABLE = "reliable"
    const val KEY_RUNNING = "running"
    const val KEY_RUNNING_SINCE = "running_since"
    const val KEY_CHECKS = "checks"
    const val KEY_SUCCESSES = "successes"

    const val DEFAULT_TARGET = "https://oneapp.hutch.lk"
    const val DEFAULT_INTERVAL = 15
    const val MIN_INTERVAL = 2
    const val MAX_INTERVAL = 3600

    const val ACTION_START = "com.pingbooster.app.action.START"
    const val ACTION_STOP = "com.pingbooster.app.action.STOP"
    const val ACTION_TOGGLE = "com.pingbooster.app.action.TOGGLE"
    const val ACTION_WIDGET_TOGGLE = "com.pingbooster.app.action.WIDGET_TOGGLE"

    private const val ALARM_REQUEST_CODE = 4711

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    fun isRunning(context: Context): Boolean =
        PingService.isAlive() || prefs(context).getBoolean(KEY_RUNNING, false)

    /** Start request coming from something the user just touched (activity, tile, widget). */
    fun start(context: Context): Boolean = try {
        ContextCompat.startForegroundService(context, serviceIntent(context, ACTION_START))
        true
    } catch (error: Throwable) {
        // Android 12+ can refuse a background foreground-service start. Instead of crashing
        // (or losing the tap) retry through an inexact alarm and report the blocked start.
        scheduleStart(context, serviceIntent(context, ACTION_START))
        false
    }

    /** Stop is always allowed, even when we are in the background. */
    fun stop(context: Context) {
        val app = context.applicationContext
        try {
            app.stopService(serviceIntent(app, ACTION_STOP))
        } catch (_: Throwable) {
            // Ignore: a service that is already gone needs no stopping.
        }
        markStopped(app)
    }

    fun toggle(context: Context): Boolean {
        val app = context.applicationContext
        return if (isRunning(app)) {
            stop(app)
            false
        } else {
            start(app)
            true
        }
    }

    fun markRunning(context: Context, sinceElapsedRealtime: Long) {
        prefs(context).edit()
            .putBoolean(KEY_RUNNING, true)
            .putLong(KEY_RUNNING_SINCE, sinceElapsedRealtime)
            .apply()
    }

    fun markStopped(context: Context) {
        val editor = prefs(context).edit().putBoolean(KEY_RUNNING, false)
        // Counters are session scoped, so a single write on stop is enough.
        if (StatusBus.checks > 0L) {
            editor.putLong(KEY_CHECKS, StatusBus.checks)
                .putLong(KEY_SUCCESSES, StatusBus.successes)
        }
        editor.apply()
    }

    fun saveSettings(context: Context, target: String, intervalSeconds: Int, reliable: Boolean) {
        prefs(context).edit()
            .putString(KEY_TARGET, target)
            .putInt(KEY_INTERVAL, intervalSeconds)
            .putBoolean(KEY_RELIABLE, reliable)
            .apply()
    }

    fun target(context: Context): String =
        prefs(context).getString(KEY_TARGET, DEFAULT_TARGET)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_TARGET

    fun intervalSeconds(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, DEFAULT_INTERVAL)
            .coerceIn(MIN_INTERVAL, MAX_INTERVAL)

    fun reliableMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RELIABLE, true)

    private fun serviceIntent(context: Context, action: String) =
        Intent(context, PingService::class.java).setAction(action)

    private fun scheduleStart(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val alarmManager = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        val pending = android.app.PendingIntent.getForegroundService(
            context,
            ALARM_REQUEST_CODE,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        try {
            // Inexact + allow-while-idle needs no special permission and fires within seconds.
            alarmManager.setAndAllowWhileIdle(
                android.app.AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 1_000L,
                pending
            )
        } catch (_: Throwable) {
            // Nothing else we can do without user interaction; the UI reports the failure.
        }
    }
}
