package com.mapbox.mapboxsdk.plugins.offline.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.mapbox.mapboxsdk.plugins.offline.R
import com.mapbox.mapboxsdk.plugins.offline.model.NotificationOptions
import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions
import com.mapbox.mapboxsdk.plugins.offline.offline.OfflineConstants
import com.mapbox.mapboxsdk.plugins.offline.offline.OfflineDownloadStateReceiver
import com.mapbox.mapboxsdk.plugins.offline.offline.OfflineServiceConfiguration

const val REQUEST_CODE_CANCEL = 2

@RequiresApi(api = Build.VERSION_CODES.O)
fun setupNotificationChannel(
    context: Context,
    config: OfflineServiceConfiguration,
) {
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
        OfflineConstants.NOTIFICATION_CHANNEL,
        config.channelName, NotificationManager.IMPORTANCE_DEFAULT
    )
    config.channelDescription?.let {
        channel.description = it
    }
    config.channelLightColor?.let {
        channel.enableLights(true)
        channel.lightColor = it
    }
    channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    manager.createNotificationChannel(channel)
}

fun toNotificationBuilder(
    context: Context,
    downloadOptions: OfflineDownloadOptions
): NotificationCompat.Builder {
    val applicationContext = context.applicationContext
    val contentIntent = OfflineDownloadStateReceiver.createNotificationIntent(
        applicationContext,
        downloadOptions
    )
    val cancelIntent = OfflineDownloadStateReceiver.createCancelIntent(
        applicationContext, downloadOptions
    )
    val notificationOptions = downloadOptions.notificationOptions

    return NotificationCompat.Builder(context, OfflineConstants.NOTIFICATION_CHANNEL)
        .setContentTitle(notificationOptions.contentTitle)
        .setContentText(notificationOptions.contentText)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setSmallIcon(notificationOptions.smallIconRes)
        .setOnlyAlertOnce(true)
        .setContentIntent(contentIntent)
        .setOngoing(true)
        .addAction(
            R.drawable.ic_cancel,
            notificationOptions.cancelText,
            PendingIntent.getService(
                context, REQUEST_CODE_CANCEL, cancelIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
}
