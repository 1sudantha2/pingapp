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
import android.os.Process
import androidx.core.app.NotificationCompat
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class PingService : Service() {

    companion object {
        var isRunning = false
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "PingServiceChannel"
    
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
            .setContentText("Ultra-Light Mode (Zero RAM Leak)")
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
        val targetUrlStr = prefs.getString("url", "https://oneapp.hutch.lk") ?: "https://oneapp.hutch.lk"
        val delayMillis = prefs.getInt("delay", 15) * 1000L

        // අලුත් URL Object එකක් හැමවෙලේම හදන්නේ නැතිව, එක පාරක් හදාගැනීම (RAM ඉතිරි කරයි)
        val targetUrl = URL(targetUrlStr)

        handlerThread = HandlerThread("UltraLightPingThread", Process.THREAD_PRIORITY_LOWEST).apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        pingRunnable = object : Runnable {
            override fun run() {
                var connection: HttpsURLConnection? = null
                try {
                    connection = targetUrl.openConnection() as HttpsURLConnection
                    connection.requestMethod = "HEAD"
                    connection.setRequestProperty("Connection", "close")
                    connection.setRequestProperty("User-Agent", "KeepAlive-Android/2.0")
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    
                    // Request එක යැවීම
                    connection.responseCode
                    
                    // (ඉතා වැදගත්) Memory Leak එක නැවැත්වීම සඳහා Streams වසා දැමීම
                    connection.inputStream?.close()
                    connection.errorStream?.close()
                    
                } catch (e: Exception) {
                    // Ignore errors to keep running
                } finally {
                    connection?.disconnect()
                }
                
                if (isRunning) {
                    backgroundHandler?.postDelayed(this, delayMillis)
                }
            }
        }
        
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
