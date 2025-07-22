package com.example.testing1

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class Testing1App : Application() {
    override fun onCreate() {
        super.onCreate()
        // The if statement is removed because the app's minSdk (29) is higher than
        // the required level for NotificationChannels (26), so this code will always run.
        val channel = NotificationChannel(
            "bt_service_channel",
            "Bluetooth Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
