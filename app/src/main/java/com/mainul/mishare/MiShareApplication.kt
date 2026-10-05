package com.mainul.mishare

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class MiShareApplication : Application() {

    companion object {
        const val CHANNEL_ID = "mishare_server_channel"
        lateinit var instance: MiShareApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.channel_name)
            val descriptionText = getString(R.string.channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
