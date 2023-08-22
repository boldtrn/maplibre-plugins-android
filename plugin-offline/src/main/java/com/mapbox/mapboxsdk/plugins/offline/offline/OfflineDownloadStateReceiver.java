package com.mapbox.mapboxsdk.plugins.offline.offline;

import static com.mapbox.mapboxsdk.plugins.offline.offline.OfflineConstants.KEY_BUNDLE;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.mapbox.mapboxsdk.plugins.offline.model.OfflineDownloadOptions;

import timber.log.Timber;

public class OfflineDownloadStateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, @NonNull Intent intent) {
        String actionName = intent.getStringExtra(OfflineConstants.KEY_STATE);
        OfflineDownloadOptions offlineDownload = intent.getParcelableExtra(OfflineConstants.KEY_BUNDLE);
        OfflinePlugin offlinePlugin = OfflinePlugin.getInstance(context);
        if (actionName == null || offlineDownload == null) {
            Timber.w("Invalid broadcast received: %s - %s", actionName, offlineDownload);
            return;
        }

        switch (actionName) {
            case OfflineConstants.STATE_STARTED:
                offlinePlugin.addDownload(offlineDownload);
                break;
            case OfflineConstants.STATE_ERROR:
                String error = intent.getStringExtra(OfflineConstants.KEY_BUNDLE_OFFLINE_REGION);
                String message = intent.getStringExtra(OfflineConstants.KEY_BUNDLE_ERROR);
                offlinePlugin.errorDownload(offlineDownload, error, message);
                break;
            case OfflineConstants.STATE_PROGRESS:
                int progress = intent.getIntExtra(OfflineConstants.KEY_PROGRESS, 0);
                offlinePlugin.onProgressChanged(offlineDownload, progress);
                break;
            default:
                // removes the offline download (cancel or successful finish)
                offlinePlugin.removeDownload(offlineDownload, actionName.equals(OfflineConstants.STATE_CANCEL));
                break;
        }
    }

    static void dispatchProgressChanged(@NonNull Context context, OfflineDownloadOptions offlineDownload,
                                        int percentage) {
        Intent intent = new Intent(OfflineConstants.ACTION_OFFLINE);
        intent.putExtra(OfflineConstants.KEY_STATE, OfflineConstants.STATE_PROGRESS);
        intent.putExtra(KEY_BUNDLE, offlineDownload);
        intent.putExtra(OfflineConstants.KEY_PROGRESS, percentage);
        context.getApplicationContext().sendBroadcast(intent);
    }

    static void dispatchStartBroadcast(@NonNull Context context, OfflineDownloadOptions offlineDownload) {
        Intent intent = new Intent(OfflineConstants.ACTION_OFFLINE);
        intent.putExtra(OfflineConstants.KEY_STATE, OfflineConstants.STATE_STARTED);
        intent.putExtra(KEY_BUNDLE, offlineDownload);
        context.getApplicationContext().sendBroadcast(intent);
    }

    static void dispatchSuccessBroadcast(@NonNull Context context, OfflineDownloadOptions offlineDownload) {
        Intent intent = new Intent(OfflineConstants.ACTION_OFFLINE);
        intent.putExtra(OfflineConstants.KEY_STATE, OfflineConstants.STATE_FINISHED);
        intent.putExtra(KEY_BUNDLE, offlineDownload);
        context.getApplicationContext().sendBroadcast(intent);
    }

    static void dispatchErrorBroadcast(Context context, OfflineDownloadOptions offlineDownload, String error) {
        dispatchErrorBroadcast(context, offlineDownload, error, error);
    }

    static void dispatchErrorBroadcast(@NonNull Context context, OfflineDownloadOptions offlineDownload,
                                       String error, String message) {
        Intent intent = new Intent(OfflineConstants.ACTION_OFFLINE);
        intent.putExtra(OfflineConstants.KEY_STATE, OfflineConstants.STATE_ERROR);
        intent.putExtra(KEY_BUNDLE, offlineDownload);
        intent.putExtra(OfflineConstants.KEY_BUNDLE_ERROR, error);
        intent.putExtra(OfflineConstants.KEY_BUNDLE_MESSAGE, message);
        context.getApplicationContext().sendBroadcast(intent);
    }

    static void dispatchCancelBroadcast(@NonNull Context context, OfflineDownloadOptions offlineDownload) {
        Intent intent = new Intent(OfflineConstants.ACTION_OFFLINE);
        intent.putExtra(OfflineConstants.KEY_STATE, OfflineConstants.STATE_CANCEL);
        intent.putExtra(KEY_BUNDLE, offlineDownload);
        context.getApplicationContext().sendBroadcast(intent);
    }

    @NonNull
    public static Intent createCancelIntent(@NonNull Context context, @NonNull OfflineDownloadOptions downloadOptions) {
        Intent cancelIntent = new Intent(context, OfflineDownloadService.class);
        cancelIntent.putExtra(KEY_BUNDLE, downloadOptions);
        cancelIntent.setAction(OfflineConstants.ACTION_CANCEL_DOWNLOAD);
        return cancelIntent;
    }

    public static PendingIntent createNotificationIntent(Context context, @NonNull OfflineDownloadOptions downloadOptions) {
        Class<?> returnActivity = downloadOptions.getNotificationOptions().getReturnActivityClass();
        Intent notificationIntent = new Intent(context, returnActivity);
        notificationIntent.putExtra(KEY_BUNDLE, downloadOptions);
        return PendingIntent.getActivity(
                context,
                0,
                notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
