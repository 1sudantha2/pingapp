package com.ping.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL

class PingService : Service() {

    companion object {
        var isRunning = false
    }

    private var pingThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "PingServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Setup Partial WakeLock to ensure 0 CPU drop during screen off
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingKeepAlive::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Keep Alive is Active")
            .setContentText("Ping is running in the background.")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        if (!isRunning) {
            isRunning = true
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes timeout fallback
            startPingLoop()
        }

        return START_STICKY // Auto restart if system kills it
    }

    private fun startPingLoop() {
        val prefs = getSharedPreferences("PingPrefs", Context.MODE_PRIVATE)
        val targetUrl = prefs.getString("url", "https://oneapp.hutch.lk") ?: "https://oneapp.hutch.lk"
        val delaySec = prefs.getInt("delay", 15)
        val delayMillis = delaySec * 1000L

        pingThread = Thread {
            while (isRunning) {
                try {
                    // Ultra-low payload HEAD request (Zero CPU load)
                    val url = URL(targetUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.setRequestProperty("Connection", "Keep-Alive")
                    connection.setRequestProperty("Cache-Control", "no-cache")
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    
                    val responseCode = connection.responseCode
                    connection.disconnect()
                } catch (e: Exception) {
                    // Ignore network errors (drop connections) to keep the loop alive
                }

                try {
                    Thread.sleep(delayMillis)
                } catch (e: InterruptedException) {
                    // Thread interrupted upon stop
                }
            }
        }
        pingThread?.start()
    }

    override fun onDestroy() {
        isRunning = false
        pingThread?.interrupt()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ping Keep Alive Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
