package com.mapbox.mapboxsdk.plugins.offline.model

import android.os.Parcelable
import com.mapbox.mapboxsdk.camera.CameraPosition
import com.mapbox.mapboxsdk.geometry.LatLngBounds
import kotlinx.parcelize.Parcelize

@Parcelize
data class RegionSelectionOptions(
    val startingBounds: LatLngBounds? = null,
    val startingCameraPosition: CameraPosition? = null,
) : Parcelable {

    @Deprecated("use proper getter syntax", replaceWith = ReplaceWith("getStartingBounds()"))
    fun startingBounds(): LatLngBounds? {
        return startingBounds
    }

    @Deprecated("use proper getter syntax", replaceWith = ReplaceWith("getStatingCameraPosition()"))
    fun statingCameraPosition(): CameraPosition? {
        return startingCameraPosition
    }
}
