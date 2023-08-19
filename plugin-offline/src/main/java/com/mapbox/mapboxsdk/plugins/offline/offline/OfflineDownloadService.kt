package com.mapbox.mapboxsdk.plugins.offline.offline

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.collection.LongSparseArray
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mapbox.mapboxsdk.offline.OfflineManager
import com.mapbox.mapboxsdk.offline.OfflineRegion
import com.mapbox.mapboxsdk.offline.OfflineRegionError
import com.mapbox.mapboxsdk.offline.OfflineRegionStatus
import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions
import com.mapbox.mapboxsdk.plugins.offline.utils.setupNotificationChannel
import com.mapbox.mapboxsdk.plugins.offline.utils.toNotificationBuilder
import timber.log.Timber

private const val NOTIFICATION_FOREGROUND_ID = 1

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
    private var broadcastReceiver: OfflineDownloadStateReceiver? = null

    // map offline regions to requests, ids are received with onStartCommand, these match serviceId
    // in OfflineDownloadOptions
    private val requestedRegions = LongSparseArray<OfflineRegion>()
    private val regionDownloads = LongSparseArray<OfflineDownloadOptions>()

    override fun onCreate() {
        super.onCreate()
        Timber.v("onCreate called with config = {%s}", config)
        // Setup notification manager and channel
        notificationManager = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupNotificationChannel(this, config)
        }

        // Register the broadcast receiver needed for updating APIs in the OfflinePlugin class.
        broadcastReceiver = OfflineDownloadStateReceiver()
        ContextCompat.registerReceiver(
            this,
            broadcastReceiver,
            IntentFilter(OfflineConstants.ACTION_OFFLINE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Called each time a new download is initiated. First it acquires the [OfflineDownloadOptions] from the
     * intent and if found, the process of downloading the offline region carries on to the
     * [.onResolveCommand]. If the [OfflineDownloadOptions] fails to be
     * found inside the intent, the service is stopped (only if no other downloads are currently running) and throws a
     * [IllegalArgumentException].
     *
     * {@inheritDoc}
     *
     * @since 0.1.0
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.v("onStartCommand called with intent = %s", intent)
        // Android does not mark this as non-null yet, but we require non-null intent to start work
        requireNotNull(intent)
        val downloadOptions: OfflineDownloadOptions = requireNotNull(
            intent.getParcelableExtra(OfflineConstants.KEY_BUNDLE)
        ) { "DownloadOptions must be passed into the service for it to do any work" }
        updateNotificationBuilder(downloadOptions)
        /*
        This service is started as a foreground service, we need to call this method IMMEDIATELY.
        Never put any hard work before this line of code and never delay calling it. The Android
        system will crash the app after only a few seconds if this is not called
         */
        startForeground(NOTIFICATION_FOREGROUND_ID, builder.build())
        onResolveCommand(intent.action, downloadOptions)
        return START_STICKY
    }

    private fun updateNotificationBuilder(downloadOptions: OfflineDownloadOptions) {
        // TODO store the builder variables and check if they need an update instead of creating new here every time
        builder = toNotificationBuilder(
            this,
            OfflineDownloadStateReceiver.createNotificationIntent(
                applicationContext,
                downloadOptions
            ),
            OfflineDownloadStateReceiver.createCancelIntent(applicationContext, downloadOptions),
            downloadOptions.notificationOptions
        )
    }

    /**
     * Several actions can take place inside this service including starting and canceling a specific region download.
     * First, it is determined what action to take by using the `intentAction` parameter. This action is finally
     * passed in to the correct map offline methods.
     *
     * @param intentAction    string holding the task that should be performed on the specific
     * [OfflineDownloadOptions] regional download.
     * @param offlineDownload the download model which defines the region and other metadata needed to download the
     * correct region.
     * @since 0.1.0
     */
    private fun onResolveCommand(intentAction: String?, downloadOptions: OfflineDownloadOptions) {
        if (OfflineConstants.ACTION_START_DOWNLOAD == intentAction) {
            createDownload(downloadOptions)
        } else if (OfflineConstants.ACTION_CANCEL_DOWNLOAD == intentAction) {
            cancelDownloads()
        } else {
            throw IllegalArgumentException("Invalid intent action $intentAction")
        }
    }

    private fun createDownload(offlineDownload: OfflineDownloadOptions) {
        Timber.v("createDownload() called with: offlineDownload = %s", offlineDownload)
        OfflineManager.getInstance(applicationContext).createOfflineRegion(
            offlineDownload.definition,
            offlineDownload.metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    Timber.v("onCreate with: offlineRegion = %s", offlineRegion)
                    // TODO until this point, the offlineDownload.id is completely invalid, can we make it lateinit?
                    val options = offlineDownload.copy(uuid = offlineRegion.id)
                    OfflineDownloadStateReceiver.dispatchStartBroadcast(applicationContext, options)
                    requestedRegions.put(options.uuid, offlineRegion)
                    launchDownload(options, offlineRegion)
                    updateNotification(options)
                }

                override fun onError(error: String) {
                    Timber.w("onError with error = %s", error)
                    OfflineDownloadStateReceiver.dispatchErrorBroadcast(
                        applicationContext,
                        offlineDownload,
                        error
                    )
                }
            })
    }

    fun updateNotification(options: OfflineDownloadOptions) {
        Timber.d(
            "showNotification() called with: offlineDownload = [%s], requestedRegions = %s",
            options,
            requestedRegions
        )
        // TODO request permission if not already given
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("Notification permission not given")
            return
        }

        Timber.d("Notifying manager for offline download")
        notificationManager.notify(
            NOTIFICATION_FOREGROUND_ID,
            builder.setContentText(
                resources.getQuantityString(
                    options.notificationOptions.remainingTextRes,
                    requestedRegions.size(),
                    requestedRegions.size()
                )
            ).build()
        )

    }

    private fun cancelDownloads() {
        // Careful, the array is manipulated during this operation, iterate backwards to preserve indexing
        for (index in requestedRegions.size() - 1 downTo 0) {
            cancelDownload(requestedRegions.valueAt(index))
        }
    }

    private fun cancelDownload(offlineRegion: OfflineRegion) {
        val downloadOptions = regionDownloads[offlineRegion.id]
        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
        offlineRegion.setObserver(null)
        offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                Timber.v("Offline region {%s} deleted", offlineRegion.id)
            }

            override fun onError(error: String) {
                Timber.w("Offline region {%s} deletion error: %s", offlineRegion.id, error)
                OfflineDownloadStateReceiver.dispatchErrorBroadcast(
                    applicationContext,
                    downloadOptions,
                    error
                )
            }
        })
        OfflineDownloadStateReceiver.dispatchCancelBroadcast(applicationContext, downloadOptions)
        removeOfflineRegion(offlineRegion.id)
    }

    @Synchronized
    private fun removeOfflineRegion(regionId: Long) {
        requestedRegions.remove(regionId)
        if (requestedRegions.size() == 0) {
            // The current "batch / phase" off downloads is done, inform the user

            // Clear download progress of all items, so that future additions don't have remaining 100% models here
            regionDownloads.clear()
            stopForeground(true)
        }
        stopSelf(regionId.toInt())
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
        updateNotificationProgressDownload(offlineDownload)
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    finishDownload(offlineDownload, offlineRegion)
                    return
                }
                progressDownload(offlineDownload, status)
            }

            override fun onError(error: OfflineRegionError) {
                Timber.w("onError with error = %s", error)
                OfflineDownloadStateReceiver.dispatchErrorBroadcast(
                    applicationContext, offlineDownload,
                    error.reason, error.message
                )
                stopSelf(offlineDownload.uuid.toInt())
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                Timber.w("mapboxTileCountLimitExceeded with limit = %s", limit)
                OfflineDownloadStateReceiver.dispatchErrorBroadcast(
                    applicationContext, offlineDownload,
                    "Mapbox tile count limit exceeded:$limit"
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
        OfflineDownloadStateReceiver.dispatchSuccessBroadcast(this, offlineDownload)
        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
        offlineRegion.setObserver(null)
        removeOfflineRegion(offlineDownload.uuid)
    }

    private fun progressDownload(
        downloadOptions: OfflineDownloadOptions,
        status: OfflineRegionStatus
    ) {
        val regionPercentage =
            (if (status.requiredResourceCount >= 0) (100.0 * status.completedResourceCount / status.requiredResourceCount) else 0.0).toInt()
        val uuid = downloadOptions.uuid

        // Careful, DownloadManager alerts download progress extremely rapidly
        // Android Notification Manager will punish us if we notify too often, so we do several safety check
        if (
            regionPercentage != downloadOptions.progress &&
            regionPercentage % 5 == 0 &&
            requestedRegions[uuid] != null
        ) {
            // Store the progress in the model, so that it can be accessed later
            downloadOptions.progress = regionPercentage
            OfflineDownloadStateReceiver.dispatchProgressChanged(
                this,
                downloadOptions,
                regionPercentage
            )
            regionDownloads.put(uuid, downloadOptions)
            updateNotificationProgressDownload(downloadOptions)
        }
    }

    private fun updateNotificationProgressDownload(
        downloadOptions: OfflineDownloadOptions,
    ) {
        val totalPercentage = calculateTotalDownloadPercentage()
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // Careful, we have to re-notify the summary each time we update, might have been swiped away
            Timber.v("Notifying progress change to percentage: %s", totalPercentage)
            builder.setProgress(100, downloadOptions.progress, false)
            notificationManager.notify(NOTIFICATION_FOREGROUND_ID, builder.build())
        }
    }

    private fun calculateTotalDownloadPercentage(): Int {
        var total = 0
        for (index in 0 until regionDownloads.size()) {
            total += regionDownloads.valueAt(index).progress
        }
        return total / requestedRegions.size()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (broadcastReceiver != null) {
            try {
                unregisterReceiver(broadcastReceiver)
            } catch (ex: IllegalStateException) {
                Timber.d("Receiver already unregistered, ignoring")
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        // don't provide binding
        return null
    }

    companion object {

        /**
         * Static configuration which is valid for all download services that are created during the
         * lifetime of the app. Initialized with an empty default containing reasonable fallbacks
         * where necessary.
         */
        @JvmStatic
        var config: OfflineServiceConfiguration = OfflineServiceConfiguration()
    }
}
