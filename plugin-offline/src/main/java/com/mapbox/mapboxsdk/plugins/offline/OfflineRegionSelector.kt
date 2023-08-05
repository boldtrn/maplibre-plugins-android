package com.mapbox.mapboxsdk.plugins.offline

import android.app.Activity
import android.content.Intent
import com.mapbox.mapboxsdk.offline.OfflineRegionDefinition
import com.mapbox.mapboxsdk.plugins.offline.model.NotificationOptions
import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions
import com.mapbox.mapboxsdk.plugins.offline.model.RegionSelectionOptions
import com.mapbox.mapboxsdk.plugins.offline.offline.OfflineConstants
import com.mapbox.mapboxsdk.plugins.offline.ui.OfflineActivity

/**
 * While the offline plugin includes a service for optimally launching an offline download session,
 * the plugin also includes UI components which also assist in providing a way for your app users to
 * select the region in which they'd like to download the region they desire.
 *
 *
 * This specific class is used to build an intent which launches the [OfflineActivity] which
 * provides your users with a way to define the region which they'd like to download. This includes
 * automatically providing the region definition with a name; Whether that's the default name or the
 * name of the region the map's camera position is currently located.
 *
 *
 * The provided Intent builder in this class should be built with the options you would like to
 * provide, then passed into the [Activity.startActivityForResult] or related
 * methods. Inside the same activity being used to launch this activity you'll need to
 * `@Override` [Activity.onActivityResult] and use the provided static
 * methods also provided in this class.
 *
 *
 * Note that if you are using this exclusively inside your app with a Mapbox Map not already being
 * used somewhere else, you'll need to provide a Mapbox access token by overriding your applications
 * `onCreate` method and placing [com.mapbox.mapboxsdk.Mapbox.getInstance]
 * inside the override method.
 *
 *
 * @since 0.1.0
 */
object OfflineRegionSelector {

    /**
     * Use this method to take the returning [Intent] data and construct a [OfflineDownloadOptions]
     * instance which can be used for starting a new offline region download.
     *
     * @param data                the [Activity.startActivityForResult] which this
     * method should be used in provides the returning intent which should
     * be provided in this param
     * @param notificationOptions the [NotificationOptions] object you've constructed to be used
     * when launching the offline region download service.
     * @return a new [OfflineDownloadOptions] instance which can be used to launch the download
     * service using
     * [com.mapbox.mapboxsdk.plugins.offline.offline.OfflinePlugin.startDownload]
     * @since 0.1.0
     */
    @JvmStatic
    fun getOfflineDownloadOptions(
        data: Intent,
        notificationOptions: NotificationOptions
    ): OfflineDownloadOptions {
        return OfflineDownloadOptions(
            definition = getRegionDefinition(data),
            regionName = getRegionName(data),
            notificationOptions = notificationOptions,
        )
    }

    /**
     * Use this method to take the returning [Intent] data and construct a [OfflineDownloadOptions]
     * instance which can be used for starting a new offline region download.
     *
     * @param data                the [Activity.startActivityForResult] which this
     * method should be used in provides the returning intent which should
     * be provided in this param
     * @param notificationOptions the [NotificationOptions] object you've constructed to be used
     * when launching the offline region download service.
     * @param metadata            Add additional metadata to the [OfflineRegionDefinition],
     * note to make sure not to override the region definition name if you
     * still wish to use it
     * @return a new [OfflineDownloadOptions] instance which can be used to launch the download
     * service using
     * [com.mapbox.mapboxsdk.plugins.offline.offline.OfflinePlugin.startDownload]
     * @since 0.1.0
     */
    @JvmStatic
    fun getOfflineDownloadOptions(
        data: Intent,
        notificationOptions: NotificationOptions,
        metadata: ByteArray
    ): OfflineDownloadOptions {
        return OfflineDownloadOptions(
            definition = getRegionDefinition(data),
            regionName = getRegionName(data),
            notificationOptions = notificationOptions,
            metadata = metadata,
        )
    }

    /**
     * Returns the [OfflineRegionDefinition] which was created when the user was
     * inside the [OfflineActivity].
     *
     * @param data the [Activity.startActivityForResult] which this method should
     * be used in provides the returning intent which should be provided in this param
     * @return the [OfflineRegionDefinition] which was created inside the
     * [OfflineActivity]
     * @since 0.1.0
     */
    @JvmStatic
    fun getRegionDefinition(data: Intent): OfflineRegionDefinition {
        return data.getParcelableExtra(OfflineConstants.RETURNING_DEFINITION)!!
    }

    /**
     * The [OfflineActivity] class will try to provide a region name which is either the default
     * region naming string or, depending on the map's camera position and where it is positioned over
     * the map.
     *
     * @param data the [Activity.startActivityForResult] which this method should
     * be used in provides the returning intent which should be provided in this param
     * @return either a string containing the default region name or the actual region name which the
     * map's camera position was last placed over
     * @since 0.1.0
     */
    @JvmStatic
    fun getRegionName(data: Intent): String {
        return data.getStringExtra(OfflineConstants.RETURNING_REGION_NAME)!!
    }

    /**
     * Useful for building an [Intent] which can be used to launch the [OfflineActivity]
     * allowing your app user to select a region which they'd like to download.
     *
     * @since 0.1.0
     */
    class IntentBuilder {
        var intent: Intent

        /**
         * Construct a new instance of this builder.
         *
         * @since 0.1.0
         */
        init {
            intent = Intent()
        }

        fun regionSelectionOptions(regionSelectionOptions: RegionSelectionOptions?): IntentBuilder {
            intent.putExtra(OfflinePluginConstants.REGION_SELECTION_OPTIONS, regionSelectionOptions)
            return this
        }

        /**
         * Build a new [Intent] object which should be used to launch the [OfflineActivity].
         *
         * @param activity pass in the current activity which you intend to use
         * [Activity.startActivityForResult] in.
         * @return a new [Intent] which should be used to launch the [OfflineActivity]
         * @since 0.1.0
         */
        fun build(activity: Activity?): Intent {
            intent.setClass(activity!!, OfflineActivity::class.java)
            return intent
        }
    }
}
