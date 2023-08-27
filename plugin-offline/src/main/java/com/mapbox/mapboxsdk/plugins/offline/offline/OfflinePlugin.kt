package com.mapbox.mapboxsdk.plugins.offline.offline

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import com.mapbox.mapboxsdk.offline.OfflineRegion
import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions
import com.mapbox.mapboxsdk.plugins.offline.utils.NOTIFICATION_REQUEST_ID
import com.mapbox.mapboxsdk.plugins.offline.utils.makeRetryRequestNotification
import com.mapbox.mapboxsdk.plugins.offline.utils.setupNotificationChannel

/**
 * OfflinePlugin is the main entry point for integrating the offline plugin into your app.
 *
 * To start downloading a region call [.startDownload]
 *
 * @since 0.1.0
 */
class OfflinePlugin private constructor(private val context: Context) {

    private val stateChangeDispatcher = OfflineDownloadChangeDispatcher()
    private val offlineDownloads: MutableList<OfflineDownloadOptions> = ArrayList()
    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupNotificationChannel(context, config)
        }
    }

    /**
     * Returns an immutable list of the currently active offline downloads
     */
    fun getActiveDownloads(): List<OfflineDownloadOptions> {
        return offlineDownloads
    }

    /**
     * Start downloading an offline download by providing an options object.
     *
     * You can listen to the actual creation of the download with [OfflineDownloadChangeListener].
     *
     * @param options the offline download builder
     * @since 0.1.0
     */
    fun startDownload(options: OfflineDownloadOptions) {
        startDownloads(arrayListOf(options))
    }

    fun startDownloads(options: ArrayList<OfflineDownloadOptions>) {
        require(options.isNotEmpty()) { "Unable to start downloads with no options" }
        val intent = Intent(context, OfflineDownloadService::class.java)
        intent.action = OfflineConstants.ACTION_START_DOWNLOAD
        pendingDownloads = options
        startServiceCompat(intent, options)
    }

    /**
     * Cancel an ongoing download.
     *
     * @since 0.1.0
     */
    fun cancelDownload() {
        val intent = Intent(context, OfflineDownloadService::class.java)
        intent.action = OfflineConstants.ACTION_CANCEL_DOWNLOAD
        context.startService(intent)
    }

    private fun startServiceCompat(
        intent: Intent,
        downloadOptions: ArrayList<OfflineDownloadOptions>
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                context.startForegroundService(intent)
            } catch (ex: ForegroundServiceStartNotAllowedException) {
                requestForeground(downloadOptions)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun requestForeground(downloadOptions: ArrayList<OfflineDownloadOptions>) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            notificationManager.notify(
                NOTIFICATION_REQUEST_ID,
                makeRetryRequestNotification(context, config.retryString, downloadOptions)
            )
        }
    }

    /**
     * Get the OfflineDownloadOptions for an offline region, returns null if no download is active for region.
     *
     * @param offlineRegion the offline region to get related offline download for
     * @return the active offline download, null if not downloading the region.
     * @since 0.1.0
     */
    fun getActiveDownloadForOfflineRegion(offlineRegion: OfflineRegion): OfflineDownloadOptions? {
        var offlineDownload: OfflineDownloadOptions? = null
        if (offlineDownloads.isNotEmpty()) {
            for (download in offlineDownloads) {
                if (download.uuid == offlineRegion.id) {
                    offlineDownload = download
                }
            }
        }
        return offlineDownload
    }

    /**
     * Add a callback that is invoked when the offline download state changes.
     *
     *
     * In normal cases this method will be invoked as part of [android.app.Activity.onStart]
     *
     *
     * @param listener the callback that will be invoked
     * @since 0.1.0
     */
    fun addOfflineDownloadStateChangeListener(listener: OfflineDownloadChangeListener?) {
        stateChangeDispatcher.addListener(listener)
    }

    /**
     * remove a callback that is invoked when the offline download state changes.
     *
     *
     * In normal cases this method will be invoked as part of [android.app.Activity.onStop]
     *
     *
     * @param listener the callback that will be removed
     * @since 0.1.0
     */
    fun removeOfflineDownloadStateChangeListener(listener: OfflineDownloadChangeListener?) {
        stateChangeDispatcher.removeListener(listener)
    }
    //
    // internal API
    //
    /**
     * Called when the OfflineDownloadService has created an offline region for an offlineDownload and
     * has assigned a region and service id.
     *
     * @param offlineDownload the offline download to track
     * @since 0.1.0
     */
    fun addDownload(offlineDownload: OfflineDownloadOptions) {
        offlineDownloads.add(offlineDownload)
        stateChangeDispatcher.onCreate(offlineDownload)
    }

    /**
     * Called when the OfflineDownloadService has finished downloading.
     *
     * @param offlineDownload the offline download to stop tracking
     * @since 0.1.0
     */
    fun removeDownload(offlineDownload: OfflineDownloadOptions, canceled: Boolean) {
        if (canceled) {
            stateChangeDispatcher.onCancel(offlineDownload)
        } else {
            stateChangeDispatcher.onSuccess(offlineDownload)
        }
        removeDownloadFromList(offlineDownload)
    }

    /**
     * Called when the OfflineDownloadService produced an error while downloading
     *
     * @param offlineDownload the offline download that produced an error
     * @param error           short description of the error
     * @param errorMessage    full description of the error
     * @since 0.1.0
     */
    fun errorDownload(
        offlineDownload: OfflineDownloadOptions,
        error: String? = null,
        errorMessage: String? = null
    ) {
        stateChangeDispatcher.onError(offlineDownload, error, errorMessage)
        removeDownloadFromList(offlineDownload)
    }

    private fun removeDownloadFromList(offlineDownload: OfflineDownloadOptions) {
        // We only compare uuid, as these are randomly generated when creating the OfflineDownloadOptions, so it's independent from other changes
        offlineDownloads.removeAll { it.uuid == offlineDownload.uuid }
    }

    /**
     * Called when the offline download service has made progress downloading an offline download.
     *
     * @param offlineDownload the offline download for which progress was made
     * @param progress        the amount of progress
     * @since 0.1.0
     */
    fun onProgressChanged(offlineDownload: OfflineDownloadOptions, progress: Int) {
        stateChangeDispatcher.onProgress(offlineDownload, progress)
    }

    var pendingDownloads: List<OfflineDownloadOptions>? = null
        private set

    companion object {
        // Suppress warning about context being possibly leaked, we immediately get the application
        // context which removes this risk.
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: OfflinePlugin? = null

        /**
         * Get the unique instance of the OfflinePlugin
         *
         * @param context The current context used to create intents and services later. This method
         * will look for the application context on the parameter you pass, so you don't need to do
         * that beforehand
         * @return the single instance of OfflinePlugin
         * @since 0.1.0
         */
        @JvmStatic
        fun getInstance(context: Context): OfflinePlugin = INSTANCE ?: synchronized(this) {
            INSTANCE ?: OfflinePlugin(context.applicationContext).also { INSTANCE = it }
        }

        /**
         * Initializer method, which can be called before or after any call of [getInstance]. Will
         * set all the desired fields for the offline handling and remember them on this unique
         * instance for the lifetime of the process. Therefore, you can configure the service to
         * your implementation's liking. Note that if you call this method after the first call to
         * [startDownload], it will have no effect anymore, as - currently - the service will not be
         * recreated.
         */
        @JvmStatic
        @JvmOverloads
        fun getConfiguredInstance(
            context: Context,
            channelName: String? = null,
            channelDescription: String? = null,
            retryString: String? = null
        ): OfflinePlugin {
            // This method may have many other effects in the future. Remember, the instance can
            // also be accessed and set here, e.g.: `getInstance(context).myField = myValue`
            val config = if (channelName.isNullOrBlank()) {
                // Don't overwrite default
                OfflineServiceConfiguration(
                    channelDescription = channelDescription,
                    retryString = retryString
                )
            } else {
                OfflineServiceConfiguration(
                    channelName = channelName,
                    channelDescription = channelDescription,
                    retryString = retryString
                )
            }
            OfflinePlugin.config = config
            return getInstance(context)
        }


        /**
         * Static configuration which is valid for all download services that are created during the
         * lifetime of the app. Initialized with an empty default containing reasonable fallbacks
         * where necessary.
         */
        @JvmStatic
        var config: OfflineServiceConfiguration = OfflineServiceConfiguration()
    }

}
