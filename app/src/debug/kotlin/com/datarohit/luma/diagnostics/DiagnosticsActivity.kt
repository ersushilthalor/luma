package com.datarohit.luma.diagnostics

import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.datarohit.luma.core.camera.diagnostics.AppLevelDiagnostics
import com.datarohit.luma.core.camera.diagnostics.CaptureBehaviorProbe
import com.datarohit.luma.core.camera.inventory.CameraInventoryProbe
import com.datarohit.luma.core.camera.inventory.CameraInventoryReport
import com.datarohit.luma.ui.theme.LumaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Debug-only hardware validation runner (docs/HARDWARE_VALIDATION_PLAN.md).
 *
 * `adb shell am start -n com.datarohit.luma/.diagnostics.DiagnosticsActivity` runs Phase A (no camera
 * opened). Adding `--ez phaseB true` also runs the Phase B measurement subset on camera 0, which keeps
 * frames in memory only. Reports go to the app's private `files/diagnostics/` directory.
 */
class DiagnosticsActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LumaTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), contentPadding = PaddingValues(16.dp)) {
                        items(lines) { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra(EXTRA_UHR, false)) lifecycleScope.launch {
                runCatching { UhrProbe(getSystemService(CameraManager::class.java), ::log).run() }.onFailure { log("FAILED $it") }
                log("UHR_DONE")
            }
            else if (intent.getBooleanExtra(EXTRA_ISZ, false)) lifecycleScope.launch {
                runCatching { InSensorZoomProbe(getSystemService(CameraManager::class.java), ::log).run("0") }.onFailure { log("FAILED $it") }
                log("ISZ_DONE")
            }
            else if (intent.getBooleanExtra(EXTRA_QCFA, false)) lifecycleScope.launch {
                runCatching { QcfaProbe(getSystemService(CameraManager::class.java), ::log).run("0") }.onFailure { log("FAILED $it") }
                log("QCFA_DONE")
            }
            else if (intent.getBooleanExtra(EXTRA_VENDOR, false)) {
                StreamProbe(getSystemService(CameraManager::class.java), ::log).vendorKeys(listOf("0", "2", "5", "6", "7"))
                log("VENDOR_DONE")
            }
            else if (intent.getBooleanExtra(EXTRA_ISP, false)) lifecycleScope.launch {
                runCatching { StreamProbe(getSystemService(CameraManager::class.java), ::log).runIsp(listOf("0", "2")) }.onFailure { log("FAILED $it") }
                log("ISP_DONE")
            }
            else if (intent.getBooleanExtra(EXTRA_HIRES, false)) lifecycleScope.launch {
                runCatching { StreamProbe(getSystemService(CameraManager::class.java), ::log).captureHires(listOf("0", "2"), java.io.File(filesDir, "diagnostics/hires")) }
                    .onFailure { log("FAILED $it") }
                log("HIRES_DONE")
            }
            else if (intent.getBooleanExtra(EXTRA_PROBE, false)) lifecycleScope.launch {
                runCatching { StreamProbe(getSystemService(CameraManager::class.java), ::log).run(listOf("0", "2")) }.onFailure { log("FAILED $it") }
                log("PROBE_DONE")
            }
            else if (intent.getBooleanExtra(EXTRA_MEDIA, false)) verifyMedia(intent.getIntExtra(EXTRA_COUNT, 8))
            else if (intent.getBooleanExtra(EXTRA_ICON, false)) renderLauncherIcon()
            else if (intent.getBooleanExtra(EXTRA_CAPS, false)) runCaps()
            else if (intent.getBooleanExtra(EXTRA_CAPTURE_TEST, false)) runCaptureTest()
            else run(phaseB = intent.getBooleanExtra(EXTRA_PHASE_B, false))
        }
    }

    private fun runCaptureTest() = lifecycleScope.launch {
        val manager = getSystemService(CameraManager::class.java)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
        try {
            log("Capture test: full pipeline, output stays in app-private storage")
            val dir = File(filesDir, "diagnostics/captures/$stamp")
            val runner = CaptureTestRunner(manager, dir, ::log)
            val report = if (intent.getBooleanExtra(EXTRA_LENS_PROBE, false)) runner.lensProbe()
                else runner.run(superRes = intent.getBooleanExtra(EXTRA_SUPER_RES, false))
            write("capture-test-$stamp.json", report.toString(2))
            log("CAPTURE_TEST_DONE capture-test-$stamp.json")
        } catch (e: Exception) {
            log("FAILED $e")
        } finally {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Renders Luma's icon exactly as launchers obtain it (LauncherApps activity icon) into a PNG, and
     * reports how many pixels are non-transparent, so a blank icon is detectable without touching the launcher.
     */
    private fun renderLauncherIcon() = lifecycleScope.launch {
        try {
            val apps = getSystemService(android.content.pm.LauncherApps::class.java)
            val activity = apps.getActivityList(packageName, android.os.Process.myUserHandle()).first()
            val drawable = activity.getIcon(resources.displayMetrics.densityDpi)
            val size = 432
            val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val px = IntArray(size * size).also { bmp.getPixels(it, 0, size, 0, 0, size, size) }
            val opaque = px.count { (it ushr 24) > 16 }
            val distinct = px.toSet().size
            withContext(Dispatchers.IO) {
                File(filesDir, "diagnostics").apply { mkdirs() }.resolve("launcher-icon.png").outputStream().use {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            log("ICON_DONE class=${drawable.javaClass.simpleName} opaque=$opaque/${size * size} colours=$distinct label=${activity.label}")
            // Also render the raw resource and each adaptive layer, to locate a blank layer.
            fun save(name: String, d: android.graphics.drawable.Drawable?) {
                if (d == null) return
                val b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(android.graphics.Canvas(b))
                val p2 = IntArray(size * size).also { b.getPixels(it, 0, size, 0, 0, size, size) }
                val avg = p2.map { ((it shr 16) and 0xFF) + ((it shr 8) and 0xFF) + (it and 0xFF) }.average() / 3
                log("ICON_LAYER $name class=${d.javaClass.simpleName} meanRGB=${"%.0f".format(avg)} opaque=${p2.count { (it ushr 24) > 16 }}")
                File(filesDir, "diagnostics/icon-$name.png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val raw = resources.getDrawable(com.datarohit.luma.R.mipmap.ic_launcher_legacy, theme)
            save("raw", raw)
            save("pm", packageManager.getApplicationIcon(packageName))
            (raw as? android.graphics.drawable.AdaptiveIconDrawable)?.let { a ->
                save("background", a.background)
                save("foreground", a.foreground)
                save("monochrome", a.monochrome)
            }
        } catch (e: Exception) {
            log("FAILED $e")
        }
    }

    /**
     * Opens Luma's newest MediaStore items the way a player would: images are fully decoded; videos are
     * demuxed track by track (format, duration, sample count, largest video frame gap, audio/video start
     * offset). Read-only, Luma's own files only.
     */
    private fun verifyMedia(count: Int) = lifecycleScope.launch {
        try {
            val items = withContext(Dispatchers.IO) { com.datarohit.luma.core.storage.LumaMediaRepository(this@DiagnosticsActivity).list().take(count) }
            for (item in items) {
                val line = withContext(Dispatchers.IO) {
                    runCatching {
                        if (item.isVideo) describeVideo(item.uri) else {
                            val src = android.graphics.ImageDecoder.createSource(contentResolver, item.uri)
                            val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { d, _, _ -> d.setTargetSampleSize(4) }
                            "image ${item.width}x${item.height} decoded ${bmp.width}x${bmp.height} ok"
                        }
                    }.getOrElse { "ERROR ${it.message}" }
                }
                log("MEDIA ${item.name} ${item.size}B $line")
            }
            log("MEDIA_DONE ${items.size}")
        } catch (e: Exception) {
            log("FAILED $e")
        }
    }

    private fun describeVideo(uri: android.net.Uri): String {
        val ex = android.media.MediaExtractor()
        ex.setDataSource(this, uri, null)
        val parts = mutableListOf<String>()
        val starts = mutableMapOf<String, Long>()
        for (t in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(t)
            val mime = f.getString(android.media.MediaFormat.KEY_MIME) ?: "?"
            ex.selectTrack(t)
            var n = 0
            var first = -1L
            var last = -1L
            var maxGap = 0L
            val buf = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val ts = ex.sampleTime
                if (first < 0) first = ts
                if (last >= 0 && mime.startsWith("video/")) maxGap = maxOf(maxGap, ts - last)
                last = ts
                n++
                ex.advance()
            }
            ex.unselectTrack(t)
            starts[mime.substringBefore('/')] = first
            val extra = if (mime.startsWith("video/")) "${f.getInteger(android.media.MediaFormat.KEY_WIDTH)}x${f.getInteger(android.media.MediaFormat.KEY_HEIGHT)} " +
                "maxGap=${maxGap / 1000}ms" else "${f.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)}Hz ch=${f.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)}"
            parts += "$mime samples=$n dur=${(last - first) / 1000}ms $extra"
        }
        ex.release()
        val offset = if ("video" in starts && "audio" in starts) " avStartOffset=${(starts["audio"]!! - starts["video"]!!) / 1000}ms" else ""
        return parts.joinToString(" | ") + offset
    }

    /** Capability inventory (no camera opened): cameras, concurrent sets, dynamic range, high speed, encoders. */
    private fun runCaps() = lifecycleScope.launch {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
        try {
            val json = withContext(Dispatchers.Default) {
                com.datarohit.luma.device.DeviceCapabilities(getSystemService(CameraManager::class.java)).toJson()
            }
            write("caps-$stamp.json", json.toString(2))
            log("CAPS_DONE caps-$stamp.json")
        } catch (e: Exception) {
            log("FAILED $e")
        }
    }

    private fun run(phaseB: Boolean) = lifecycleScope.launch {
        val manager = getSystemService(CameraManager::class.java)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
        try {
            log("Phase A: app-level inventory (no camera opened)")
            val phaseA = withContext(Dispatchers.Default) { AppLevelDiagnostics(manager).collect() }
            val inventory = withContext(Dispatchers.Default) { CameraInventoryReport.render(CameraInventoryProbe(manager).collect()) }
            write("phase-a-$stamp.json", phaseA.toString(2))
            write("inventory-$stamp.txt", inventory)
            log("PHASE_A_DONE phase-a-$stamp.json")

            if (phaseB) {
                log("Phase B: measurement subset on camera 0 (frames are not stored)")
                val phaseBReport: JSONObject = CaptureBehaviorProbe(manager, ::log).run("0")
                write("phase-b-$stamp.json", phaseBReport.toString(2))
                log("PHASE_B_DONE phase-b-$stamp.json")
            }
        } catch (e: Exception) {
            log("FAILED $e")
        } finally {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private suspend fun write(name: String, content: String) = withContext(Dispatchers.IO) {
        File(filesDir, "diagnostics").apply { mkdirs() }.resolve(name).writeText(content)
    }

    private fun log(message: String) {
        Log.i(TAG, message)
        runOnUiThread { lines += message }
    }

    companion object {
        const val EXTRA_CAPS = "caps"
        const val EXTRA_MEDIA = "media"
        const val EXTRA_PROBE = "probe"
        const val EXTRA_HIRES = "hires"
        const val EXTRA_ISP = "isp"
        const val EXTRA_VENDOR = "vendor"
        const val EXTRA_QCFA = "qcfa"
        const val EXTRA_ISZ = "isz"
        const val EXTRA_UHR = "uhr"
        const val EXTRA_SUPER_RES = "superRes"
        const val EXTRA_COUNT = "count"
        const val EXTRA_ICON = "icon"
        const val EXTRA_PHASE_B = "phaseB"
        const val EXTRA_CAPTURE_TEST = "captureTest"
        const val EXTRA_LENS_PROBE = "lensProbe"
        private const val TAG = "LumaDiagnostics"
    }
}
