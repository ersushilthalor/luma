package com.datarohit.luma.settings

import android.content.Context
import android.content.SharedPreferences
import com.datarohit.luma.core.imaging.Look
import com.datarohit.luma.core.imaging.OutputSpace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AspectRatio(val label: String, val widthOverHeight: Float) {
    RATIO_4_3("4:3", 4f / 3f), RATIO_16_9("16:9", 16f / 9f), RATIO_1_1("1:1", 1f)
}

enum class PhotoFormat(val label: String) { JPEG("JPEG"), HEIF("HEIF") }

/** Photo output tiers. Whether a tier is native, downsampled or upscaled is decided per lens (ResolutionCatalog). */
enum class PhotoResolution(val label: String, val megapixels: Int) { MP12("12MP", 12), MP24("24MP", 24), MP50("50MP", 50), MP200("200MP", 200) }

data class AppSettings(
    val saveDng: Boolean = false,
    val jpegQuality: Int = 95,
    val look: Look = Look.NATURAL,
    val outputSpace: OutputSpace = OutputSpace.DISPLAY_P3,
    val aspect: AspectRatio = AspectRatio.RATIO_4_3,
    val grid: Boolean = false,
    val level: Boolean = true,
    val shutterSound: Boolean = true,
    val haptics: Boolean = true,
    val closeUpHints: Boolean = true,
    val proMultiFrame: Boolean = false,
    val sharpness: Float = 0.5f,
    val noiseReduction: Float = 0.5f,
    val localTone: Float = 0.6f,
    val timerSeconds: Int = 0,
    val histogram: Boolean = false,
    val photoFormat: PhotoFormat = PhotoFormat.JPEG,
    val photoResolution: PhotoResolution = PhotoResolution.MP12,
    /** Switch to the macro-capable ultra-wide when the subject is closer than the main lens can focus. */
    val focusEnhancer: Boolean = true,
)

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("luma_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        _settings.value = next
        save(next)
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            saveDng = prefs.getBoolean("saveDng", d.saveDng),
            jpegQuality = prefs.getInt("jpegQuality", d.jpegQuality),
            look = enumOr(prefs.getString("look", null), d.look),
            outputSpace = enumOr(prefs.getString("outputSpace", null), d.outputSpace),
            aspect = enumOr(prefs.getString("aspect", null), d.aspect),
            grid = prefs.getBoolean("grid", d.grid),
            level = prefs.getBoolean("level", d.level),
            shutterSound = prefs.getBoolean("shutterSound", d.shutterSound),
            haptics = prefs.getBoolean("haptics", d.haptics),
            closeUpHints = prefs.getBoolean("closeUpHints", d.closeUpHints),
            proMultiFrame = prefs.getBoolean("proMultiFrame", d.proMultiFrame),
            sharpness = prefs.getFloat("sharpness", d.sharpness),
            noiseReduction = prefs.getFloat("noiseReduction", d.noiseReduction),
            localTone = prefs.getFloat("localTone", d.localTone),
            timerSeconds = prefs.getInt("timerSeconds", d.timerSeconds),
            histogram = prefs.getBoolean("histogram", d.histogram),
            photoFormat = enumOr(prefs.getString("photoFormat", null), d.photoFormat),
            photoResolution = enumOr(prefs.getString("photoResolution", null), d.photoResolution),
            focusEnhancer = prefs.getBoolean("focusEnhancer", d.focusEnhancer),
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putBoolean("saveDng", s.saveDng)
            .putInt("jpegQuality", s.jpegQuality)
            .putString("look", s.look.name)
            .putString("outputSpace", s.outputSpace.name)
            .putString("aspect", s.aspect.name)
            .putBoolean("grid", s.grid)
            .putBoolean("level", s.level)
            .putBoolean("shutterSound", s.shutterSound)
            .putBoolean("haptics", s.haptics)
            .putBoolean("closeUpHints", s.closeUpHints)
            .putBoolean("proMultiFrame", s.proMultiFrame)
            .putFloat("sharpness", s.sharpness)
            .putFloat("noiseReduction", s.noiseReduction)
            .putFloat("localTone", s.localTone)
            .putInt("timerSeconds", s.timerSeconds)
            .putBoolean("histogram", s.histogram)
            .putString("photoFormat", s.photoFormat.name)
            .putString("photoResolution", s.photoResolution.name)
            .putBoolean("focusEnhancer", s.focusEnhancer)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
}
