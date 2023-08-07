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
import com.mapbox.mapboxsdk.offline.OfflineManager.Companion.getInstance
import com.mapbox.mapboxsdk.offline.OfflineRegion
import com.mapbox.mapboxsdk.offline.OfflineRegionDefinition
import com.mapbox.mapboxsdk.offline.OfflineRegionError
import com.mapbox.mapboxsdk.offline.OfflineRegionStatus
import com.mapbox.mapboxsdk.plugins.offline.R
import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions
import com.mapbox.mapboxsdk.plugins.offline.utils.makeSummaryNotification
import com.mapbox.mapboxsdk.plugins.offline.utils.setupNotificationChannel
import com.mapbox.mapboxsdk.plugins.offline.utils.toNotificationBuilder
import com.mapbox.mapboxsdk.snapshotter.MapSnapshot
import com.mapbox.mapboxsdk.snapshotter.MapSnapshotter
import timber.log.Timber

data class OfflineServiceConfiguration(
    val channelName: String? = null,
    val channelDescription: String? = null,
    val useGrouping: Boolean = false,
    val channelLightColor: Int? = null
)

/**
 * Internal usage only, use this service indirectly by using methods found in [OfflinePlugin]. When an offline
 * download is initiated for the first time using this plugin, this service is created, captures the
 * `StartCommand` and collects the [OfflineDownloadOptions] instance which holds all the download metadata
 * needed to perform the download.
 *
 *
 * If another offline download is initiated through the [OfflinePlugin] while another download is currently in
 * process, this service will add it to the [OfflineManager] queue for downloading, downstream, this will execute
 * the region downloads asynchronously (although writing to the same file). Once all downloads have been completed, this
 * service is stopped and destroyed.
 *
 * @since 0.1.0
 */
class OfflineDownloadService : Service() {
    private lateinit var notificationManager: NotificationManagerCompat
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var mapSnapshotter: MapSnapshotter? = null
    private var broadcastReceiver: OfflineDownloadStateReceiver? = null

    // map offline regions to requests, ids are received with onStartCommand, these match serviceId
    // in OfflineDownloadOptions
    val regionLongSparseArray = LongSparseArray<OfflineRegion?>()
    override fun onCreate() {
        super.onCreate()
        Timber.v("onCreate called")
        // Setup notification manager and channel
        notificationManager = NotificationManagerCompat.from(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupNotificationChannel(config)
        }

        // Register the broadcast receiver needed for updating APIs in the OfflinePlugin class.
        broadcastReceiver = OfflineDownloadStateReceiver()
        val filter = IntentFilter(OfflineConstants.ACTION_OFFLINE)
        ContextCompat.registerReceiver(
            this,
            broadcastReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /**
     * Called each time a new download is initiated. First it acquires the [OfflineDownloadOptions] from the
     * intent and if found, the process of downloading the offline region carries on to the
     * [.onResolveCommand]. If the [OfflineDownloadOptions] fails to be
     * found inside the intent, the service is stopped (only if no other downloads are currently running) and throws a
     * [NullPointerException].
     *
     *
     * {@inheritDoc}
     *
     * @since 0.1.0
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.v("onStartCommand called.")
        if (intent != null) {
            val offlineDownload =
                intent.getParcelableExtra<OfflineDownloadOptions>(OfflineConstants.KEY_BUNDLE)
            if (offlineDownload != null) {
                onResolveCommand(intent.action, offlineDownload)
            } else {
                stopSelf(startId)
                throw NullPointerException(
                    "A DownloadOptions instance must be passed into the service to"
                            + " begin downloading."
                )
            }
        }
        return START_STICKY
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
    private fun onResolveCommand(intentAction: String?, offlineDownload: OfflineDownloadOptions) {
        if ((OfflineConstants.ACTION_START_DOWNLOAD == intentAction)) {
            createDownload(offlineDownload)
        } else if ((OfflineConstants.ACTION_CANCEL_DOWNLOAD == intentAction)) {
            cancelDownload(offlineDownload)
        }
    }

    private fun createDownload(offlineDownload: OfflineDownloadOptions) {
        val definition = offlineDownload.definition
        val metadata = offlineDownload.metadata
        getInstance(applicationContext).createOfflineRegion(
            definition,
            metadata,
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    val options = offlineDownload.copy(uuid = offlineRegion.id)
                    OfflineDownloadStateReceiver.dispatchStartBroadcast(applicationContext, options)
                    regionLongSparseArray.put(options.uuid, offlineRegion)
                    launchDownload(options, offlineRegion)
                    showNotification(options)
                }

                override fun onError(error: String) {
                    OfflineDownloadStateReceiver.dispatchErrorBroadcast(
                        applicationContext,
                        offlineDownload,
                        error
                    )
                }
            })
    }

    fun showNotification(options: OfflineDownloadOptions) {
        Timber.d("showNotification() called with: offlineDownload = [%s]", options)
        notificationBuilder = toNotificationBuilder(
            this,
            options,
            OfflineDownloadStateReceiver.createNotificationIntent(
                applicationContext,
                options
            ),
            OfflineDownloadStateReceiver.createCancelIntent(applicationContext, options)
        )
        if (regionLongSparseArray.isEmpty) {
            // TODO why is this here, this service is never started as a foregroundService
            startForeground(options.uuid.toInt(), notificationBuilder!!.build())
        } else {
            // TODO request permission if not already given
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Timber.d("Notifying manager for offline download")
                notificationManager.notify(options.uuid.toInt(), notificationBuilder!!.build())
                if (config?.useGrouping == true) {
                    notificationManager.notify(0, makeSummaryNotification(this, options))
                }
            }
        }
        if (options.notificationOptions.requestMapSnapshot) {
            // create map bitmap to show as notification icon
            createMapSnapshot(
                options.definition
            ) { snapshot: MapSnapshot ->
                val regionId: Int = options.uuid.toInt()
                if (regionLongSparseArray.get(regionId.toLong()) != null) {
                    notificationBuilder!!.setLargeIcon(snapshot.bitmap)
                    Timber.d("Notifying manager for region")
                    notificationManager.notify(regionId, notificationBuilder!!.build())
                }
            }
        }
    }

    private fun createMapSnapshot(
        definition: OfflineRegionDefinition,
        callback: MapSnapshotter.SnapshotReadyCallback
    ) {
        val resources = resources
        val height = resources.getDimension(R.dimen.notification_large_icon_height).toInt()
        val width = resources.getDimension(R.dimen.notification_large_icon_width).toInt()
        val options = MapSnapshotter.Options(width, height)
        options.withStyle(definition.styleURL)
        options.withRegion(definition.bounds)
        mapSnapshotter = MapSnapshotter(this, options)
        mapSnapshotter!!.start(callback)
    }

    private fun cancelDownload(offlineDownload: OfflineDownloadOptions) {
        val serviceId = offlineDownload.uuid.toInt()
        val offlineRegion = regionLongSparseArray[serviceId.toLong()]
        if (offlineRegion != null) {
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
                        offlineDownload,
                        error
                    )
                }
            })
        }
        OfflineDownloadStateReceiver.dispatchCancelBroadcast(applicationContext, offlineDownload)
        removeOfflineRegion(serviceId)
    }

    @Synchronized
    private fun removeOfflineRegion(regionId: Int) {
        if (notificationBuilder != null) {
            notificationManager.cancel(regionId)
        }
        regionLongSparseArray.remove(regionId.toLong())
        if (regionLongSparseArray.size() == 0) {
            stopForeground(true)
        }
        stopSelf(regionId)
    }

    fun launchDownload(offlineDownload: OfflineDownloadOptions, offlineRegion: OfflineRegion) {
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (status.isComplete) {
                    finishDownload(offlineDownload, offlineRegion)
                    return
                }
                progressDownload(offlineDownload, status)
            }

            override fun onError(error: OfflineRegionError) {
                OfflineDownloadStateReceiver.dispatchErrorBroadcast(
                    applicationContext, offlineDownload,
                    error.reason, error.message
                )
                stopSelf(offlineDownload.uuid.toInt())
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
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
        removeOfflineRegion(offlineDownload.uuid.toInt())
    }

    fun progressDownload(offlineDownload: OfflineDownloadOptions, status: OfflineRegionStatus) {
        val percentage =
            (if (status.requiredResourceCount >= 0) (100.0 * status.completedResourceCount / status.requiredResourceCount) else 0.0).toInt()
        offlineDownload.progress = percentage
        if (percentage % 2 == 0 && regionLongSparseArray[offlineDownload.uuid] != null) {
            // TODO Progess updates currently make the UI flicker, find out if this is the cause
            OfflineDownloadStateReceiver.dispatchProgressChanged(this, offlineDownload, percentage)
            notificationBuilder?.let {
                it.setProgress(100, percentage, false)
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notificationManager.notify(
                        offlineDownload.uuid.toInt(),
                        it.build()
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mapSnapshotter?.cancel()
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
         * lifetime of the app.
         */
        @JvmStatic
        var config: OfflineServiceConfiguration? = null
    }
}
