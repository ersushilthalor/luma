package com.datarohit.luma.core.imaging

data class EngineBuildInfo(
    val version: String,
    val architecture: String,
    val compiler: String,
    val simd: String,
)

object ImagingEngine {

    init {
        System.loadLibrary("luma_imaging")
    }

    /** Forces the native library to load (touching the object runs the initializer). */
    fun ensureLoaded() = Unit

    fun buildInfo(): EngineBuildInfo {
        val (version, architecture, compiler, simd) = nativeBuildInfo().split('\n')
        return EngineBuildInfo(version, architecture, compiler, simd)
    }

    /**
     * Resamples [src] into [dst] (both ARGB_8888, any sizes) with the engine's Lanczos-3 resampler:
     * sharp enlargement without ringing artefacts beyond the kernel, area-correct reduction.
     */
    fun resample(src: android.graphics.Bitmap, dst: android.graphics.Bitmap): Boolean = nativeResample(src, dst)

    @JvmStatic
    private external fun nativeBuildInfo(): String

    @JvmStatic
    private external fun nativeResample(src: android.graphics.Bitmap, dst: android.graphics.Bitmap): Boolean
}
