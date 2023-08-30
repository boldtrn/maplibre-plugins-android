package com.mapbox.mapboxsdk.plugins.offline.offline;

public class OfflineConstants {

    private OfflineConstants() {
        // No Instances
    }

    public static final String ACTION_START_DOWNLOAD = "com.mapbox.mapboxsdk.plugins.offline.download.start";
    public static final String ACTION_CANCEL_DOWNLOAD = "com.mapbox.mapboxsdk.plugins.offline.download.cancel";
    public static final String NOTIFICATION_CHANNEL = "offline";

    static final String KEY_BUNDLE_OFFLINE_REGION_ID = "com.mapbox.mapboxsdk.plugins.offline.region_id";

    public static final String RETURNING_DEFINITION = "com.mapbox.mapboxsdk.plugins.offline.returning.definition";
    public static final String RETURNING_REGION_NAME = "com.mapbox.mapboxsdk.plugins.offline.returing.region.name";

}
