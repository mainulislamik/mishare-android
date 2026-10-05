package com.mainul.mishare.server

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mainul.mishare.MainActivity
import com.mainul.mishare.MiShareApplication
import com.mainul.mishare.R
import com.mainul.mishare.model.SharedFile
import com.mainul.mishare.utils.NetworkUtils

class FileServerService : Service() {

    companion object {
        const val ACTION_START = "com.mainul.mishare.ACTION_START"
        const val ACTION_STOP = "com.mainul.mishare.ACTION_STOP"
        const val EXTRA_PORT = "com.mainul.mishare.EXTRA_PORT"

        const val NOTIFICATION_ID = 1001

        var isRunning: Boolean = false
            private set
        var currentServer: HttpFileServer? = null
            private set
        var currentPort: Int = 8888
            private set

        var onStateChangeListener: ((Boolean) -> Unit)? = null
        var onFileUploadedListener: ((SharedFile) -> Unit)? = null
    }

    private var server: HttpFileServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopServer()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_START) {
            val port = intent.getIntExtra(EXTRA_PORT, 8888)
            startServer(port)
        }

        return START_STICKY
    }

    private fun startServer(port: Int) {
        if (isRunning) return

        try {
            acquireLocks()

            server = HttpFileServer(this, port) { uploadedFile ->
                onFileUploadedListener?.invoke(uploadedFile)
            }
            server?.start()

            currentServer = server
            currentPort = port
            isRunning = true
            onStateChangeListener?.invoke(true)

            val notification = buildNotification(port)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

        } catch (e: Exception) {
            e.printStackTrace()
            stopServer()
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
            server = null
            currentServer = null
            isRunning = false
            onStateChangeListener?.invoke(false)
            releaseLocks()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildNotification(port: Int): Notification {
        val ip = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
        val url = "http://$ip:$port"

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, MiShareApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Access at $url")
            .setSmallIcon(R.drawable.ic_wifi)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_delete, getString(R.string.notification_stop), stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MiShare::ServerWakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "MiShare::ServerWifiLock"
            ).apply {
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
            if (wifiLock?.isHeld == true) wifiLock?.release()
            wifiLock = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }
}
