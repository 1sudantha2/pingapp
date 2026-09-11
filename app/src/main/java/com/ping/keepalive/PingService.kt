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
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

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
            .setContentText("Ultra-Light Mode (Termux Raw Socket)")
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

        // අඩුම CPU ප්‍රමුඛතාවය (0.1% CPU සඳහා)
        handlerThread = HandlerThread("TermuxStylePingThread", Process.THREAD_PRIORITY_LOWEST).apply { start() }
        backgroundHandler = Handler(handlerThread!!.looper)

        pingRunnable = object : Runnable {
            override fun run() {
                var socket: SSLSocket? = null
                var outStream: OutputStream? = null
                var inStream: InputStream? = null
                
                try {
                    val urlObj = URL(targetUrlStr)
                    val host = urlObj.host
                    val path = if (urlObj.path.isEmpty()) "/" else urlObj.path

                    // Termux (C/C++) Technique: කෙලින්ම Raw SSL Socket එකක් සෑදීම
                    val factory = SSLSocketFactory.getDefault()
                    socket = factory.createSocket(host, 443) as SSLSocket
                    socket.soTimeout = 5000
                    socket.startHandshake()

                    // Raw HTTP Request එක කෙලින්ම Bytes විදිහට යැවීම (Android Objects නැත)
                    val request = "HEAD $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\nUser-Agent: Termux/1.0\r\n\r\n"
                    outStream = socket.outputStream
                    outStream.write(request.toByteArray())
                    outStream.flush()
                    
                    // Memory පිරෙන්නේ නැති වෙන්න Response එකේ 1 Byte එකක් පමණක් කියවීම
                    inStream = socket.inputStream
                    inStream.read()
                    
                } catch (e: Exception) {
                    // Ignore Network errors (Network කපන වෙලාවට App එක Crash නොවීම සඳහා)
                } finally {
                    // Memory Leak වීම 100% ක් වැළැක්වීම සඳහා අනිවාර්යයෙන්ම Streams Close කිරීම
                    try { inStream?.close() } catch (e: Exception) {}
                    try { outStream?.close() } catch (e: Exception) {}
                    try { socket?.close() } catch (e: Exception) {}
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
