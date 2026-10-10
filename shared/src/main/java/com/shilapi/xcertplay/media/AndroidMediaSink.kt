package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.MainAreaViewport
import com.shilapi.xcertplay.airplay.MainAreaSelection
import com.shilapi.xcertplay.airplay.toHexString
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributes, var appliedVolume: Float = FULL_VOLUME)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequest? = null
    private var requestedChannel: AudioChannel? = null
    private var mediaVolume = FULL_VOLUME
    private var closed = false
    private var focusGeneration = 0L
    private var currentListener = listenerFor(focusGeneration)
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            // Android may have queued callbacks before a request was abandoned or replaced.
            if (generation != focusGeneration || request == null || active.isEmpty()) return@synchronized
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (muteMediaOnTransientLoss) {
                        // This reports a temporary Android focus owner, not a confirmed call.
                        Log.i(TAG, "Audio: muting media during transient focus loss")
                        setMediaVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributes) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
        applyMediaVolume(track, active.getValue(track))
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        active.clear()
        refreshRequest()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            val abandoned = request
            request = null
            requestedChannel = null
            mediaVolume = FULL_VOLUME
            abandoned?.let { manager?.abandonAudioFocusRequest(it) }
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        focusGeneration += 1
        request?.let { manager?.abandonAudioFocusRequest(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(primary.attributes)
            .setOnAudioFocusChangeListener(currentListener, Handler(Looper.getMainLooper()))
            .build()
        request = next
        requestedChannel = primary.channel
        val result = manager?.requestAudioFocus(next)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) setMediaVolume(FULL_VOLUME)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setMediaVolume(volume: Float) {
        mediaVolume = volume
        active.forEach { (track, entry) -> applyMediaVolume(track, entry) }
    }

    private fun applyMediaVolume(track: AudioTrack, entry: Entry) {
        // Telephony and assistant speech remain audible, and navigation never enters this map.
        // This changes only the renderer's relative gain, never Android's user stream volume.
        if (entry.channel != AudioChannel.MEDIA || entry.appliedVolume == mediaVolume) return
        val applied = runCatching { track.setStereoVolume(mediaVolume, mediaVolume) == AudioTrack.SUCCESS }.getOrDefault(false)
        if (applied) entry.appliedVolume = mediaVolume
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val audioFocusAutoYield: Boolean = true,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    val adaptiveSelection: MainAreaSelection? = null,
    private val microphoneProcessing: () -> MicrophoneProcessing = { MicrophoneProcessing() },
) : MediaSink {
    private companion object {
        const val MAIN_SCREEN_TYPE = 110
    }

    override val adaptiveMainViewportEnabled: Boolean get() = adaptiveSelection != null
    @Volatile private var viewportChanged: (() -> Unit)? = null
    @Volatile private var mainGeometry: MainAreaViewport? = null
    private var lastAdaptiveGeometryLog: String? = null
    fun setViewportChangedListener(listener: (() -> Unit)?) { viewportChanged = listener; listener?.invoke() }
    @Synchronized fun clearViewportChangedListener(listener: (() -> Unit)?) {
        if (viewportChanged === listener) viewportChanged = null
    }
    override fun onVideoGeometry(type: Int, codec: VideoCodec, geometry: MainAreaViewport?) {
        if (type != MAIN_SCREEN_TYPE || adaptiveSelection == null) return
        if (codec != VideoCodec.H264) mainGeometry = null
        else geometry?.takeIf { it.valid() && it.codedWidth <= adaptiveSelection.canvasWidth && it.codedHeight <= adaptiveSelection.canvasHeight }?.let { mainGeometry = it }
        val matched = adaptiveSelection.receive(codec, geometry)
        val line = "Adaptive H264: geometry=$geometry matched=$matched; ${if (matched) "viewport confirmed" else "keeping fit"}"
        if (line != lastAdaptiveGeometryLog) {
            lastAdaptiveGeometryLog = line
            Log.i("DiPlay-AdaptiveDisplay", line)
            onAudioDiagnostic(line)
        }
        viewportChanged?.invoke()
    }

    private val appContext = context?.applicationContext
    private val audioManager = appContext?.getSystemService(AudioManager::class.java)
    private val communicationAudioMode = audioManager?.let { manager ->
        CommunicationAudioMode<MicrophoneUplink>(
            communicationMode = AudioManager.MODE_IN_COMMUNICATION,
            readMode = { manager.mode },
            writeMode = { manager.mode = it },
            onFailure = { Log.w("xcertplay-usb", "could not change call audio mode", it) },
        )
    }
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        audioFocusAutoYield,
        onAudioDiagnostic,
    )
    /** The media-key session may own Android's current focus request for this same sink. */
    fun onMediaAudioFocusChanged(change: Int) {
        audioFocusCoordinator.onExternalFocusChange(change)
    }

    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private val defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    private val ambientSinkToken = AmbientMusicController.openSink(appContext)
    private val microphoneExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-microphone-lifecycle").apply { isDaemon = true }
    }
    private val microphoneUplinks = QueuedMicrophoneStreams<AudioStreamId, MicrophoneUplink>(
        microphoneExecutor,
        start = { it.start() },
        release = { uplink ->
            try { uplink.close() } finally { communicationAudioMode?.release(uplink) }
        },
        report = { error ->
            Log.e("xcertplay-usb", "microphone lifecycle failed", error)
            onAudioDiagnostic("Microphone lifecycle failed error=${error.javaClass.simpleName}")
        },
    )
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val mirrorFrameListeners = HashMap<Pair<Int, String>, () -> Unit>()
    private val mirrorPresentedListeners = HashMap<Pair<Int, String>, () -> Unit>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    private val recoveryDispatch = VideoRecoveryDispatch(
        recoveryExecutor,
        request = { type -> videoRecoveryHandlers[type]?.invoke() },
        onFailure = { error -> Log.w("xcertplay-usb", "Video keyframe request failed", error) },
    )

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        recoveryDispatch.request(type)
    }

    fun setSurface(type: Int, surface: Surface) {
        val conflictingTypes = VideoSurfaceRouting.otherTypesUsing(type, surface, surfaces)
        if (type == MAIN_SCREEN_TYPE) {
            conflictingTypes.forEach { otherType ->
                surfaces.remove(otherType, surface)
                videoDecoders[otherType]?.setSurface(null)
            }
        } else if (conflictingTypes.isNotEmpty()) {
            Log.w("xcertplay-usb", "video surface rejected for stream=$type because another stream already owns it")
            return
        }
        surfaces[type] = surface
        videoDecoders[type]?.setSurface(surface)
    }

    fun clearSurface(type: Int, surface: Surface) {
        if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?, onFirstFrameRendered: (() -> Unit)? = null, onFirstFramePresented: (() -> Unit)? = null) {
        val id = type to key
        synchronized(mirrorLock) {
            mirrorDecoders.remove(id)?.close()
            if (surface == null) {
                mirrorSurfaces.remove(id)
                mirrorFrameListeners.remove(id)
                mirrorPresentedListeners.remove(id)
                return
            }
            mirrorSurfaces[id] = surface
            if (onFirstFrameRendered == null) mirrorFrameListeners.remove(id)
            else mirrorFrameListeners[id] = onFirstFrameRendered
            if (onFirstFramePresented == null) mirrorPresentedListeners.remove(id)
            else mirrorPresentedListeners[id] = onFirstFramePresented
        }
        lastVideoConfig[type]?.let { (codec, data) ->
            val geometry = if (type == MAIN_SCREEN_TYPE && adaptiveSelection != null && codec == VideoCodec.H264) mainGeometry else null
            mirrorDecoders(type).forEach { it.configure(codec, data, geometry?.codedWidth, geometry?.codedHeight) }
        }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(mirrorLock) {
        if (mirrorSurfaces.isEmpty()) return emptyList()
        mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
            mirrorDecoders.getOrPut(id) {
                lateinit var mirror: VideoDecoder
                mirror = newVideoDecoder(type, surface, " stream=$type mirror=${id.second}",
                    onFirstFrameRendered = {
                        synchronized(mirrorLock) {
                            if (mirrorDecoders[id] === mirror && mirrorSurfaces[id] === surface) mirrorFrameListeners[id]?.invoke()
                        }
                    },
                    onFirstFramePresented = {
                        synchronized(mirrorLock) {
                            if (mirrorDecoders[id] === mirror && mirrorSurfaces[id] === surface) mirrorPresentedListeners[id]?.invoke()
                        }
                    })
                mirror
            }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        pendingVideoCodec[type] = codec
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        val codec = pendingVideoCodec[type] ?: VideoCodec.H264
        lastVideoConfig[type] = codec to codecData
        val geometry = if (type == MAIN_SCREEN_TYPE && adaptiveSelection != null && codec == VideoCodec.H264) mainGeometry else null
        videoDecoder(type).configure(codec, codecData, geometry?.codedWidth, geometry?.codedHeight)
        mirrorDecoders(type).forEach { it.configure(codec, codecData, geometry?.codedWidth, geometry?.codedHeight) }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        videoDecoder(type).submit(naluBytes)
        mirrorDecoders(type).forEach { it.submit(naluBytes) }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        if (!active) {
            if (type == MAIN_SCREEN_TYPE) { adaptiveSelection?.reset(); mainGeometry = null; viewportChanged?.invoke() }
            videoRecoveryHandlers.remove(type)
            videoDiagnosticHandlers.remove(type)
            videoDecoders.remove(type)?.close()
            synchronized(mirrorLock) {
                mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
            }
            lastVideoConfig.remove(type)
            pendingVideoCodec.remove(type)
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        audioRenderers.remove(id)?.close()
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        microphoneUplinks.start(id) {
            lateinit var created: MicrophoneUplink
            created = MicrophoneUplink(config, processing = microphoneProcessing(),
                diagnostic = onAudioDiagnostic, onClosed = {
                    if (config.audioType == "telephony") communicationAudioMode?.release(created)
                })
            if (config.audioType == "telephony") communicationAudioMode?.acquire(created)
            created
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        microphoneUplinks.stop(id)
    }

    fun hasPriorityVoiceAudio(): Boolean = audioRenderers.values.any { it.priorityVoiceActive() }


    fun close() {
        AmbientMusicController.closeSink(ambientSinkToken)
        adaptiveSelection?.reset()
        viewportChanged = null
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoDecoders.values.forEach(VideoDecoder::close)
        videoDecoders.clear()
        synchronized(mirrorLock) {
            mirrorDecoders.values.forEach(VideoDecoder::close)
            mirrorDecoders.clear()
            mirrorSurfaces.clear()
            mirrorFrameListeners.clear()
            mirrorPresentedListeners.clear()
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        // Audio workers close asynchronously; none may reacquire focus after their sink closes.
        audioFocusCoordinator.close()
        audioRenderers.values.forEach(AudioRenderer::close)
        audioRenderers.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            microphoneUplinks.close()
        } finally {
            microphoneExecutor.shutdown()
            communicationAudioMode?.close()
        }
    }

    private fun videoDecoder(type: Int): VideoDecoder =
        videoDecoders.computeIfAbsent(type) {
            newVideoDecoder(type, surfaces[type] ?: VideoSurfaceRouting.defaultFor(type, defaultSurface))
        }

    private fun newVideoDecoder(
        type: Int, surface: Surface?, statsLabel: String? = null,
        onFirstFrameRendered: (() -> Unit)? = null,
        onFirstFramePresented: (() -> Unit)? = null,
    ) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
        onFirstFrameRendered = onFirstFrameRendered,
        onFirstFramePresented = onFirstFramePresented,
    )

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            ambientSinkToken,
        ).also { audioRenderers[id] = it }
    }
}

/** Prevents independent CarPlay screen decoders from connecting to the same BufferQueue. */
internal object VideoSurfaceRouting {
    fun <T> defaultFor(type: Int, defaultSurface: T?): T? =
        if (type == 110) defaultSurface else null

    fun otherTypesUsing(type: Int, surface: Any, assigned: Map<Int, *>): List<Int> =
        assigned.filter { (otherType, otherSurface) -> otherType != type && otherSurface === surface }.keys.toList()
}

/** Either the platform presentation callback or two released picture buffers can confirm readiness. */
internal class VideoFrameReadiness(
    private val current: () -> Boolean,
    private val notify: (String) -> Unit,
) {
    private val completed = AtomicBoolean(false)
    private val outputs = AtomicInteger()

    fun outputReleased(): Boolean = outputs.incrementAndGet() == 2 && !completed.get()
    fun codecRendered() = confirm("codec_callback")
    fun decodedOutputsReady() = confirm("decoded_output")

    private fun confirm(source: String) {
        if (current() && completed.compareAndSet(false, true)) notify(source)
    }
}

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    private val onFirstFrameRendered: (() -> Unit)? = null,
    private val onFirstFramePresented: (() -> Unit)? = null,
) : Closeable {
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    @Volatile private var outputSurface: Surface? = surface
    private var lastConfig: VideoJob.Config? = null
    private var renderedFrameLogged = false
    private var submittedFrameLogged = false
    private var frameReadiness: VideoFrameReadiness? = null
    @Volatile private var firstPresentedNotified = false
    private val readinessHandler = Handler(Looper.getMainLooper())
    private var duplicateConfigLogged = false
    private val referenceChain = VideoReferenceChain()
    private val backlogRecovery = VideoBacklogRecovery()
    private val codecRetry = VideoCodecRetryGate()
    private val decoderCandidates: List<VideoDecoderCandidate> by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) emptyList()
        else runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isAlias }.map {
                VideoDecoderCandidate(it.name, it.supportedTypes.toList(), it.isEncoder,
                    it.isHardwareAccelerated, it.isSoftwareOnly)
            }
        }.getOrElse {
            Log.w(TAG, "video decoder enumeration unavailable; retaining compatibility selection", it)
            emptyList()
        }
    }
    private val warmRecovery = VideoWarmRecovery()
    private val frameTiming = VideoFrameTiming()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == 110) "" else " stream=$streamType")
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true; start() }

    fun configure(codec: VideoCodec, codecData: ByteArray, codedWidth: Int? = null, codedHeight: Int? = null) {
        queue.offer(VideoJob.Config(codec, codecData, codedWidth, codedHeight))
    }

    fun submit(nalus: ByteArray) {
        stats.onReceived(nalus.size)
        queue.offer(VideoJob.Frame(nalus))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            val backlog = queue.backlogAfterCurrent()
                            // Keep the reference chain through short CPU/radio stalls. Rebuilding
                            // on one late frame can itself keep the next frames permanently late.
                            val overloaded = !referenceChain.needsKeyFrame && backlogRecovery.observe(
                                System.nanoTime(), job.receivedNs,
                                backlog.pendingFrames, backlog.newestPendingReceivedNs,
                            )
                            if (overloaded) {
                                queue.recordStaleRecovery()
                                recover("video backlog kept growing or exceeded hard limit", queueRecovery = true)
                            } else feed(job.nalus, job.receivedNs)
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.Resync -> recover("video queue overflow", queueRecovery = true)
                        null -> Unit
                    }
                    decoder?.let(::drainOutput)
                    if (warmRecovery.timedOut(System.nanoTime())) {
                        recover("warm decoder recovery produced no picture")
                    }
                    stats.logIfDue()?.let { report(it + " " + queue.takeDiagnostics()) }
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    if (running) Log.e(TAG, "video decoder job failed: ${job?.javaClass?.simpleName}", error)
                    val resourceUnavailable = error is MediaCodec.CodecException &&
                        VideoCodecFailurePolicy.resourceUnavailable(error.errorCode)
                    if (resourceUnavailable) {
                        val delayMs = codecRetry.failed(System.nanoTime())
                        if (running) report("decoder resource unavailable code=${(error as MediaCodec.CodecException).errorCode}; retryAfterMs=$delayMs")
                    } else {
                        val delayMs = if (decoder != null && codecRetry.awaitingPicture) codecRetry.failed(System.nanoTime()) else 0L
                        if (running) report("decoder error ${error.javaClass.simpleName}; waiting for keyframe retryAfterMs=$delayMs")
                    }
                    releaseDecoder(resourceUnavailable)
                    queue.discardCurrentChain()
                    referenceChain.reset()
                    requestKeyFrameIfDue()
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            releaseDecoder()
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.sameDecoderConfig(config, width, height) == true
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        lastConfig = config
        if (!codecRetry.canRetry(System.nanoTime())) return
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        val plan = VideoDecoderSelection.plan(mime, decoderCandidates, preferSoftwareHevcDecoder)
        if (plan.hardwareOnly) {
            Log.i(TAG, "video decoder hardware-only candidates=${plan.attempts.mapNotNull { it.codecName }.distinct()} mime=$mime")
        }
        var next: MediaCodec? = null
        for (attempt in plan.attempts) {
            next = tryConfigure(mime, csd, surface, attempt)
            if (next != null) break
        }
        if (next == null) {
            queue.discardCurrentChain()
            val delayMs = codecRetry.failed(System.nanoTime())
            report("decoder configuration failed mime=$mime size=${config.codedWidth ?: width}x${config.codedHeight ?: height}; hardwareOnly=${plan.hardwareOnly} softwareFallback=${!plan.hardwareOnly} retryAfterMs=$delayMs")
        }
        decoder = next
        renderedFrameLogged = false
        submittedFrameLogged = false
        if (next != null) {
            val acceleration = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    "hardware=${next.codecInfo.isHardwareAccelerated} software=${next.codecInfo.isSoftwareOnly}"
                }.getOrDefault("hardware=unknown software=unknown")
            } else "hardware=unknown software=unknown"
            report("decoder=${next.name} mime=$mime size=${config.codedWidth ?: width}x${config.codedHeight ?: height} $acceleration")
            Log.i(
                TAG,
                "video decoder configured name=${next.name} mime=$mime size=${config.codedWidth ?: width}x${config.codedHeight ?: height}",
            )
        }
    }

    private fun buildFormat(mime: String, csd: List<ByteArray>, tuned: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(mime, lastConfig?.codedWidth ?: width, lastConfig?.codedHeight ?: height).apply {
            if (tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: VideoDecoderAttempt,
    ): MediaCodec? {
        var candidate: MediaCodec? = null
        var codecName = attempt.codecName ?: "default"
        var stage = "create"
        var stageStartNs = System.nanoTime()
        val attemptStartNs = stageStartNs
        var createMs = 0L
        var configureMs = 0L
        return try {
            val format = buildFormat(mime, csd, attempt.tuned)
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            createMs = (System.nanoTime() - stageStartNs) / 1_000_000
            codecName = runCatching { codec.name }.getOrDefault(codecName)
            stage = "configure"
            stageStartNs = System.nanoTime()
            if (attempt.tuned && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec.configure(format, surface, null, 0)
            configureMs = (System.nanoTime() - stageStartNs) / 1_000_000
            stage = "listeners"
            stageStartNs = System.nanoTime()
            if (onFirstFrameRendered != null) {
                val readiness = VideoFrameReadiness(
                    current = { running && decoder === codec && outputSurface === surface && surface.isValid },
                    notify = { source ->
                        Log.w(TAG, "mirror first frame readiness=$source")
                        onFirstFrameRendered?.invoke()
                    },
                )
                frameReadiness = readiness
            }
            listenForPresentedFrames(codec, surface)
            stage = "start"
            stageStartNs = System.nanoTime()
            codec.start()
            Log.i(TAG, "video decoder startup name=$codecName tuned=${attempt.tuned} " +
                "createMs=$createMs configureMs=$configureMs startMs=${(System.nanoTime() - stageStartNs) / 1_000_000}")
            codec
        } catch (error: Exception) {
            val failedStageMs = (System.nanoTime() - stageStartNs) / 1_000_000
            val elapsedMs = (System.nanoTime() - attemptStartNs) / 1_000_000
            val codecError = if (error is MediaCodec.CodecException) {
                " code=${error.errorCode} recoverable=${error.isRecoverable} " +
                    "transient=${error.isTransient} diagnostic=${error.diagnosticInfo}"
            } else ""
            Log.w(
                TAG,
                "video decoder configure failed name=$codecName stage=$stage stageMs=$failedStageMs " +
                    "createMs=$createMs configureMs=$configureMs elapsedMs=$elapsedMs " +
                    "tuned=${attempt.tuned} mime=$mime " +
                    "size=${lastConfig?.codedWidth ?: width}x${lastConfig?.codedHeight ?: height}$codecError",
                error,
            )
            runCatching { candidate?.release() }.onFailure {
                Log.w(TAG, "video decoder candidate release failed name=$codecName", it)
            }
            // Continue only within the selected plan. Resource loss returns to the
            // worker cooldown instead of immediately reallocating another codec.
            if (error is MediaCodec.CodecException &&
                VideoCodecFailurePolicy.resourceUnavailable(error.errorCode)) throw error
            null
        }
    }

    private fun createDecoder(mime: String): MediaCodec {
        if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC && preferSoftwareHevcDecoder) {
            val software = decoderCandidates.firstOrNull {
                !it.encoder && it.software && mime in it.supportedTypes
            }
            if (software != null) {
                try {
                    return MediaCodec.createByCodecName(software.name)
                } catch (error: Exception) {
                    Log.w(TAG, "software HEVC decoder unavailable name=${software.name}", error)
                }
            }
        }
        return MediaCodec.createDecoderByType(mime)
    }

    private fun changeSurface(surface: Surface?) {
        if (outputSurface === surface) return
        backlogRecovery.reset()
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            queue.discardCurrentChain()
            referenceChain.reset()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        val codec = decoder
        if (codec != null) {
            try {
                // Invalidate the old surface's pending timestamps before changing its output.
                frameTiming.reset()
                codec.setOutputSurface(surface)
                listenForPresentedFrames(codec, surface)
                Log.i(TAG, "video decoder output surface updated")
                return
            } catch (error: Exception) {
                if (error is MediaCodec.CodecException &&
                    VideoCodecFailurePolicy.resourceUnavailable(error.errorCode)) throw error
                Log.w(TAG, "video decoder output surface update failed; reconfiguring", error)
            }
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray, receivedNs: Long) {
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (decoder == null && !codecRetry.canRetry(System.nanoTime())) return
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        if (!referenceChain.accepts(annexB, config.codec)) {
            requestKeyFrameIfDue()
            return
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        // A slow rebuild may make its triggering IDR obsolete. Keep the new codec,
        // but wait for a fresh reference chain instead of immediately rebuilding it again.
        if (referenceChain.needsKeyFrame && VideoRecoveryFrameAge.isObsolete(
                System.nanoTime(), receivedNs, queue.backlogAfterCurrent(),
            )) {
            queue.discardCurrentChain()
            backlogRecovery.reset()
            queue.recordStaleRecovery()
            Log.w(TAG, "video keyframe became stale while waiting for decoder; requesting fresh keyframe")
            requestKeyFrameIfDue()
            return
        }
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size} " +
                    "head=${annexB.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }}",
            )
        }
        val index = VideoInputPump.acquire(
            running = { running }, drain = { drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) { recover("video decoder input stalled"); return }
        val input = checkNotNull(codec.getInputBuffer(index)) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            val submittedNs = System.nanoTime()
            val ptsUs = submittedNs / 1000
            frameTiming.submitted(ptsUs, receivedNs, submittedNs)?.let(stats::onSubmitted)
            codec.queueInputBuffer(index, 0, annexB.size, ptsUs, 0)
            referenceChain.onQueued()
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        drainOutput(codec)
    }

    private fun recover(reason: String, queueRecovery: Boolean = false) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        val codec = decoder
        val surface = outputSurface
        if (codec != null && surface != null && warmRecovery.canFlush(
                queueRecovery, surface.isValid, lastConfig != null, !codecRetry.awaitingPicture,
            )) {
            try {
                // Only a codec with picture output may keep its CSD through flush.
                // This decoder uses synchronous dequeue; it does not need start() after flush.
                frameTiming.reset()
                codec.flush()
                warmRecovery.flushed(System.nanoTime())
                backlogRecovery.reset()
                queue.discardCurrentChain()
                referenceChain.reset()
                // Preserve completed mirror readiness, but bind timing to a fresh epoch.
                listenForPresentedFrames(codec, surface, preserveFirstPresented = true)
                report("warm decoder recovery flushed; waiting for fresh keyframe")
                requestKeyFrameIfDue()
                return
            } catch (error: Exception) {
                if (error is MediaCodec.CodecException &&
                    VideoCodecFailurePolicy.resourceUnavailable(error.errorCode)) throw error
                Log.w(TAG, "video warm recovery failed; rebuilding decoder", error)
                report("warm decoder recovery failed ${error.javaClass.simpleName}; rebuilding")
            }
        }
        if (decoder != null && codecRetry.awaitingPicture) {
            val delayMs = codecRetry.failed(System.nanoTime())
            report("decoder recovery produced no picture; retryAfterMs=$delayMs")
        }
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (!codecRetry.canRetry(now)) return
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
                index >= 0 -> {
                    val render = outputSurface != null
                    val picture = info.flags and (MediaCodec.BUFFER_FLAG_CODEC_CONFIG or
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
                    val outputAges = if (render && picture) {
                        frameTiming.released(info.presentationTimeUs, System.nanoTime())
                    } else null
                    codec.releaseOutputBuffer(index, render)
                    if (render && picture) {
                        codecRetry.pictureDecoded()
                        warmRecovery.pictureDecoded()
                    }
                    if (render && picture) frameReadiness?.let { readiness ->
                        if (readiness.outputReleased()) {
                            // Some car decoders never emit OnFrameRendered. Confirm submitted picture
                            // output after a short Surface settling interval, only for this decoder/surface.
                            readinessHandler.postDelayed({ readiness.decodedOutputsReady() }, 80L)
                        }
                    }
                    if (render) {
                        stats.onRendered()
                        outputAges?.let(stats::onReleasedAge)
                    }
                    if (render && !renderedFrameLogged) {
                        renderedFrameLogged = true
                        report("first frame rendered")
                        Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun listenForPresentedFrames(codec: MediaCodec, surface: Surface, preserveFirstPresented: Boolean = false) {
        val epoch = frameTiming.reset()
        val readiness = frameReadiness
        if (!preserveFirstPresented) firstPresentedNotified = false
        runCatching {
            codec.setOnFrameRenderedListener({ renderedCodec, ptsUs, renderedNs ->
                if (running && renderedCodec === codec && decoder === codec &&
                    outputSurface === surface && surface.isValid && frameTiming.isCurrent(epoch)) {
                    frameTiming.presented(epoch, ptsUs, renderedNs)?.let(stats::onPresented)
                    readiness?.codecRendered()
                    if (!firstPresentedNotified) {
                        firstPresentedNotified = true
                        onFirstFramePresented?.invoke()
                    }
                }
            }, readinessHandler)
        }.onFailure { Log.w(TAG, "video frame presentation listener unavailable", it) }
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    @Synchronized
    private fun releaseDecoder(resourceUnavailable: Boolean = false) {
        backlogRecovery.reset()
        warmRecovery.reset()
        frameTiming.reset()
        frameReadiness = null
        val codec = decoder
        decoder = null
        if (codec != null) {
            val name = runCatching { codec.name }.getOrDefault("unknown")
            if (!resourceUnavailable) {
                try {
                    codec.stop()
                } catch (error: Exception) {
                    Log.w(TAG, "video decoder stop failed name=$name", error)
                }
            }
            try {
                codec.release()
            } catch (error: Exception) {
                Log.w(TAG, "video decoder release failed name=$name", error)
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        const val INPUT_TIMEOUT_US = 10_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

/** Use the existing hard backlog age only when a newer frame remains in this control segment. */
internal object VideoRecoveryFrameAge {
    fun isObsolete(nowNs: Long, receivedNs: Long, backlog: VideoDecodeQueue.Backlog): Boolean =
        nowNs >= receivedNs && nowNs - receivedNs >= 1_500_000_000L &&
            backlog.pendingFrames > 0 && backlog.newestPendingReceivedNs?.let {
                it <= nowNs && it - receivedNs >= 100_000_000L
            } == true
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** A failure-safe snapshot of AudioTrack playback parameters for low-frequency diagnostics. */
internal data class PlaybackParamsSnapshot(val speed: Float?, val pitch: Float?) {
    companion object {
        fun capture(read: () -> Pair<Float, Float>): PlaybackParamsSnapshot =
            runCatching(read).fold(
                onSuccess = { PlaybackParamsSnapshot(it.first, it.second) },
                onFailure = { PlaybackParamsSnapshot(null, null) },
            )
    }

    fun logFields(): String =
        "playbackSpeed=${speed ?: "unavailable"} playbackPitch=${pitch ?: "unavailable"}"
}

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val ambientSinkToken: Long,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int, val arrivalNanos: Long)

    private var trackAttributes: AudioAttributes? = null
    private var mappedChannel: AudioChannel? = null
    private val navigationPlaybackToken = NavigationPlayback.open()
    private val voicePlaybackActivity = VoicePlaybackActivity()
    private var actualLegacyStreamType: Int? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var pcm = ByteArray(64 * 1024)
    private val codecOutputInfo = MediaCodec.BufferInfo()
    @Volatile private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var aacOutputFormatLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var inputRetried = 0L
    private val pendingInput = PendingAudioInput()
    private var inputWaitTimeouts = 0L
    private var inputWaitExpired = 0L
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private val ambientEnvelope = AmbientMusicEnvelope()
    private val ambientBass = AmbientMusicBassAnalyzer(format.sampleRate, format.channels)
    private var ambientRendererToken = 0L
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private val recoveryDiagnostics = MediaAudioRecoveryDiagnostics()
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        if (!running) return
        if (started) {
            packetsReceived.incrementAndGet()
            val now = System.nanoTime()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) maxArrivalGapMs.accumulateAndGet((now - previous) / 1_000_000L, ::maxOf)
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample, System.nanoTime()))) {
            if (started) packetsDropped.incrementAndGet()
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                report("Audio: queue full audioType=${format.audioType}")
            }
        }
    }

    fun priorityVoiceActive(): Boolean = running && playbackStarted &&
        (mappedChannel == AudioChannel.PHONE || mappedChannel == AudioChannel.ASSISTANT) &&
        voicePlaybackActivity.active()

    override fun close() {
        running = false
        voicePlaybackActivity.close()
        AmbientMusicController.closeRenderer(ambientRendererToken)
        NavigationPlayback.close(navigationPlaybackToken)
        thread.interrupt()
    }

    private fun run() {
        try {
            // Media needs the same scheduling protection as navigation: this worker
            // both decodes packets and feeds AudioTrack. Keep audio above video work.
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }
                .onSuccess { Log.i(TAG, "audio worker type=${format.audioType} priority=${Process.getThreadPriority(Process.myTid())}") }
                .onFailure { Log.w(TAG, "audio worker priority unavailable", it) }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            requestAudioFocus()
            while (running) {
                // Finish the held access unit before taking another packet. Retry the last
                // unit even when no new UDP packet arrives.
                if (pendingInput.current != null) {
                    pumpCodecInput()
                } else {
                    queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                }
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                codec?.let(::drainCodec)
                maintainPlaybackBuffer()
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                report("Audio: renderer failed audioType=${format.audioType} error=${error.javaClass.simpleName}")
            }
        } finally {
            running = false
            playbackStarted = false
            voicePlaybackActivity.close()
            runCatching { logStatsIfDue(force = true) }
            pendingInput.clear()
            queue.clear()
            release()
        }
    }

    private fun configureCodec(mime: String) {
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { MediaCodec.createDecoderByType(mime) },
                configure = { it.configure(mediaFormat, null, null, 0) },
                start = { it.start() },
                release = { it.release() },
            ).also {
                // Optional diagnostics must not discard a successfully started decoder.
                runCatching { Log.i(TAG, "audio decoder configured mime=$mime name=${it.name}") }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            null
        }
    }

    private fun createTrack() {
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        val attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = if (selection.channel == AudioChannel.NAVIGATION) {
            NavigationAudioBuffer.plan(format.sampleRate, format.channels, minBuffer)
        } else {
            MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
                format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        }
        bytesPerSecond = format.sampleRate * frameBytes
        val built: AudioTrack
        var routeLabel: String
        if (streamOverride == 0) {
            val attributes = audioAttributesFor(selection)
            routeLabel = "usage"
            built = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(pcmFormat(encoding, channelMask))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(plan.trackBufferBytes)
                .build()
        } else {
            val streamType = streamOverride
            actualLegacyStreamType = streamType
            routeLabel = "streamType=$streamType"
            built = LegacyAudioFallback.build(
                createLegacy = {
                    AudioTrack(streamType, format.sampleRate, channelMask, encoding,
                        plan.trackBufferBytes, AudioTrack.MODE_STREAM)
                },
                isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
                release = { it.release() },
                createFallback = {
                    actualLegacyStreamType = null
                    routeLabel = "streamType=$streamType(fallback=usage)"
                    Log.w(TAG, "streamType=$streamType rejected by this ROM; falling back to usage-based track")
                    AudioTrack.Builder()
                        .setAudioAttributes(audioAttributesFor(selection))
                        .setAudioFormat(pcmFormat(encoding, channelMask))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(plan.trackBufferBytes)
                        .build()
                },
            )
        }
        track = built
        if (mappedChannel == AudioChannel.MEDIA) {
            ambientRendererToken = AmbientMusicController.openRenderer(ambientSinkToken, ambientEnvelope) {
                built.playbackHeadPosition to (running && built.playState == AudioTrack.PLAYSTATE_PLAYING)
            }
        }
        trackAttributes = built.audioAttributes
        val capacityBytes = built.bufferSizeInFrames * frameBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond}")
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributes {
        if (streamOverride in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) {
            // Android accepts only its defined legacy stream IDs here. BYD audio policy can
            // map these standard streams to vehicle outputs; arbitrary channel numbers are
            // not valid AudioAttributes legacy stream types.
            try {
                return AudioAttributes.Builder().setLegacyStreamType(streamOverride).build()
            } catch (error: Exception) {
                Log.w(TAG, "legacy audio stream $streamOverride rejected; keeping usage routing", error)
            }
        }
        return AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()
    }

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
    }

    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usageFor(selection.channel))
            .setContentType(contentTypeFor(selection.contentType))
            .build()

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance intentionally takes no focus: it overlays media without ducking it.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun pcmFormat(encoding: Int, channelMask: Int) = AndroidAudioFormat.Builder()
        .setSampleRate(format.sampleRate)
        .setChannelMask(channelMask)
        .setEncoding(encoding)
        .build()

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_ASSISTANT
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size} " +
                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                        packet.arrivalNanos,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size} " +
                                "head=${accessUnit.toHexString()}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs, packet.arrivalNanos)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long, arrivalNanos: Long) {
        if (codec == null || !running) return
        pendingInput.offer(payload, presentationTimeUs, arrivalNanos, System.nanoTime())
        pumpCodecInput()
    }

    private fun pumpCodecInput() {
        val codec = codec ?: return
        val unit = pendingInput.current ?: return
        if (!running) return
        // A later pump must not revive an access unit beyond its retry window.
        if (pendingInput.unavailable(System.nanoTime())) {
            inputWaitExpired++
            inputDropped++
            return
        }
        val index = CodecInputRetry.acquire(
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
            drain = { drainCodec(codec) },
            running = { running },
            onRetry = { inputRetried++ },
        )
        if (index < 0) {
            if (!running) return
            inputWaitTimeouts++
            if (pendingInput.unavailable(System.nanoTime())) {
                inputWaitExpired++
                inputDropped++
            }
            if (inputDropped == 1 && pendingInput.current == null) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        if (!running) return
        val input = codec.getInputBuffer(index) ?: run {
            pendingInput.clear()
            inputDropped++
            return
        }
        val payload = unit.payload
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, unit.presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size} " +
                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        pendingInput.clear()
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        val info = codecOutputInfo
        while (running) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (format.codec == AudioCodecKind.AAC_LC && !aacOutputFormatLogged) {
                        aacOutputFormatLogged = true
                        val outputFormat = runCatching { codec.outputFormat }.getOrNull()
                        if (outputFormat == null) {
                            Log.w(TAG, "audio decoder output format unavailable codec=${format.codec}")
                        } else {
                            Log.i(
                                TAG,
                                "audio decoder output format codec=${format.codec} " +
                                    "negotiatedSampleRate=${format.sampleRate} negotiatedChannels=${format.channels} " +
                                    "actualSampleRate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: -1} " +
                                    "actualChannels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: -1}",
                            )
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    val ownedPcm = DecodedAudioPcm.copyAndRelease(
                        source = { codec.getOutputBuffer(index) },
                        offset = info.offset,
                        length = size,
                        scratch = pcm,
                        release = { codec.releaseOutputBuffer(index, false) },
                    )
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    if (ownedPcm != null) {
                        pcm = ownedPcm
                        writePcm(pcm, 0, size)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            val end = minOf(data.size, offset + minOf(length, 16))
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length " +
                    "head=${data.copyOfRange(offset, end).toHexString()}",
            )
        }
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            val count = track.write(data, offset + written, writeLength, AudioTrack.WRITE_BLOCKING)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            val framesWritten = count / frameBytes
            if (mappedChannel == AudioChannel.MEDIA && AmbientMusicController.wantsPcm(ambientRendererToken)) {
                ambientEnvelope.append(totalWrittenFrames, framesWritten.toLong(),
                    AmbientMusicEnvelope.pcmRms(data, offset + written, count),
                    ambientBass.rms(data, offset + written, count))
            }
            written += count
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (playbackStarted) {
                reportNavigationPlayback(track)
                reportPriorityVoicePlayback(track)
            }
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        val underruns = track.underrunCount
        val recoveryUnderruns = underruns - underrunsAtPlaybackStart
        underrunsAtPlaybackStart = underruns
        track.play()
        playbackStarted = true
        reportMediaRecovery(track, resumed = true, remainingPcmBytes = prebufferBytes.toLong(),
            underrunDelta = recoveryUnderruns)
        reportNavigationPlayback(track)
        reportPriorityVoicePlayback(track)
    }

    private fun reportPriorityVoicePlayback(track: AudioTrack) {
        if (!running || (mappedChannel != AudioChannel.PHONE && mappedChannel != AudioChannel.ASSISTANT)) return
        // A retained voice stream is active only while its written PCM can still be playing.
        val bufferedMillis = runCatching {
            val pendingFrames = bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes
            (pendingFrames * 1000L + format.sampleRate - 1L) / format.sampleRate
        }.getOrDefault(0L)
        voicePlaybackActivity.played(bufferedMillis)
    }

    private fun reportNavigationPlayback(track: AudioTrack) {
        if (!running || mappedChannel != AudioChannel.NAVIGATION) return
        // This optional UI hint must never interrupt sound on a ROM with unusual track behavior.
        runCatching {
            val pendingFrames = bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes
            val bufferedMillis = (pendingFrames * 1000L + format.sampleRate - 1L) / format.sampleRate
            NavigationPlayback.played(navigationPlaybackToken, actualLegacyStreamType, bufferedMillis)
        }
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                track.underrunCount > underrunsAtPlaybackStart, queue.isEmpty(), track.playbackHeadPosition,
                startThresholdBytes / 2L)) {
            // The hardware buffer has starved below the recovery floor. Pause without flushing
            // or discarding PCM, then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            // Pausing retains queued PCM. Count it toward the restart threshold so a
            // blocking write cannot fill the paused track before we call play().
            prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                .coerceAtMost(startThresholdBytes.toLong()).toInt()
            lastPcmWriteNs = System.nanoTime()
            rebufferCount++
            reportMediaRecovery(track, resumed = false, remainingPcmBytes = prebufferBytes.toLong(),
                underrunDelta = null)
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    private fun reportMediaRecovery(track: AudioTrack, resumed: Boolean,
        remainingPcmBytes: Long, underrunDelta: Int?) {
        if (mappedChannel != AudioChannel.MEDIA || (resumed && !recoveryDiagnostics.isPaused)) return
        // Diagnostics must not interrupt playback on a ROM with unusual track getters.
        runCatching {
            val snapshot = MediaAudioRecoveryDiagnostics.Snapshot(
                System.nanoTime(), track.playbackHeadPosition, remainingPcmBytes, frameBytes,
                queue.size, pendingInput.current != null,
                underrunDelta ?: (track.underrunCount - underrunsAtPlaybackStart), lastArrivalNs.get(),
            )
            val line = if (resumed) recoveryDiagnostics.resumed(snapshot) else recoveryDiagnostics.paused(snapshot)
            if (line != null) {
                Log.i(STATS_TAG, line)
                report(line)
            }
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        val underruns = track?.underrunCount ?: 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackParams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && currentTrack != null) {
            PlaybackParamsSnapshot.capture {
                val params = currentTrack.playbackParams
                params.speed to params.pitch
            }
        } else {
            PlaybackParamsSnapshot(null, null)
        }
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        val oldestArrival = listOfNotNull(pendingInput.current?.arrivalNanos, queue.peek()?.arrivalNanos).minOrNull()
        val oldestPacketAgeMs = oldestArrival?.let { ((now - it) / 1_000_000L).coerceAtLeast(0L) } ?: -1L
        val line = "audio stats audioType=${format.audioType} channel=$mappedChannel " +
            "routeType=${currentTrack?.routedDevice?.type ?: -1} codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackSampleRate=${currentTrack?.sampleRate ?: -1} negotiatedSampleRate=${format.sampleRate} " +
            "${playbackParams.logFields()} " +
            "trackBufferFrames=${currentTrack?.bufferSizeInFrames ?: -1} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped decoderInputRetriesTotal=$inputRetried " +
            "decoderInputWaitTimeoutsTotal=$inputWaitTimeouts decoderInputWaitExpiredTotal=$inputWaitExpired " +
            "pendingInput=${if (pendingInput.current == null) 0 else 1} oldestPacketAgeMs=$oldestPacketAgeMs " +
            "outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        AmbientMusicController.closeRenderer(ambientRendererToken)
        NavigationPlayback.close(navigationPlaybackToken)
        abandonAudioFocus()
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
    }
}
