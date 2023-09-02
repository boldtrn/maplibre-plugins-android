package com.mapbox.mapboxsdk.plugins.offline.offline

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.collection.LongSparseArray
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.mapbox.mapboxsdk.Mapbox
import com.mapbox.mapboxsdk.offline.OfflineManager
import com.mapbox.mapboxsdk.offline.OfflineRegion
import com.mapbox.mapboxsdk.offline.OfflineRegionError
import com.mapbox.mapboxsdk.offline.OfflineRegionStatus
import com.mapbox.mapboxsdk.plugins.offline.model.NotificationOptions
import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions
import com.mapbox.mapboxsdk.plugins.offline.utils.NOTIFICATION_FOREGROUND_ID
import com.mapbox.mapboxsdk.plugins.offline.utils.toNotificationBuilder
import timber.log.Timber

/**
 * Package for all options that can be configured on an [OfflineDownloadService]. No field is
 * mandatory and the service will choose sensible fallbacks where necessary. For instance: Channel
 * name must have a fallback, otherwise we can't create a channel. This fallback is not localized,
 * so implementors of this plugin are highly encouraged to add a proper channel name themselves
 */
data class OfflineServiceConfiguration(
    val channelName: String = "Offline",
    val channelDescription: String? = null,
    val channelLightColor: Int? = null,
    val retryString: String? = null
)

/**
 * Internal usage only, use this service indirectly by using methods found in [OfflinePlugin]. When
 * an offline download is initiated for the first time using this plugin, this service is created,
 * captures the `StartCommand` and collects the [OfflineDownloadOptions] instance which holds all
 * the download metadata needed to perform the download.
 *
 * If another offline download is initiated through the [OfflinePlugin] while another download is
 * currently in process, this service will add it to the [OfflineManager] queue for downloading,
 * downstream, this will execute the region downloads asynchronously (although writing to the same
 * file). Once all downloads have been completed, this service is stopped and destroyed.
 *
 * @since 0.1.0
 */
class OfflineDownloadService : Service() {
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var builder: NotificationCompat.Builder

    // map offline regions to requests, ids are received with onStartCommand, the keys match serviceId
    // in OfflineDownloadOptions
    private val requestedRegions = LongSparseArray<OfflineRegion>()

    override fun onCreate() {
        super.onCreate()
        Timber.v("onCreate called with config = {%s}", OfflinePlugin.config)
        // Setup notification manager for later, channels are already setup at this point
        notificationManager = NotificationManagerCompat.from(this)
        Timber.d("onCreate finished")
    }

    /**
     * Called each time a new download is initiated. First it acquires the [OfflineDownloadOptions] from the
     * OfflinePlugin's pendingIntents and if found, the process of downloading the offline region carries on to the
     * [.onResolveCommand]. If the [OfflineDownloadOptions] fails to be
     * found inside the intent, the service is stopped (only if no other downloads are currently running) and throws a
     * [IllegalArgumentException].
     *
     * {@inheritDoc}
     *
     * @since 0.1.0
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("onStartCommand called with intent = {%s}, startId = %s", intent, startId)
        // Android does not mark this as non-null yet, but we require non-null intent to start work
        requireNotNull(intent)
        val intentAction = intent.action
        if (OfflineConstants.ACTION_CANCEL_DOWNLOAD == intentAction) {
            // Cancelling is currently not a foreground action, start right away
            val uuid = intent.getLongExtra(OfflineConstants.KEY_BUNDLE_OFFLINE_REGION_ID, -1L)
            if (uuid == -1L) {
                cancelAllDownloads()
            } else {
                cancelDownload(uuid)
            }
            return START_REDELIVER_INTENT
        }

        if (OfflineConstants.ACTION_START_DOWNLOAD != intentAction) {
            throw IllegalArgumentException("Invalid intent action $intentAction")
        }

        val downloadOptions = OfflinePlugin.getInstance(this).pendingDownloads
        if (downloadOptions.isNullOrEmpty()) {
            Timber.w("Downloads were not set before starting the service, undefined state, stopping")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        Timber.d("service start with {%s}", downloadOptions)
        // Fail fast if the list is empty
        updateNotificationBuilder(downloadOptions[0])

        /*
        This service is started as a foreground service, we need to call this method IMMEDIATELY.
        Never put any hard work before this line of code and never delay calling it. The Android
        system will crash the app after only a few seconds if this is not called
         */
        startForeground(NOTIFICATION_FOREGROUND_ID, builder.build())
        createDownloads(downloadOptions)
        return START_REDELIVER_INTENT
    }

    private fun createDownloads(downloadOptions: List<OfflineDownloadOptions>) {
        // Careful, the instance from outside the service may have died in the meantime. We need to
        // have an instance for downloads. If app instance is still alive, this will still work
        Mapbox.getInstance(this)
        for (option in downloadOptions) {
            createDownload(option)
        }
    }

    private var lastKnownNotificationOptions: NotificationOptions? = null

    private fun updateNotificationBuilder(downloadOptions: OfflineDownloadOptions): NotificationCompat.Builder {
        if (lastKnownNotificationOptions != downloadOptions.notificationOptions) {
            lastKnownNotificationOptions = downloadOptions.notificationOptions
            builder = toNotificationBuilder(
                context = this,
                downloadOptions = downloadOptions
            )
        }
        return builder
    }

    private fun createDownload(offlineDownload: OfflineDownloadOptions) {
        Timber.v("createDownload() called with: offlineDownload = %s", offlineDownload)
        OfflineManager.getInstance(applicationContext).createOfflineRegion(
            offlineDownload.definition,
            offlineDownload.metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    Timber.v("onCreate with: offlineRegion = %s", offlineRegion)
                    OfflinePlugin.getInstance(this@OfflineDownloadService)
                        .addDownload(offlineDownload)
                    requestedRegions.put(offlineDownload.uuid, offlineRegion)
                    launchDownload(offlineDownload, offlineRegion)
                }

                override fun onError(error: String) {
                    Timber.w("onError with error = %s", error)
                    OfflinePlugin.getInstance(this@OfflineDownloadService)
                        .errorDownload(offlineDownload, error)
                }
            })
    }

    private fun cancelAllDownloads() {
        while (OfflinePlugin.getInstance(this).getActiveDownloads().isNotEmpty()) {
            cancelDownload(OfflinePlugin.getInstance(this).getActiveDownloads().last().uuid)
        }
    }

    private fun cancelDownload(uuid: Long) {
        Timber.v("Requesting cancellation of download for Offlineregion with uuid = $uuid")
        val downloadOptions =
            OfflinePlugin.getInstance(this).getActiveDownloads().filter { it.uuid == uuid }
                .firstOrNull()
        val offlineRegion = requestedRegions[uuid]
        if (downloadOptions == null || offlineRegion == null) {
            Timber.w("Could not find download with uuid $uuid")
            return
        }
        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
        offlineRegion.setObserver(null)
        offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                Timber.v("Offline region with UUID {%s} deleted ", uuid)
            }

            override fun onError(error: String) {
                Timber.w("Offline region with UUID {%s} deletion error: %s", uuid, error)
                OfflinePlugin.getInstance(this@OfflineDownloadService).errorDownload(
                    downloadOptions,
                    error
                )
            }
        })
        OfflinePlugin.getInstance(this).removeDownload(downloadOptions, true)
        removeOfflineRegion(uuid)
        Timber.v("Cancelled download for Offlineregion with uuid = $uuid")
    }

    private fun removeOfflineRegion(uuid: Long) {
        requestedRegions.remove(uuid)
        maybeStopService(uuid)
    }

    private fun maybeStopService(uuid: Long) {
        var stopped = false
        if (requestedRegions.size() == 0) {
            // The current "batch / phase" off downloads is done, inform the user
            stopForegroundCompat()
            // Stop the service immediately, ordering of the downloads does not matter, they're done
            stopped = stopSelfResult(-1)
        }
        Timber.i("removeOfflineRegion with uuid = {%s}, stopped = %s", uuid, stopped)
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    private fun launchDownload(
        offlineDownload: OfflineDownloadOptions,
        offlineRegion: OfflineRegion
    ) {
        Timber.v(
            "launchDownload with: offlineDownload = %s, offlineRegion = %s",
            offlineDownload,
            offlineRegion
        )
        // Send a one-shot fake progress update to initialise the progress bar in the notification in case this was the first download
        updateNotificationProgress(offlineDownload)
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    finishDownload(offlineDownload, offlineRegion)
                } else {
                    updateProgress(offlineDownload, status)
                }
            }

            override fun onError(error: OfflineRegionError) {
                // These errors are typically recoverable, so don't remove them, but log it
                Timber.w("onError with error = %s", error)
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                Timber.w("mapboxTileCountLimitExceeded with limit = %s", limit)
                OfflinePlugin.getInstance(this@OfflineDownloadService).errorDownload(
                    offlineDownload = offlineDownload,
                    error = "Mapbox tile count limit exceeded:$limit"
                )
            }
        })

        // Change the region state
        offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
    }

    /**
     * When a particular download has been completed, this method's called which handles removing the notification and
     * setting the download state.
     *
     * @param offlineRegion   the region which has finished being downloaded
     * @param offlineDownload the corresponding options used to define the offline region
     * @since 0.1.0
     */
    fun finishDownload(offlineDownload: OfflineDownloadOptions, offlineRegion: OfflineRegion) {
        // Announce the finish to any potential listeners as fast as possible
        OfflinePlugin.getInstance(this).removeDownload(offlineDownload, false)
        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
        offlineRegion.setObserver(null)
        removeOfflineRegion(offlineDownload.uuid)
    }

    private fun updateProgress(
        downloadOptions: OfflineDownloadOptions,
        status: OfflineRegionStatus
    ) {
        val regionPercentage =
            (if (status.requiredResourceCount >= 0) (100.0 * status.completedResourceCount / status.requiredResourceCount) else 0.0).toInt()

        // Careful, DownloadManager alerts download progress extremely rapidly
        // Android Notification Manager will punish us if we notify too often, so we do several safety check
        if (regionPercentage > downloadOptions.progress) {
            // Store the progress in the model, so that it can be accessed later
            downloadOptions.progress = regionPercentage
            OfflinePlugin.getInstance(this).onProgressChanged(downloadOptions, regionPercentage)
            OfflinePlugin.getInstance(this).getActiveDownloads()
                .filter { it.uuid == downloadOptions.uuid }
                .forEach { it.progress = regionPercentage }
            updateNotificationProgress(downloadOptions)
        }
    }

    private var lastAnnouncedProgress = 0

    @SuppressLint("MissingPermission")
    private fun updateNotificationProgress(
        downloadOptions: OfflineDownloadOptions,
    ) {
        val totalPercentage = calculateTotalDownloadPercentage()
        // Do another safety check to lower amount of Android notifications
        if (hasNotificationPermission() && totalPercentage != lastAnnouncedProgress) {
            Timber.v("Notifying progress change to percentage: %s", totalPercentage)
            lastAnnouncedProgress = totalPercentage
            builder.setContentText(
                resources.getString(
                    downloadOptions.notificationOptions.remainingTextRes,
                    totalPercentage,
                )
            ).build()
            builder.setProgress(100, totalPercentage, false)
            // We have to re-notify the summary each time we update, might have been swiped away
            notificationManager.notify(NOTIFICATION_FOREGROUND_ID, builder.build())
        }
    }

    private fun hasNotificationPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

    private fun calculateTotalDownloadPercentage(): Int {
        var total = 0
        val activeDownloads = OfflinePlugin.getInstance(this).getActiveDownloads()
        if (activeDownloads.isEmpty()) return total
        for (downloads in activeDownloads) {
            total += downloads.progress
        }
        return total / OfflinePlugin.getInstance(this).getActiveDownloads().size
    }

    override fun onBind(intent: Intent): IBinder? {
        // don't provide binding
        return null
    }
}
