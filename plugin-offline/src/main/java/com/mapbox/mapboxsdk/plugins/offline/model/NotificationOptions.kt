package com.mapbox.mapboxsdk.plugins.offline.model

import android.content.Context
import android.os.Parcelable
import androidx.annotation.DrawableRes
import com.mapbox.mapboxsdk.plugins.offline.R
import kotlinx.parcelize.Parcelize


@Parcelize
data class NotificationOptions(
    @DrawableRes val smallIconRes: Int = android.R.drawable.stat_sys_download,
    val returnActivity: String,
    val contentTitle: String,
    val contentText: String,
    val cancelText: String,
    val requestMapSnapshot: Boolean = true,
    val grouping: Boolean = true,
    ) : Parcelable {

    constructor(
        context: Context,
        @DrawableRes smallIconRes: Int = android.R.drawable.stat_sys_download,
        returnActivity: String,
        contentTitle: String = context.getString(R.string.mapbox_offline_notification_default_content_title),
        contentText: String = context.getString(R.string.mapbox_offline_notification_default_content_text),
        cancelText: String = context.getString(R.string.mapbox_offline_notification_action_cancel),
        requestMapSnapshot: Boolean = true
    ) : this(
        smallIconRes = smallIconRes,
        returnActivity = returnActivity,
        contentTitle = contentTitle,
        contentText = contentText,
        cancelText = cancelText,
        requestMapSnapshot = requestMapSnapshot
    )

    fun getReturnActivityClass(): Class<*>? {
        try {
            return Class.forName(returnActivity)
        } catch (exception: ClassNotFoundException) {
            throw IllegalArgumentException(
                "The returning class name $returnActivity cannot be found."
            )
        }
    }

    // TODO remove this companion object after a few versions
    companion object {
        @Deprecated(
            "Use idiomatic Kotlin constructor with named properties",
            replaceWith = ReplaceWith("NotificationOptions()")
        )
        fun builder(context: Context): Builder {
            return Builder()
                .smallIconRes(android.R.drawable.stat_sys_download)
                .contentTitle(context.getString(R.string.mapbox_offline_notification_default_content_title))
                .contentText(context.getString(R.string.mapbox_offline_notification_default_content_text))
                .cancelText(context.getString(R.string.mapbox_offline_notification_action_cancel))
                .requestMapSnapshot(true)
        }
    }

    @Deprecated(
        "Use idiomatic Kotlin constructor with named properties",
        replaceWith = ReplaceWith("NotificationOptions()")
    )
    class Builder {
        @DrawableRes
        private var smallIconRes: Int = android.R.drawable.stat_sys_download
        private lateinit var returnActivity: String
        private lateinit var contentTitle: String
        private lateinit var contentText: String
        private lateinit var cancelText: String
        private var requestMapSnapshot: Boolean = true

        @Deprecated(
            "Use idiomatic Kotlin constructor with named properties",
            replaceWith = ReplaceWith("NotificationOptions(smallIconRes = smallIconRes)")
        )
        fun smallIconRes(smallIconRes: Int) = apply { this.smallIconRes = smallIconRes }

        @Deprecated(
            "Use idiomatic Kotlin constructor with named properties",
            replaceWith = ReplaceWith("NotificationOptions(returnActivity = returnActivity)")
        )
        fun returnActivity(returnActivity: String) = apply { this.returnActivity = returnActivity }

        @Deprecated(
            "Use idiomatic Kotlin constructor with named properties",
            replaceWith = ReplaceWith("NotificationOptions(contentTitle = contentTitle)")
        )
        fun contentTitle(contentTitle: String) = apply { this.contentTitle = contentTitle }

        @Deprecated(
            "Use idiomatic Kotlin constructor with named properties",
            replaceWith = ReplaceWith("NotificationOptions(contentText = contentText)")
        )
        fun contentText(contentText: String) = apply { this.contentText = contentText }

        @Deprecated(
            "Use idiomatic Kotlin constructor with named properties",
            replaceWith = ReplaceWith("NotificationOptions(cancelText = cancelText)")
        )
        fun cancelText(cancelText: String) = apply { this.cancelText = cancelText }

        @Deprecated(
            "Use idiomatic Kotlin constructor with named properties",
            replaceWith = ReplaceWith("NotificationOptions(requestMapSnapshot = requestMapSnapshot)")
        )
        fun requestMapSnapshot(requestMapSnapshot: Boolean) =
            apply { this.requestMapSnapshot = requestMapSnapshot }

        @Deprecated(
            "Use idiomatic Kotlin constructor with named properties",
            replaceWith = ReplaceWith("NotificationOptions()")
        )
        fun build(): NotificationOptions {
            return NotificationOptions(
                smallIconRes,
                returnActivity,
                contentTitle,
                contentText,
                cancelText,
                requestMapSnapshot
            )
        }
    }
}
