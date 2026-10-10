package com.shilapi.xcertplay.media

import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.os.Build
import java.util.concurrent.Executor

/** Session metadata and amplitude totals only; no speech is retained. */
internal class MicrophoneCaptureDiagnostics(private val report: (String) -> Unit) {
    private var monitoredRecorder: AudioRecord? = null
    private var callback: AudioManager.AudioRecordingCallback? = null
    private val earlySignal = EarlyMicrophoneSignalStatistics()

    @Synchronized
    fun beforeStart(recorder: AudioRecord) {
        safely {
            earlySignal.start(System.nanoTime())
            if (Build.VERSION.SDK_INT >= 29) {
                val listener = object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                        synchronized(this@MicrophoneCaptureDiagnostics) {
                            if (monitoredRecorder === recorder) {
                                safely { snapshot(recorder, "configuration changed") }
                            }
                        }
                    }
                }
                monitoredRecorder = recorder
                callback = listener
                recorder.registerAudioRecordingCallback(Executor { it.run() }, listener)
            }
            snapshot(recorder, "before start")
        }
    }

    @Synchronized
    fun snapshot(recorder: AudioRecord, reason: String) {
        // Route diagnostics work on API 28 too, even when recording configuration is absent.
        safely {
            val route = recorder.routedDevice
            emit("Microphone route reason=$reason session=${recorder.audioSessionId} deviceType=${route?.type} deviceId=${route?.id}")
        }
        if (Build.VERSION.SDK_INT >= 28) safely {
            try {
                val microphones = recorder.activeMicrophones
                val mappings = microphones.joinToString("|") { mic ->
                    microphoneMappingLabel(mic.id, mic.group, mic.indexInTheGroup,
                        mic.channelMapping.map { it.first to it.second })
                }
                emit("Microphone active mapping reason=$reason session=${recorder.audioSessionId} count=${microphones.size} microphones=[$mappings]")
            } catch (error: Exception) {
                emit("Microphone active mapping reason=$reason session=${recorder.audioSessionId} unavailable=${error.javaClass.simpleName}")
            }
        }
        safely {
            if (Build.VERSION.SDK_INT < 29) {
                emit("Microphone capture state reason=$reason session=${recorder.audioSessionId} api29Metadata=false")
                return@safely
            }
            val config = recorder.activeRecordingConfiguration
            if (config == null) {
                emit("Microphone capture state reason=$reason session=${recorder.audioSessionId} activeConfiguration=none")
                return@safely
            }
            val device = config.audioDevice
            fun format(value: android.media.AudioFormat) =
                "${value.sampleRate}Hz/${value.channelCount}ch/encoding${value.encoding}"
            fun effects(values: List<android.media.audiofx.AudioEffect.Descriptor>) =
                values.joinToString("|") { it.name.replace('\n', ' ').replace('\r', ' ') }
            emit("Microphone capture state reason=$reason session=${recorder.audioSessionId} " +
                "silenced=${config.isClientSilenced} deviceType=${device?.type} deviceId=${device?.id} " +
                "clientFormat=${format(config.clientFormat)} deviceFormat=${format(config.format)} " +
                "clientEffects=[${effects(config.clientEffects)}] activeEffects=[${effects(config.effects)}]")
        }
    }

    fun observe(bytes: ByteArray, count: Int) = safely {
        earlySignal.observe(bytes, count, System.nanoTime())?.let(::emit)
    }

    @Synchronized
    fun detach(recorder: AudioRecord?) {
        if (recorder == null || monitoredRecorder !== recorder) return
        val listener = callback
        monitoredRecorder = null
        callback = null
        if (Build.VERSION.SDK_INT >= 29 && listener != null) {
            safely { recorder.unregisterAudioRecordingCallback(listener) }
        }
    }

    private fun emit(message: String) = safely { report(message) }
    private inline fun safely(action: () -> Unit) {
        try { action() } catch (_: Throwable) { /* Diagnostics cannot interrupt capture. */ }
    }
}

/** At most twelve startup reports, with one pass over each PCM buffer and no sample allocations. */
internal class EarlyMicrophoneSignalStatistics {
    private var startNs: Long? = null
    private var lastReportNs = 0L
    private var reports = 0
    private var samples = 0L
    private var zeroSamples = 0L
    private var squared = 0L
    private var peak = 0
    private var firstNonzeroMs: Long? = null

    fun start(nowNs: Long) {
        if (startNs != null) return
        startNs = nowNs
        lastReportNs = nowNs
    }

    fun observe(bytes: ByteArray, count: Int, nowNs: Long): String? {
        val started = startNs ?: return null
        if (reports >= 12 || nowNs - started > WINDOW_NS) return null
        require(count in 0..bytes.size)
        for (offset in 0 until count - 1 step 2) {
            val value = ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
            samples++
            if (value == 0) zeroSamples++ else if (firstNonzeroMs == null) {
                firstNonzeroMs = (nowNs - started).coerceAtLeast(0) / 1_000_000
            }
            squared += value.toLong() * value
            peak = maxOf(peak, kotlin.math.abs(value))
        }
        if (nowNs - lastReportNs < REPORT_NS) return null
        reports++
        val rms = if (samples == 0L) 0 else kotlin.math.sqrt(squared.toDouble() / samples).toInt()
        val zeroPermille = if (samples == 0L) 0 else zeroSamples * 1000 / samples
        val line = "Microphone startup signal elapsedMs=${(nowNs - started) / 1_000_000} " +
            "samples=$samples peak=$peak rms=$rms zeroPermille=$zeroPermille firstNonzeroMs=${firstNonzeroMs ?: "none"}"
        lastReportNs = nowNs
        samples = 0; zeroSamples = 0; squared = 0; peak = 0
        return line
    }

    private companion object {
        const val REPORT_NS = 250_000_000L
        const val WINDOW_NS = 3_000_000_000L
    }
}

/** No hardware address or inferred seat name belongs in the microphone mapping log. */
internal fun microphoneMappingLabel(id: Int, group: Int, index: Int,
    channels: List<Pair<Int, Int>>): String {
    return "id=$id group=$group index=$index channels=[${channels.joinToString(",") { "${it.first}:${it.second}" }}]"
}
