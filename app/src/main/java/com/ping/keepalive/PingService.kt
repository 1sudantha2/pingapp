package com.ping.keepalive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL

class PingService : Service() {

    companion object {
        var isRunning = false
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "PingServiceChannel"
    
    // අතිශය සැහැල්ලු Android Handler එක (Zero CPU සඳහා)
    private var handlerThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var pingRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Ping Keep Alive Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
        }
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PingKeepAlive::WakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Keep Alive is Active")
            .setContentText("Zero-CPU mode is running...")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1, notification)

        if (!isRunning) {
            isRunning = true
            wakeLock?.acquire()
            startOptimizedPingLoop()
        }

        return START_STICKY
    }

    private fun startOptimizedPingLoop() {
        val prefs = getSharedPreferences("PingPrefs", Context.MODE_PRIVATE)
        val targetUrl = prefs.getString("url", "https://oneapp.hutch.lk") ?: "https://oneapp.hutch.lk"
        val delayMillis = prefs.getInt("delay", 15) * 1000L

        // අලුත් සැහැල්ලු Thread එකක් හැදීම
        handlerThread = HandlerThread("UltraLightPingThread").apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        pingRunnable = object : Runnable {
            override fun run() {
                try {
                    val connection = URL(targetUrl).openConnection() as HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = 3000 // Timeout එක 3s වලට අඩු කළා
                    connection.readTimeout = 3000
                    connection.responseCode
                    connection.disconnect()
                } catch (e: Exception) {
                    // ජාල දෝෂ මඟහරියි
                }
                
                // ඊළඟ Ping එක යනකම් CPU එක සම්පූර්ණයෙන්ම නිදි කරවයි
                if (isRunning) {
                    backgroundHandler?.postDelayed(this, delayMillis)
                }
            }
        }
        
        // පළමු Ping එක පටන් ගැනීම
        backgroundHandler?.post(pingRunnable!!)
    }

    override fun onDestroy() {
        isRunning = false
        pingRunnable?.let { backgroundHandler?.removeCallbacks(it) }
        handlerThread?.quitSafely()
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
