package com.datarohit.luma.portrait

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import java.nio.ByteOrder

/** Person probability per pixel (0..255), row 0 = top of the upright image it was computed from. */
class SubjectMask(val width: Int, val height: Int, val data: ByteArray) {
    /** Fraction of the frame that is subject (> 50 %). */
    val coverage: Float by lazy { data.count { (it.toInt() and 0xFF) > 127 }.toFloat() / data.size }
}

/**
 * On-device person segmentation for Portrait (MediaPipe multiclass selfie segmenter, bundled model, no network).
 * Subject = everything that is not background: hair, face and body skin, clothes and accessories, so hair and
 * shoulders stay sharp and the blur starts where the person ends.
 */
class PortraitSegmenter(private val context: Context) {

    private var segmenter: ImageSegmenter? = null
    private var failed = false

    @Synchronized
    fun segment(bitmap: Bitmap): SubjectMask? {
        if (failed) return null
        val seg = segmenter ?: runCatching {
            ImageSegmenter.createFromOptions(
                context,
                ImageSegmenter.ImageSegmenterOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setOutputConfidenceMasks(true)
                    .setOutputCategoryMask(false)
                    .build(),
            )
        }.onFailure {
            Log.w(TAG, "segmenter unavailable", it)
            failed = true
        }.getOrNull()?.also { segmenter = it } ?: return null
        return runCatching {
            val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val result = seg.segment(BitmapImageBuilder(input).build())
            val background = result.confidenceMasks().get()[0]
            val w = background.width
            val h = background.height
            val floats = ByteBufferExtractor.extract(background).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val out = ByteArray(w * h)
            for (i in 0 until w * h) out[i] = ((1f - floats.get(i)).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            result.confidenceMasks().get().forEach { it.close() }
            SubjectMask(w, h, out)
        }.onFailure { Log.w(TAG, "segmentation failed", it) }.getOrNull()
    }

    @Synchronized
    fun close() {
        segmenter?.close()
        segmenter = null
    }

    private companion object {
        const val TAG = "LumaPortrait"
        const val MODEL = "models/selfie_multiclass_256x256.tflite"
    }
}
