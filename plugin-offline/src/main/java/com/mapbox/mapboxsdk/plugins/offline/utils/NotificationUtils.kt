package com.mapbox.mapboxsdk.plugins.offline.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pManager.ChannelListener
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.plugins.offline.R
import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions
import com.mapbox.mapboxsdk.plugins.offline.offline.OfflineConstants
import com.mapbox.mapboxsdk.plugins.offline.offline.OfflineServiceConfiguration

@RequiresApi(api = Build.VERSION_CODES.O)
fun setupNotificationChannel(
    config: OfflineServiceConfiguration?,
) {
    val manager = Mapbox.getApplicationContext()
        .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(
        OfflineConstants.NOTIFICATION_CHANNEL,
        config?.channelName ?: "Offline", NotificationManager.IMPORTANCE_DEFAULT
    )
    config?.channelLightColor?.let {
        channel.enableLights(true)
        channel.lightColor = it
    }
    channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
    manager.createNotificationChannel(channel)
}

fun toNotificationBuilder(
    context: Context?,
    offlineDownloadOptions: OfflineDownloadOptions,
    contentIntent: PendingIntent?,
    cancelIntent: Intent?
): NotificationCompat.Builder {
    val notificationOptions = offlineDownloadOptions.notificationOptions
    return NotificationCompat.Builder(context!!, OfflineConstants.NOTIFICATION_CHANNEL)
        .setContentTitle(notificationOptions.contentTitle)
        .setContentText(notificationOptions.contentText)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setSmallIcon(notificationOptions.smallIconRes)
        .setOnlyAlertOnce(true)
        // Remember: Setting a group here does not have any effect without the .setGroupSummary(true) in the summary notification below
        .setGroup(OfflineConstants.NOTIFICATION_GROUP)
        .setContentIntent(contentIntent)
        .addAction(
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) 0 else R.drawable.ic_cancel,
            notificationOptions.cancelText,
            PendingIntent.getService(
                context, offlineDownloadOptions.uuid.toInt(), cancelIntent!!,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
}

fun makeSummaryNotification(
    context: Context?,
    offlineDownload: OfflineDownloadOptions
): Notification {
    return NotificationCompat.Builder(context!!, OfflineConstants.NOTIFICATION_CHANNEL)
        .setContentTitle(offlineDownload.notificationOptions.contentTitle)
        // Set content text to support devices running API level < 24.
        .setContentText(offlineDownload.notificationOptions.contentText)
        .setSmallIcon(offlineDownload.notificationOptions.smallIconRes)
        // Build summary info into InboxStyle template.
        .setStyle(
            NotificationCompat.InboxStyle()
        )
        // Specify which group this notification belongs to.
        .setGroup(OfflineConstants.NOTIFICATION_GROUP)
        // Set this notification as the summary for the group.
        .setGroupSummary(true)
        .build()
}
