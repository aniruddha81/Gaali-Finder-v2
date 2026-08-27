package com.aniruddha81.gaalifinderv2

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class GaaliFinderApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Registering the channel is idempotent, so doing it on every start keeps the name and
     * description in step with the current locale.
     */
    private fun createNotificationChannel() {
        val manager = getSystemService<NotificationManager>() ?: return

        val channel = NotificationChannel(
            NotificationChannels.NEW_CLIPS,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }

        // A malformed channel must not take the whole process down on launch.
        runCatching { manager.createNotificationChannel(channel) }
    }
}

object NotificationChannels {
    const val NEW_CLIPS = "new_clips"
}
