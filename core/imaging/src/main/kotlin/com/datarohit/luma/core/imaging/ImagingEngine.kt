package com.datarohit.luma.core.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

data class EngineBuildInfo(
    val version: String,
    val architecture: String,
    val compiler: String,
    val simd: String,
)

object ImagingEngine {

    /** No-op when using Kotlin fallback instead of native libluma_imaging. */
    fun ensureLoaded() = Unit

    fun buildInfo(): EngineBuildInfo = EngineBuildInfo(
        version = "0.2.0-jvm",
        architecture = System.getProperty("os.arch") ?: "jvm",
        compiler = "Kotlin/JVM",
        simd = "Fallback",
    )

    /**
     * Resamples [src] into [dst] (both ARGB_8888, any sizes) using Android Canvas filtering
     * as a pure-Kotlin fallback for the native Lanczos-3 resampler.
     */
    fun resample(src: Bitmap, dst: Bitmap): Boolean {
        if (src.isRecycled || dst.isRecycled) return false
        val canvas = Canvas(dst)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, null, Rect(0, 0, dst.width, dst.height), paint)
        return true
    }
}
