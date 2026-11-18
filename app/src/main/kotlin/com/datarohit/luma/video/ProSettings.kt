package com.datarohit.luma.video

import android.content.Context
import android.util.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScopeKind(val label: String) { OFF("Off"), HISTOGRAM("Histogram"), WAVEFORM("Waveform"), PARADE("RGB Parade") }

enum class PeakColor(val label: String, val argb: Int) {
    RED("Red", 0xFFFF3B30.toInt()), GREEN("Green", 0xFF34C759.toInt()), BLUE("Blue", 0xFF0A84FF.toInt()),
    YELLOW("Yellow", 0xFFFFD60A.toInt()), WHITE("White", 0xFFFFFFFF.toInt()),
}

enum class PeakSensitivity(val label: String, val threshold: Float) { LOW("Low", 0.32f), MEDIUM("Medium", 0.2f), HIGH("High", 0.12f) }

/** Persistent video, monitoring and look settings (Pro Video and every video mode). */
data class ProSettings(
    val video: VideoConfig = VideoConfig(),
    val slowMoFps: Int = 240,
    val hyperlapse: HyperlapseSpeed = HyperlapseSpeed.AUTO,
    val dualLayout: DualLayout = DualLayout.PIP,
    val dualSeparateFiles: Boolean = false,
    val multiComboKey: String? = null,
    val multiSeparateFiles: Boolean = false,
    val peaking: Boolean = false,
    val peakColor: PeakColor = PeakColor.RED,
    val peakSensitivity: PeakSensitivity = PeakSensitivity.MEDIUM,
    val zebraIre: Int = 0,
    val falseColor: Boolean = false,
    val scope: ScopeKind = ScopeKind.OFF,
    val levelGauge: Boolean = true,
    val guide: FrameGuide = FrameGuide.NONE,
    val hudExpanded: Boolean = true,
    val storageTree: String? = null,
    val portraitFStop: Float = 2.0f,
    val portraitStyle: com.datarohit.luma.gl.BokehStyle = com.datarohit.luma.gl.BokehStyle.NATURAL,
    val foodRadius: Float = 0.22f,
    val foodBlur: Float = 0.6f,
)

class ProSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("luma_video", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<ProSettings> = _state.asStateFlow()

    fun update(transform: (ProSettings) -> ProSettings) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    private fun load(): ProSettings {
        val d = ProSettings()
        val v = d.video
        return ProSettings(
            video = VideoConfig(
                size = Size(prefs.getInt("w", v.size.width), prefs.getInt("h", v.size.height)),
                fps = prefs.getInt("fps", v.fps),
                codec = enumOr(prefs.getString("codec", null), v.codec),
                profile = enumOr(prefs.getString("profile", null), v.profile),
                apvQuality = enumOr(prefs.getString("apvQuality", null), v.apvQuality),
                bitsPerPixel = prefs.getFloat("bpp", v.bitsPerPixel),
                stabilization = enumOr(prefs.getString("stab", null), v.stabilization),
                audio = prefs.getBoolean("audio", v.audio),
                audioSource = enumOr(prefs.getString("audioSource", null), v.audioSource),
                audioGainDb = prefs.getFloat("gain", v.audioGainDb),
                desqueeze = prefs.getFloat("desqueeze", v.desqueeze),
                bakeDesqueeze = prefs.getBoolean("bakeDesqueeze", v.bakeDesqueeze),
                displayLut = prefs.getBoolean("displayLut", v.displayLut),
                recordLut = prefs.getBoolean("recordLut", v.recordLut),
                lutId = prefs.getString("lutId", v.lutId),
                engine = prefs.getBoolean("engine", v.engine),
                look = enumOr(prefs.getString("look", null), v.look),
                headroomEv = prefs.getFloat("headroom", v.headroomEv),
                grain = prefs.getBoolean("grain", v.grain),
                localTone = prefs.getFloat("localTone", v.localTone),
            ),
            slowMoFps = prefs.getInt("slowMoFps", d.slowMoFps),
            hyperlapse = enumOr(prefs.getString("hyperlapse", null), d.hyperlapse),
            dualLayout = enumOr(prefs.getString("dualLayout", null), d.dualLayout),
            dualSeparateFiles = prefs.getBoolean("dualSeparate", d.dualSeparateFiles),
            multiComboKey = prefs.getString("multiCombo", d.multiComboKey),
            multiSeparateFiles = prefs.getBoolean("multiSeparate", d.multiSeparateFiles),
            peaking = prefs.getBoolean("peaking", d.peaking),
            peakColor = enumOr(prefs.getString("peakColor", null), d.peakColor),
            peakSensitivity = enumOr(prefs.getString("peakSens", null), d.peakSensitivity),
            zebraIre = prefs.getInt("zebra", d.zebraIre),
            falseColor = prefs.getBoolean("falseColor", d.falseColor),
            scope = enumOr(prefs.getString("scope", null), d.scope),
            levelGauge = prefs.getBoolean("levelGauge", d.levelGauge),
            guide = enumOr(prefs.getString("guide", null), d.guide),
            hudExpanded = prefs.getBoolean("hud", d.hudExpanded),
            storageTree = prefs.getString("storageTree", d.storageTree),
            portraitFStop = prefs.getFloat("fstop", d.portraitFStop),
            portraitStyle = enumOr(prefs.getString("bokeh", null), d.portraitStyle),
            foodRadius = prefs.getFloat("foodRadius", d.foodRadius),
            foodBlur = prefs.getFloat("foodBlur", d.foodBlur),
        )
    }

    private fun save(s: ProSettings) {
        val v = s.video
        prefs.edit()
            .putInt("w", v.size.width).putInt("h", v.size.height).putInt("fps", v.fps)
            .putString("codec", v.codec.name).putString("profile", v.profile.name).putString("apvQuality", v.apvQuality.name)
            .putFloat("bpp", v.bitsPerPixel).putString("stab", v.stabilization.name).putBoolean("audio", v.audio)
            .putString("audioSource", v.audioSource.name).putFloat("gain", v.audioGainDb).putFloat("desqueeze", v.desqueeze)
            .putBoolean("bakeDesqueeze", v.bakeDesqueeze).putBoolean("displayLut", v.displayLut).putBoolean("recordLut", v.recordLut)
            .putString("lutId", v.lutId)
            .putBoolean("engine", s.video.engine)
            .putString("look", s.video.look.name)
            .putFloat("headroom", s.video.headroomEv)
            .putBoolean("grain", s.video.grain)
            .putFloat("localTone", s.video.localTone)
            .putInt("slowMoFps", s.slowMoFps).putString("hyperlapse", s.hyperlapse.name).putString("dualLayout", s.dualLayout.name)
            .putBoolean("dualSeparate", s.dualSeparateFiles).putString("multiCombo", s.multiComboKey).putBoolean("multiSeparate", s.multiSeparateFiles)
            .putBoolean("peaking", s.peaking).putString("peakColor", s.peakColor.name).putString("peakSens", s.peakSensitivity.name)
            .putInt("zebra", s.zebraIre).putBoolean("falseColor", s.falseColor).putString("scope", s.scope.name)
            .putBoolean("levelGauge", s.levelGauge).putString("guide", s.guide.name).putBoolean("hud", s.hudExpanded)
            .putString("storageTree", s.storageTree).putFloat("fstop", s.portraitFStop).putString("bokeh", s.portraitStyle.name)
            .putFloat("foodRadius", s.foodRadius).putFloat("foodBlur", s.foodBlur)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
