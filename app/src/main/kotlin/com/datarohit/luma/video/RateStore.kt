package com.datarohit.luma.video

import android.content.Context
import android.os.Build
import android.util.Size
import androidx.core.content.edit

/**
 * Remembers frame rates measured on this phone (per firmware, camera and stream size). Cameras often hold a
 * fixed AE range such as [60, 60] at sizes whose advertised minimum frame duration is longer; Luma offers
 * those rates and keeps whatever the sensor timestamps prove.
 */
class RateStore(context: Context) {

    private val prefs = context.getSharedPreferences("luma_rates", Context.MODE_PRIVATE)

    private fun key(cameraId: String, size: Size, fps: Int) = "${Build.FINGERPRINT}|$cameraId|${size.width}x${size.height}@$fps"

    fun failed(cameraId: String, size: Size, fps: Int): Boolean = prefs.getString(key(cameraId, size, fps), null) == FAILED
    fun verified(cameraId: String, size: Size, fps: Int): Boolean = prefs.getString(key(cameraId, size, fps), null) == VERIFIED

    fun record(cameraId: String, size: Size, fps: Int, sustained: Boolean) {
        prefs.edit { putString(key(cameraId, size, fps), if (sustained) VERIFIED else FAILED) }
    }

    private companion object {
        const val FAILED = "failed"
        const val VERIFIED = "verified"
    }
}
