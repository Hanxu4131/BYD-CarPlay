package com.shilapi.xcertplay.media

import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.MicrophoneCounters
import com.shilapi.xcertplay.airplay.MicrophonePacketizer
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures one PCM microphone stream and sends it back to the phone as sealed CarPlay RTP.
 *
 * The recorder runs only while the matching audio stream is active, so callers start this after
 * the first downlink audio packet and close it on stream teardown.
 */
internal class MicrophoneUplink(
    private val config: MicrophoneConfig,
    private val processing: MicrophoneProcessing = MicrophoneProcessing(),
    private val diagnostic: (String) -> Unit = {},
    private val onClosed: () -> Unit = {},
) : Closeable {
    private val running = AtomicBoolean(false)
    private val firstPacketLogged = AtomicBoolean(false)
    private val closedNotified = AtomicBoolean(false)
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var opusEncoder: OpusEncoder? = null
    private val effects = VoiceEffects<AudioEffect>(
        configure = { effect, enabled ->
            effect.setEnabled(enabled) == AudioEffect.SUCCESS && effect.enabled == enabled
        },
        release = { it.release() },
        report = { message, error ->
            if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error)
            diagnostic(message)
        },
    )
    private val captureDiagnostics = MicrophoneCaptureDiagnostics(diagnostic)
    private var thread: Thread? = null
    private var effectsRequested = false
    private val capturePolicy = MicrophoneCapturePolicy.from(processing)

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val channelMask = if (config.channels >= 2) {
            AndroidAudioFormat.CHANNEL_IN_STEREO
        } else {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelMask,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "microphone unavailable rate=${config.sampleRate} channels=${config.channels}")
            running.set(false)
            return false
        }

        val source = if (capturePolicy.useMicSource) MediaRecorder.AudioSource.MIC else when (config.audioType) {
            "telephony" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            "speechrecognition" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            else -> MediaRecorder.AudioSource.MIC
        }
        val nextEncoder = if (config.codec == AudioCodecKind.OPUS) {
            OpusEncoder(config.bitrate ?: 48_000).takeIf { it.available }
        } else {
            null
        }
        if (config.codec == AudioCodecKind.OPUS && nextEncoder == null) {
            Log.w(TAG, "microphone Opus encoder is unavailable")
            running.set(false)
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, config.frameBytes * 4)
        val nextRecorder = try {
            createRecorder(source, channelMask, bufferSize)
        } catch (error: Exception) {
            Log.e(TAG, "microphone recorder creation failed", error)
            nextEncoder?.close()
            running.set(false)
            return false
        }
        if (nextRecorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "microphone recorder failed to initialize")
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        val nextSocket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("::"), 0))
            }
        } catch (error: Exception) {
            Log.e(TAG, "microphone socket creation failed", error)
            nextRecorder.release()
            nextEncoder?.close()
            running.set(false)
            return false
        }

        recorder = nextRecorder
        socket = nextSocket
        opusEncoder = nextEncoder
        return try {
            // VOICE_COMMUNICATION can enable effects by default. Configure false explicitly too,
            // so the switch controls the session's Android AEC/NS rather than only our requests.
            var aecConfigured = true
            var nsConfigured = true
            if (capturePolicy.configureSystemEffects) {
                aecConfigured = effects.add("AEC", capturePolicy.echoCancellation) {
                    if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(nextRecorder.audioSessionId) else null
                }
                nsConfigured = effects.add("NS", capturePolicy.noiseSuppression) {
                    if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(nextRecorder.audioSessionId) else null
                }
            } else {
                diagnostic("Microphone effects bypassed reason=device compatibility source=MIC; system effect handles not created")
            }
            effectsRequested = capturePolicy.echoCancellation || capturePolicy.noiseSuppression
            var captureRecorder = nextRecorder
            if ((capturePolicy.echoCancellation && !aecConfigured) || (capturePolicy.noiseSuppression && !nsConfigured)) {
                effectsRequested = false
                diagnostic("Microphone effects fallback reason=enable failed; processing disabled for this stream")
                captureRecorder = restartWithoutEffects(nextRecorder, channelMask, bufferSize)
                    ?: throw IllegalStateException("Microphone effects fallback recorder unavailable")
            } else {
                captureDiagnostics.beforeStart(nextRecorder)
                nextRecorder.startRecording()
                captureDiagnostics.snapshot(nextRecorder, "started")
            }
            diagnostic("Microphone started type=${config.audioType} codec=${config.codec} rate=${config.sampleRate} channels=${config.channels} frameBytes=${config.frameBytes} frameMs=${config.frameMillis} nsRequested=${processing.noiseSuppression} aecRequested=${processing.echoCancellation} systemEffectsAllowed=${processing.systemEffectsAllowed} effectsRequested=$effectsRequested")
            thread = Thread({ capture(captureRecorder, nextSocket, channelMask, bufferSize) }, "carplay-mic").apply {
                isDaemon = true
                start()
            }
            Log.i(
                TAG,
                "microphone uplink started type=${config.audioType} " +
                    "rate=${config.sampleRate} channels=${config.channels} " +
                    "frameMs=${config.frameMillis} port=${config.port}",
            )
            true
        } catch (error: Exception) {
            Log.e(TAG, "microphone recording failed", error)
            release()
            false
        }
    }

    private fun createRecorder(source: Int, channelMask: Int, bufferSize: Int): AudioRecord =
        AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRate)
                        .setChannelMask(channelMask)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()

    private fun capture(initialRecorder: AudioRecord, socket: DatagramSocket, channelMask: Int, bufferSize: Int) {
        var currentRecorder = initialRecorder
        var frames = MicrophonePcmFrames(config.frameBytes)
        val signal = MicrophoneSignalHealth(config.sampleRate * config.channels)
        // READ_BLOCKING waits for the requested byte count. A 2048-byte minimum makes
        // 8/16 kHz input wait for multiple packets, then sends those packets in a burst.
        val readBuffer = ByteArray(frames.readSize)
        val counters = MicrophoneCounters()
        var reads = 0L
        var sentFrames = 0L
        var maxReadNs = 0L
        var lastReportNs = System.nanoTime()
        try {
            while (running.get()) {
                val beforeReadNs = System.nanoTime()
                val count = currentRecorder.read(readBuffer, 0, readBuffer.size, AudioRecord.READ_BLOCKING)
                maxReadNs = maxOf(maxReadNs, System.nanoTime() - beforeReadNs)
                reads++
                captureDiagnostics.observe(readBuffer, count.coerceAtLeast(0))
                if (count < 0) {
                    if (running.get()) {
                        Log.e(TAG, "microphone read failed code=$count")
                        diagnostic("Microphone read failed code=$count type=${config.audioType}")
                    }
                    if (effectsRequested && running.get()) {
                        diagnostic("Microphone effects fallback reason=read failed code=$count")
                        currentRecorder = restartWithoutEffects(currentRecorder, channelMask, bufferSize) ?: return
                        effectsRequested = false
                        frames = MicrophonePcmFrames(config.frameBytes)
                        continue
                    }
                    return
                }
                if (count == 0) {
                    val nowNs = System.nanoTime()
                    if (nowNs - lastReportNs >= 5_000_000_000L) {
                        diagnostic("Microphone stats type=${config.audioType} reads=$reads frames=$sentFrames maxReadMs=${maxReadNs / 1_000_000} readBytes=0 ${signal.summary()} effectsRequested=$effectsRequested")
                        lastReportNs = nowNs; reads = 0; sentFrames = 0; maxReadNs = 0
                    }
                    continue
                }
                if (signal.observe(readBuffer, count) && effectsRequested && running.get()) {
                    diagnostic("Microphone effects fallback reason=continuous zero PCM ${signal.summary()}")
                    currentRecorder = restartWithoutEffects(currentRecorder, channelMask, bufferSize) ?: return
                    effectsRequested = false
                    frames = MicrophonePcmFrames(config.frameBytes)
                    continue
                }
                frames.append(readBuffer, count) { frame ->
                    if (running.get()) { sendFrame(socket, counters, frame); sentFrames++ }
                }
                val nowNs = System.nanoTime()
                if (nowNs - lastReportNs >= 5_000_000_000L) {
                    diagnostic("Microphone stats type=${config.audioType} reads=$reads frames=$sentFrames maxReadMs=${maxReadNs / 1_000_000} ${signal.summary()} effectsRequested=$effectsRequested")
                    lastReportNs = nowNs; reads = 0; sentFrames = 0; maxReadNs = 0
                }
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "microphone capture failed", error)
                diagnostic("Microphone capture failed type=${config.audioType} error=${error.javaClass.simpleName}")
            }
        } finally {
            running.set(false)
            release()
        }
    }

    @Synchronized
    private fun restartWithoutEffects(previous: AudioRecord, channelMask: Int, bufferSize: Int): AudioRecord? {
        if (!running.get() || recorder !== previous) return null
        captureDiagnostics.snapshot(previous, "before fallback")
        captureDiagnostics.detach(previous)
        effects.disableAll()
        effects.close()
        recorder = null
        try { previous.stop() } catch (_: Exception) { }
        previous.release()
        if (!running.get()) return null
        val replacement = createRecorder(MediaRecorder.AudioSource.MIC, channelMask, bufferSize)
        if (replacement.state != AudioRecord.STATE_INITIALIZED || !running.get()) {
            replacement.release()
            return null
        }
        recorder = replacement
        // Explicitly disable session effects; releasing handles alone can restore vendor defaults.
        val aecDisabled = effects.add("AEC", false) {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(replacement.audioSessionId) else null
        }
        val nsDisabled = effects.add("NS", false) {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(replacement.audioSessionId) else null
        }
        captureDiagnostics.beforeStart(replacement)
        replacement.startRecording()
        captureDiagnostics.snapshot(replacement, "fallback started")
        diagnostic("Microphone recorder restarted source=MIC nsRequested=false aecRequested=false nsDisableConfirmed=$nsDisabled aecDisableConfirmed=$aecDisabled attempts=1")
        return replacement
    }

    private fun sendFrame(socket: DatagramSocket, counters: MicrophoneCounters, frame: ByteArray) {
        val bodies = if (config.codec == AudioCodecKind.OPUS) {
            opusEncoder?.encode(frame).orEmpty()
        } else {
            listOf(MicrophonePacketizer.toWirePcm(frame))
        }
        bodies.forEach { body ->
            sendPacket(
                socket = socket,
                counters = counters,
                body = body,
                samples = config.samplesPerPacket,
            )
        }
    }

    private fun sendPacket(
        socket: DatagramSocket,
        counters: MicrophoneCounters,
        body: ByteArray,
        samples: Int,
    ) {
        val packet = MicrophonePacketizer.sealPacket(
            key = config.key,
            payloadType = config.payloadType,
            counters = counters,
            body = body,
            samples = samples,
        )
        try {
            socket.send(DatagramPacket(packet, packet.size, config.host, config.port))
            if (firstPacketLogged.compareAndSet(false, true)) {
                Log.i(
                    TAG,
                    "microphone first packet type=${config.audioType} bytes=${packet.size} body=${body.size}",
                )
            }
        } catch (error: Exception) {
            if (running.get()) throw error
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) {
            release()
            return
        }
        try {
            recorder?.stop()
        } catch (_: Exception) {
            // Best effort; release below is authoritative.
        }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        thread?.let { worker ->
            try {
                worker.join(CLOSE_JOIN_MILLIS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (worker.isAlive) worker.interrupt()
        }
        release()
    }

    @Synchronized
    private fun release() {
        running.set(false)
        effects.close()
        val currentRecorder = recorder
        captureDiagnostics.detach(currentRecorder)
        recorder = null
        try {
            currentRecorder?.release()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentSocket = socket
        socket = null
        try {
            currentSocket?.close()
        } catch (_: Exception) {
            // Best effort.
        }
        val currentEncoder = opusEncoder
        opusEncoder = null
        try {
            currentEncoder?.close()
        } finally {
            if (closedNotified.compareAndSet(false, true)) {
                diagnostic("Microphone stopped type=${config.audioType}")
                onClosed()
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val CLOSE_JOIN_MILLIS = 500L
    }
}
