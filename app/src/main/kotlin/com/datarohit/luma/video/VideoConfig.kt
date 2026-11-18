package com.datarohit.luma.video

import android.media.MediaFormat
import android.util.Size
import com.datarohit.luma.device.EncoderInfo

enum class VideoCodec(val label: String, val mime: String) {
    H264("H.264", MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC("HEVC", MediaFormat.MIMETYPE_VIDEO_HEVC),
    APV("APV", EncoderInfo.MIME_APV),
}

/** Colour pipeline of a recording. 10-bit HDR profiles go camera → encoder directly. */
enum class ColorProfile(val label: String, val short: String, val hdr: Boolean) {
    SDR("Rec.709 SDR", "709", false),
    LOG("Luma Log", "LOG", false),
    HLG("HDR HLG 10-bit", "HLG", true),
    HDR10("HDR10 PQ 10-bit", "HDR10", true),
    HDR10_PLUS("HDR10+ 10-bit", "HDR10+", true),
}

enum class ApvQuality(val label: String, val bitsPerPixel: Float) { HQ("422 HQ", 2.4f), LQ("422 LQ", 1.2f) }

enum class AudioSourceOption(val label: String) { CAMCORDER("Camcorder"), MIC("Mic"), UNPROCESSED("Unprocessed"), EXTERNAL("External / USB") }

enum class Stabilization(val label: String) { OFF("Off"), OIS("OIS"), OIS_EIS("OIS + EIS"), HORIZON("Horizon Lock") }

enum class HyperlapseSpeed(val label: String, val factor: Int) {
    AUTO("Auto", 0), X4("4×", 4), X8("8×", 8), X16("16×", 16), X32("32×", 32), X60("60×", 60)
}

enum class DualLayout(val label: String) { PIP("PiP"), SPLIT("Split") }

enum class FrameGuide(val label: String, val aspect: Float?) {
    NONE("Off", null), CINEMA("2.39:1", 2.39f), WIDE("16:9", 16f / 9f), FLAT("1.85:1", 1.85f), VERTICAL("9:16", 9f / 16f), THIRDS("Thirds", null), CROSS("Center", null)
}

data class VideoConfig(
    val size: Size = Size(3840, 2160),
    val fps: Int = 30,
    val codec: VideoCodec = VideoCodec.HEVC,
    val profile: ColorProfile = ColorProfile.SDR,
    val apvQuality: ApvQuality = ApvQuality.HQ,
    /** Bits per pixel per frame for H.264/HEVC (0.2 = high bitrate). */
    val bitsPerPixel: Float = 0.2f,
    val stabilization: Stabilization = Stabilization.OIS_EIS,
    val audio: Boolean = true,
    val audioSource: AudioSourceOption = AudioSourceOption.CAMCORDER,
    /** Linear input gain in dB (-12..+24). */
    val audioGainDb: Float = 0f,
    val desqueeze: Float = 1f,
    /** Bake the anamorphic desqueeze into the file (otherwise preview only). */
    val bakeDesqueeze: Boolean = false,
    /** Display LUT = monitoring only; record LUT = burnt into the clip. */
    val displayLut: Boolean = false,
    val recordLut: Boolean = false,
    val lutId: String? = null,
    /** Luma's colour engine (neutral HAL stream + GPU tone mapping) instead of the camera's own look. */
    val engine: Boolean = true,
    val look: com.datarohit.luma.gl.ColorLook = com.datarohit.luma.gl.ColorLook.NATURAL,
    /** Highlight headroom: AE is asked to underexpose by this much; the GPU restores mid-tones. */
    val headroomEv: Float = 0.7f,
    val grain: Boolean = true,
    /** Local tone mapping (dynamic range): 0 = off, 0.35 = natural, 0.55 = strong. */
    val localTone: Float = 0.3f,
) {
    val label: String
        get() = buildString {
            append(if (size.width >= 7680) "8K" else if (size.width >= 3840) "4K" else if (size.width >= 1920) "FHD" else "${size.height}p")
            append(" ").append(fps)
        }

    fun bitrate(encoderMax: Int, frameSize: Size = size): Int {
        val pixelsPerSecond = frameSize.width.toDouble() * frameSize.height * fps
        val bpp = when {
            codec == VideoCodec.APV -> apvQuality.bitsPerPixel.toDouble()
            profile.hdr -> bitsPerPixel * 1.25
            codec == VideoCodec.H264 -> bitsPerPixel * 1.4
            else -> bitsPerPixel.toDouble()
        }
        val target = (pixelsPerSecond * bpp).toLong()
        val max = if (encoderMax > 0) encoderMax.toLong() else 400_000_000L
        return target.coerceIn(2_000_000L, max).toInt()
    }
}

/** Recording-rate mapping: output pts = (pts - first) * [timeScale]; [keepEvery] frames dropped before encoding. */
data class TimeMapping(val timeScale: Double = 1.0, val keepEvery: Int = 1)
