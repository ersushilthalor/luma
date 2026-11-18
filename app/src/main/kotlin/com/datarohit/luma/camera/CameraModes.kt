package com.datarohit.luma.camera

import com.datarohit.luma.capture.CaptureMode
import com.datarohit.luma.video.VideoMode

enum class ModeFamily { NIGHT, PORTRAIT, PHOTO, VIDEO, PRO, MORE }

/** Which engine runs a mode. */
enum class Engine { PHOTO, PANORAMA, VIDEO }

/** Every mode in the selector. Each one is wired to a real capture or recording pipeline. */
enum class ModeId(
    val label: String,
    val family: ModeFamily,
    val isVideo: Boolean,
    val icon: LumaIcon,
    val engine: Engine,
    /** Still pipeline (photo modes). */
    val capture: CaptureMode = CaptureMode.PHOTO,
    /** Recording pipeline (video modes). */
    val video: VideoMode? = null,
    /** One-line description shown in the More sheet. */
    val blurb: String,
) {
    NIGHT("Night", ModeFamily.NIGHT, false, LumaIcon.MOON, Engine.PHOTO, CaptureMode.NIGHT, blurb = "Long multi-frame RAW merge"),
    PORTRAIT("Portrait", ModeFamily.PORTRAIT, false, LumaIcon.PERSON, Engine.PHOTO, CaptureMode.PHOTO, blurb = "GPU bokeh around your subject"),
    PORTRAIT_VIDEO("Portrait Video", ModeFamily.PORTRAIT, true, LumaIcon.PERSON, Engine.VIDEO, video = VideoMode.PORTRAIT, blurb = "Live bokeh while recording"),
    PHOTO("Photo", ModeFamily.PHOTO, false, LumaIcon.APERTURE, Engine.PHOTO, CaptureMode.PHOTO, blurb = "Luma RAW pipeline"),
    VIDEO("Video", ModeFamily.VIDEO, true, LumaIcon.VIDEO, Engine.VIDEO, video = VideoMode.STANDARD, blurb = "Up to the best size the sensor streams"),
    PRO_PHOTO("Pro Photo", ModeFamily.PRO, false, LumaIcon.SLIDERS, Engine.PHOTO, CaptureMode.PRO, blurb = "Manual ISO, shutter, WB, focus"),
    PRO_VIDEO("Pro Video", ModeFamily.PRO, true, LumaIcon.SLIDERS, Engine.VIDEO, video = VideoMode.PRO, blurb = "Manual video, Log, LUTs, scopes"),
    FOOD("Food", ModeFamily.MORE, false, LumaIcon.FOOD, Engine.PHOTO, CaptureMode.PHOTO, blurb = "Tilt-shift ring, warm colour"),
    PANORAMA("Panorama", ModeFamily.MORE, false, LumaIcon.PANORAMA, Engine.PANORAMA, CaptureMode.PHOTO, blurb = "Gyro-guided sweep, stitched"),
    SLOW_MOTION("Slow Motion", ModeFamily.MORE, true, LumaIcon.SLOW_MOTION, Engine.VIDEO, video = VideoMode.SLOW_MOTION, blurb = "High-speed capture"),
    HYPERLAPSE("Hyperlapse", ModeFamily.MORE, true, LumaIcon.HYPERLAPSE, Engine.VIDEO, video = VideoMode.HYPERLAPSE, blurb = "Stabilised time-lapse"),
    DUAL_RECORDING("Dual Recording", ModeFamily.MORE, true, LumaIcon.DUAL, Engine.VIDEO, video = VideoMode.DUAL, blurb = "Front and back together"),
    MULTI_RECORDING("Multi Recording", ModeFamily.MORE, true, LumaIcon.MULTI, Engine.VIDEO, video = VideoMode.MULTI, blurb = "Several cameras at once"),
    ;

    /** Short name for the wheel, where long labels would crowd their neighbours. */
    val wheelLabel: String
        get() = when (this) {
            SLOW_MOTION -> "Slo-mo"
            DUAL_RECORDING -> "Dual"
            MULTI_RECORDING -> "Multi"
            else -> label
        }

    /** The other half of a Photo/Video pair (Portrait, Pro), if any. */
    val sibling: ModeId?
        get() = when (this) {
            PORTRAIT -> PORTRAIT_VIDEO
            PORTRAIT_VIDEO -> PORTRAIT
            PRO_PHOTO -> PRO_VIDEO
            PRO_VIDEO -> PRO_PHOTO
            else -> null
        }

    companion object {
        /** Tiles of the "More" sheet, in display order. */
        val MORE = listOf(PRO_VIDEO, PORTRAIT_VIDEO, FOOD, PANORAMA, SLOW_MOTION, HYPERLAPSE, DUAL_RECORDING, MULTI_RECORDING)
    }
}

/** One entry of the mode wheel. */
data class WheelEntry(val label: String, val family: ModeFamily, val mode: ModeId?)

/**
 * Wheel: NIGHT · PORTRAIT · PHOTO · VIDEO · PRO · [active "more" mode] · MORE.
 * Portrait and Pro hold a Photo/Video pair, switched by a segmented control.
 */
fun wheelEntries(current: ModeId, lastPortrait: ModeId, lastPro: ModeId): List<WheelEntry> = buildList {
    add(WheelEntry("Night", ModeFamily.NIGHT, ModeId.NIGHT))
    add(WheelEntry("Portrait", ModeFamily.PORTRAIT, if (current.family == ModeFamily.PORTRAIT) current else lastPortrait))
    add(WheelEntry("Photo", ModeFamily.PHOTO, ModeId.PHOTO))
    add(WheelEntry("Video", ModeFamily.VIDEO, ModeId.VIDEO))
    add(WheelEntry("Pro", ModeFamily.PRO, if (current.family == ModeFamily.PRO) current else lastPro))
    if (current.family == ModeFamily.MORE) add(WheelEntry(current.wheelLabel, ModeFamily.MORE, current))
    add(WheelEntry("More", ModeFamily.MORE, null))
}
