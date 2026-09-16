package com.pingbooster.app

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import com.pingbooster.app.databinding.ActivityMainBinding

/**
 * The control screen.
 *
 * The UI is deliberately passive: it renders the shared in-memory state and re-renders only
 * when something actually changes (a new probe result, a start or a stop). Nothing polls, so
 * a visible window costs next to no CPU and the animations stay perfectly smooth.
 */
class MainActivity : AppCompatActivity(), StatusBus.Listener {

    private lateinit var binding: ActivityMainBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    private val uptimeTicker = object : Runnable {
        override fun run() {
            renderUptime()
            scheduleUptimeTick()
        }
    }

    private val testExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ping-test").apply { priority = Thread.MIN_PRIORITY }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                showMessage(getString(R.string.permission_notification_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        loadSettings()
        wireActions()
        requestNotificationPermissionIfNeeded()
        binding.footerText.text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)
    }

    override fun onStart() {
        super.onStart()
        StatusBus.addListener(this)
        renderAll()
    }

    override fun onResume() {
        super.onResume()
        scheduleUptimeTick()
        renderAll()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(uptimeTicker)
    }

    override fun onStop() {
        super.onStop()
        StatusBus.removeListener(this)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(uptimeTicker)
        testExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onStatusChanged() {
        renderAll()
    }

    // ------------------------------------------------------------------ setup

    /** Edge-to-edge: the background still fills the window, the content stays inside the bars. */
    private fun applyWindowInsets() {
        val base = Insets(
            binding.root.paddingLeft,
            binding.root.paddingTop,
            binding.root.paddingRight,
            binding.root.paddingBottom
        )
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = base.left + bars.left,
                top = base.top + bars.top,
                right = base.right + bars.right,
                bottom = base.bottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun loadSettings() {
        binding.inputTarget.setText(Booster.target(this))
        binding.inputInterval.setText(Booster.intervalSeconds(this).toString())
        binding.switchReliable.isChecked = Booster.reliableMode(this)
    }

    private fun wireActions() {
        binding.btnStart.setOnClickListener { startBoost() }
        binding.btnStop.setOnClickListener { stopBoost() }
        binding.btnTest.setOnClickListener { runQuickTest() }

        binding.switchReliable.setOnCheckedChangeListener { _, checked ->
            saveSettings()
            renderStatus()
            if (Booster.isRunning(this@MainActivity)) {
                showMessage(getString(if (checked) R.string.toast_reliable_on else R.string.toast_reliable_off))
            }
        }

        binding.btnQuickSettings.setOnClickListener { addQuickSettingsTile() }
        binding.btnBattery.setOnClickListener { openBatterySettings() }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun startBoost() {
        val target = binding.inputTarget.text?.toString()?.trim().orEmpty()
        val intervalText = binding.inputInterval.text?.toString()?.trim().orEmpty()

        if (PingEngine.parse(target) == null) {
            binding.inputTargetLayout.error = getString(R.string.error_target)
            binding.inputTarget.requestFocus()
            return
        }
        binding.inputTargetLayout.error = null

        val interval = intervalText.toIntOrNull()
        if (interval == null || interval < Booster.MIN_INTERVAL || interval > Booster.MAX_INTERVAL) {
            binding.inputIntervalLayout.error =
                getString(R.string.error_interval, Booster.MIN_INTERVAL, Booster.MAX_INTERVAL)
            binding.inputInterval.requestFocus()
            return
        }
        binding.inputIntervalLayout.error = null

        saveSettings()
        if (!Booster.start(this)) {
            showMessage(getString(R.string.error_start_blocked))
        }
        renderAll()
    }

    private fun stopBoost() {
        Booster.stop(this)
        renderAll()
    }

    private fun saveSettings() {
        val target = binding.inputTarget.text?.toString()?.trim().orEmpty()
        val interval = binding.inputInterval.text?.toString()?.trim()?.toIntOrNull()
            ?: Booster.DEFAULT_INTERVAL
        Booster.saveSettings(
            this,
            target.ifEmpty { Booster.DEFAULT_TARGET },
            interval.coerceIn(Booster.MIN_INTERVAL, Booster.MAX_INTERVAL),
            binding.switchReliable.isChecked
        )
    }

    private fun runQuickTest() {
        val parsed = PingEngine.parse(binding.inputTarget.text?.toString()?.trim().orEmpty())
        if (parsed == null) {
            binding.inputTargetLayout.error = getString(R.string.error_target)
            return
        }
        binding.inputTargetLayout.error = null
        binding.btnTest.isEnabled = false
        binding.statLatency.text = getString(R.string.value_pending)

        try {
            testExecutor.execute {
                val engine = PingEngine()
                val result = engine.probe(parsed)
                mainHandler.post {
                    if (isFinishing || isDestroyed) return@post
                    binding.btnTest.isEnabled = true
                    if (result.ok) {
                        binding.statLatency.text = getString(R.string.value_ms, result.latencyMs)
                    } else {
                        binding.statLatency.text = getString(R.string.value_failed)
                    }
                }
            }
        } catch (_: Throwable) {
            binding.btnTest.isEnabled = true
        }
    }

    private fun addQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val manager = getSystemService(StatusBarManager::class.java)
            if (manager != null) {
                val component = ComponentName(this, PingTileService::class.java)
                val icon = Icon.createWithResource(this, R.drawable.ic_tile_booster)
                try {
                    manager.requestAddTileService(component, getString(R.string.tile_label), icon, mainExecutor) { result ->
                        if (result != StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED) {
                            showMessage(getString(R.string.tile_add_manual))
                        }
                    }
                    return
                } catch (_: Throwable) {
                    // Fall through to the manual instructions below.
                }
            }
        }
        showMessage(getString(R.string.tile_add_manual))
    }

    private fun openBatterySettings() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) {
            showMessage(getString(R.string.battery_already_ignored))
            return
        }
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        try {
            startActivity(request)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Throwable) {
                showMessage(getString(R.string.error_settings_unavailable))
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun renderAll() {
        renderStatus()
        renderUptime()
        binding.sparkline.invalidate()
    }

    private fun renderStatus() {
        val running = Booster.isRunning(this)
        val accent = ContextCompat.getColor(this, R.color.accent)
        val idle = ContextCompat.getColor(this, R.color.text_secondary)
        val danger = ContextCompat.getColor(this, R.color.danger)

        binding.statusText.text =
            getString(if (running) R.string.status_running else R.string.status_stopped)
        binding.statusText.setTextColor(if (running) accent else danger)
        binding.statusDot.setBackgroundResource(
            if (running) R.drawable.dot_online else R.drawable.dot_offline
        )

        val failures = StatusBus.checks - StatusBus.successes
        binding.statusDetail.text = when {
            !running -> getString(R.string.status_detail_idle)
            StatusBus.checks == 0L -> getString(R.string.tile_subtitle_waiting)
            failures > 0L -> getString(
                R.string.status_detail_running_errors,
                StatusBus.targetLabel,
                StatusBus.lastError.ifEmpty { getString(R.string.value_failed) }
            )
            else -> getString(R.string.status_detail_running, StatusBus.targetLabel)
        }
        binding.statusDetail.setTextColor(
            if (running && failures > 0L) danger
            else if (running) accent
            else idle
        )

        binding.statLatency.text = if (StatusBus.latencyMs > 0) {
            getString(R.string.value_ms, StatusBus.latencyMs)
        } else {
            getString(R.string.value_dash)
        }
        binding.statSuccess.text = if (StatusBus.checks > 0L) {
            "${StatusBus.successes * 100 / StatusBus.checks}%"
        } else {
            getString(R.string.value_dash)
        }
        binding.statChecks.text = if (StatusBus.checks > 0L) StatusBus.checks.toString() else getString(R.string.value_dash)

        binding.btnStart.isEnabled = !running
        binding.btnStop.isEnabled = running
        binding.btnStart.alpha = if (running) 0.5f else 1f
        binding.btnStop.alpha = if (running) 1f else 0.5f

        if (StatusBus.checks > 0L) {
            // Keep the graph in sync even when only the stats changed.
            binding.sparkline.invalidate()
        }
    }

    private fun renderUptime() {
        binding.statUptime.text = if (Booster.isRunning(this)) {
            formatDuration(StatusBus.uptimeMs())
        } else {
            getString(R.string.value_dash)
        }
    }

    private fun scheduleUptimeTick() {
        mainHandler.removeCallbacks(uptimeTicker)
        if (Booster.isRunning(this)) {
            mainHandler.postDelayed(uptimeTicker, UPTIME_TICK_MS)
        }
    }

    private fun formatDuration(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0L -> getString(R.string.duration_hours, hours, minutes)
            minutes > 0L -> getString(R.string.duration_minutes, minutes, seconds)
            else -> getString(R.string.duration_seconds, seconds)
        }
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private companion object {
        const val UPTIME_TICK_MS = 5_000L
    }
}
