package com.datarohit.luma.video

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Video encoder settings. Colour keys are set only for HDR. */
data class EncoderSpec(
    val mime: String,
    val codecName: String?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val profile: Int? = null,
    val colorStandard: Int? = null,
    val colorTransfer: Int? = null,
    /** Sensor capture rate when it differs from playback (slow motion). */
    val captureRate: Int? = null,
)

data class AudioSpec(val source: AudioSourceOption, val gainDb: Float, val sampleRate: Int = 48_000, val channels: Int = 2)

/** dBFS levels per channel (-90 = silence). */
data class AudioLevels(val peakL: Float = -90f, val peakR: Float = -90f, val rmsL: Float = -90f, val rmsR: Float = -90f, val clipped: Boolean = false)

data class RecordingResult(val uri: Uri, val displayName: String, val durationMs: Long, val videoFrames: Int, val hasAudio: Boolean)

/**
 * One MP4 file: a surface-input video encoder, an optional AAC track from AudioRecord, and MediaMuxer.
 * Timestamps come from the camera (sensor clock); audio is placed on the same clock.
 */
class Recorder(
    private val context: Context,
    private val target: VideoTarget,
    private val video: EncoderSpec,
    private val audio: AudioSpec?,
    private val mapping: TimeMapping,
    orientationHint: Int,
    /** True when camera timestamps are CLOCK_BOOTTIME (SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME). */
    private val clockBoottime: Boolean,
    persistentSurface: Surface? = null,
) {
    private val thread = HandlerThread("luma-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec = if (video.codecName != null) MediaCodec.createByCodecName(video.codecName) else MediaCodec.createEncoderByType(video.mime)
    private val muxer = MediaMuxer(target.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val lock = Any()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val pending = ArrayList<Triple<Boolean, ByteBuffer, MediaCodec.BufferInfo>>()
    @Volatile private var firstVideoUs = -1L
    // Both tracks start together: video is held until the microphone delivers, then starts on a fresh
    // keyframe requested at that moment (no shutter delay, no silent lead-in).
    @Volatile private var audioReady = false
    private var gateStartUs = -1L
    private var syncRequested = false
    private var lastVideoOutUs = -1L
    private var lastAudioOutUs = -1L
    private var videoFrames = 0
    private val videoEos = CompletableDeferred<Unit>()
    @Volatile private var failure: Throwable? = null
    @Volatile private var audioRunning = false
    private var audioThread: Thread? = null
    private var audioWritten = 0

    private val _levels = MutableStateFlow(AudioLevels())
    val levels: StateFlow<AudioLevels> = _levels.asStateFlow()

    val inputSurface: Surface

    init {
        val format = MediaFormat.createVideoFormat(video.mime, video.width, video.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, video.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, video.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            video.profile?.let { setInteger(MediaFormat.KEY_PROFILE, it) }
            video.colorStandard?.let {
                setInteger(MediaFormat.KEY_COLOR_STANDARD, it)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            video.colorTransfer?.let { setInteger(MediaFormat.KEY_COLOR_TRANSFER, it) }
            video.captureRate?.let {
                setInteger(MediaFormat.KEY_CAPTURE_RATE, it)
                setInteger(MediaFormat.KEY_OPERATING_RATE, it)
            }
        }
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        val buf = codec.getOutputBuffer(index)
                        if (buf != null) writeVideo(buf, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) videoEos.complete(Unit)
                } catch (e: Exception) {
                    fail(e)
                }
            }

            override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = fail(e)

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                synchronized(lock) {
                    videoTrack = muxer.addTrack(format)
                    maybeStartMuxer()
                }
            }
        }, handler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = if (persistentSurface != null) {
            codec.setInputSurface(persistentSurface)
            persistentSurface
        } else {
            codec.createInputSurface()
        }
        muxer.setOrientationHint(orientationHint)
    }

    fun start() {
        codec.start()
        if (audio != null) startAudio(audio)
    }

    /** Stops encoding and publishes the file. Call after the frame source stopped feeding [inputSurface]. */
    suspend fun stop(): RecordingResult = withContext(Dispatchers.IO) {
        audioRunning = false
        audioThread?.join(3_000)
        runCatching { codec.signalEndOfInputStream() }
        withTimeoutOrNull(4_000) { videoEos.await() }
        runCatching { codec.stop() }
        codec.release()
        val started = synchronized(lock) { muxerStarted }
        val ok = started && videoFrames > 0 && failure == null
        try {
            if (started) muxer.stop()
        } catch (e: Exception) {
            Log.w(TAG, "muxer stop", e)
        } finally {
            runCatching { muxer.release() }
            thread.quitSafely()
        }
        if (!ok) {
            target.abort()
            throw IllegalStateException(failure?.message ?: "No video frames were recorded")
        }
        val uri = target.finish()
        val durationMs = max(0L, lastVideoOutUs) / 1000
        RecordingResult(uri, target.displayName, durationMs, videoFrames, audioWritten > 0)
    }

    /** Releases everything without publishing (error paths). */
    fun abort() {
        audioRunning = false
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { muxer.release() }
        target.abort()
        thread.quitSafely()
    }

    // ------------------------------------------------------------------------------------------

    private fun fail(e: Throwable) {
        Log.e(TAG, "recorder failure", e)
        failure = e
        videoEos.complete(Unit)
    }

    private fun maybeStartMuxer() {
        if (muxerStarted || videoTrack < 0 || (audio != null && audioTrack < 0)) return
        muxer.start()
        muxerStarted = true
        for ((isVideo, data, info) in pending) muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, data, info)
        pending.clear()
    }

    private fun requestSyncFrame() {
        if (syncRequested) return
        syncRequested = true
        runCatching { codec.setParameters(android.os.Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    private fun writeVideo(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (firstVideoUs < 0) {
                if (audio != null && !audioReady) {
                    if (gateStartUs < 0) gateStartUs = info.presentationTimeUs
                    // Give the microphone up to AUDIO_WAIT_US; without audio the clip starts anyway.
                    if (info.presentationTimeUs - gateStartUs < AUDIO_WAIT_US) return
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME == 0) {
                    requestSyncFrame()
                    return
                }
                firstVideoUs = info.presentationTimeUs
            }
            var out = ((info.presentationTimeUs - firstVideoUs) * mapping.timeScale).toLong()
            if (out <= lastVideoOutUs) out = lastVideoOutUs + 1
            lastVideoOutUs = out
            val copyInfo = MediaCodec.BufferInfo().apply { set(0, info.size, out, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()) }
            buf.position(info.offset).limit(info.offset + info.size)
            if (muxerStarted) muxer.writeSampleData(videoTrack, buf, copyInfo)
            else pending += Triple(true, ByteBuffer.allocateDirect(info.size).put(buf).flip() as ByteBuffer, copyInfo)
            videoFrames++
        }
    }

    private fun writeAudio(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val first = firstVideoUs
            if (first < 0) {
                if (!audioReady) {
                    audioReady = true
                    requestSyncFrame()
                }
                return
            }
            var out = info.presentationTimeUs - first
            if (out < 0) return
            if (out <= lastAudioOutUs) out = lastAudioOutUs + 1
            lastAudioOutUs = out
            val copyInfo = MediaCodec.BufferInfo().apply { set(0, info.size, out, 0) }
            buf.position(info.offset).limit(info.offset + info.size)
            if (muxerStarted) muxer.writeSampleData(audioTrack, buf, copyInfo)
            else pending += Triple(false, ByteBuffer.allocateDirect(info.size).put(buf).flip() as ByteBuffer, copyInfo)
            audioWritten++
        }
    }

    private fun clockNowNs(): Long = if (clockBoottime) SystemClock.elapsedRealtimeNanos() else System.nanoTime()

    @SuppressLint("MissingPermission")
    private fun startAudio(spec: AudioSpec) {
        val channelMask = if (spec.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(spec.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val source = when (spec.source) {
            AudioSourceOption.CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            AudioSourceOption.MIC, AudioSourceOption.EXTERNAL -> MediaRecorder.AudioSource.MIC
            AudioSourceOption.UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        }
        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(spec.sampleRate).setChannelMask(channelMask).build())
            .setBufferSizeInBytes(max(minBuf * 4, 16_384))
            .build()
        if (spec.source == AudioSourceOption.EXTERNAL) {
            val am = context.getSystemService(AudioManager::class.java)
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in EXTERNAL_TYPES }?.let { record.setPreferredDevice(it) }
        }
        val aac = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, spec.sampleRate, spec.channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        aac.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        aac.start()
        record.startRecording()
        audioRunning = true
        val gain = 10f.pow(spec.gainDb / 20f)
        audioThread = Thread({
            val frameChunk = 1024
            val pcm = ShortArray(frameChunk * spec.channels)
            val ts = AudioTimestamp()
            val timebase = if (clockBoottime) AudioTimestamp.TIMEBASE_BOOTTIME else AudioTimestamp.TIMEBASE_MONOTONIC
            var framesRead = 0L
            var startNs = -1L
            var lastLevel = 0L
            val info = MediaCodec.BufferInfo()
            try {
                while (audioRunning) {
                    val n = record.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    val frames = n / spec.channels
                    if (startNs < 0) {
                        startNs = if (record.getTimestamp(ts, timebase) == AudioRecord.SUCCESS) {
                            ts.nanoTime - ts.framePosition * 1_000_000_000L / spec.sampleRate
                        } else {
                            clockNowNs() - frames * 1_000_000_000L / spec.sampleRate
                        }
                    }
                    // Gain + meters.
                    var peakL = 0; var peakR = 0; var sumL = 0.0; var sumR = 0.0; var clipped = false
                    for (i in 0 until n) {
                        var v = (pcm[i] * gain).toInt()
                        if (v > Short.MAX_VALUE) { v = Short.MAX_VALUE.toInt(); clipped = true }
                        if (v < Short.MIN_VALUE) { v = Short.MIN_VALUE.toInt(); clipped = true }
                        pcm[i] = v.toShort()
                        val a = kotlin.math.abs(v)
                        if (spec.channels == 1 || i % 2 == 0) { if (a > peakL) peakL = a; sumL += v.toDouble() * v } else { if (a > peakR) peakR = a; sumR += v.toDouble() * v }
                    }
                    val now = SystemClock.uptimeMillis()
                    if (now - lastLevel > 60) {
                        lastLevel = now
                        val perCh = (n / spec.channels).coerceAtLeast(1)
                        val rR = if (spec.channels == 1) sumL else sumR
                        val pR = if (spec.channels == 1) peakL else peakR
                        _levels.value = AudioLevels(db(peakL.toDouble()), db(pR.toDouble()), db(sqrt(sumL / perCh)), db(sqrt(rR / perCh)), clipped)
                    }
                    val ptsUs = (startNs + framesRead * 1_000_000_000L / spec.sampleRate) / 1000
                    framesRead += frames
                    val inIndex = aac.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = aac.getInputBuffer(inIndex)!!
                        inBuf.clear()
                        val bytes = n * 2
                        val bb = ByteBuffer.allocate(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        bb.asShortBuffer().put(pcm, 0, n)
                        inBuf.put(bb.array(), 0, minOf(bytes, inBuf.remaining()))
                        aac.queueInputBuffer(inIndex, 0, minOf(bytes, inBuf.capacity()), ptsUs, 0)
                    }
                    drainAudio(aac, info, false)
                }
                val inIndex = aac.dequeueInputBuffer(10_000)
                if (inIndex >= 0) aac.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drainAudio(aac, info, true)
            } catch (e: Exception) {
                Log.w(TAG, "audio", e)
            } finally {
                runCatching { record.stop() }
                record.release()
                runCatching { aac.stop() }
                aac.release()
            }
        }, "luma-audio").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun drainAudio(aac: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean) {
        val deadline = SystemClock.uptimeMillis() + if (untilEos) 1_500 else 0
        while (true) {
            val index = aac.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    audioTrack = muxer.addTrack(aac.outputFormat)
                    maybeStartMuxer()
                }
                index >= 0 -> {
                    val buf = aac.getOutputBuffer(index)
                    if (buf != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) writeAudio(buf, info)
                    aac.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!untilEos || SystemClock.uptimeMillis() > deadline) return
            }
        }
    }

    private fun db(amplitude: Double): Float = if (amplitude <= 1.0) -90f else (20 * log10(amplitude / 32768.0)).toFloat().coerceAtLeast(-90f)

    private companion object {
        private const val AUDIO_WAIT_US = 1_500_000L
        const val TAG = "LumaRecorder"
        val EXTERNAL_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }
}
