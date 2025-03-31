package com.aniruddha81.gaalifinderv2

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApp: Application(){
    override fun onCreate() {
        super.onCreate()

        val name = getString(R.string.notification_title)
        val channelDescription = getString(R.string.notification_description)

        val channel = NotificationChannel(
            Constants.CHANNEL_NAME,
            name,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = channelDescription
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}