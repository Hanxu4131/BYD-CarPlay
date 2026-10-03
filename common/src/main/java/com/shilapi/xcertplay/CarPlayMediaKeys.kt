package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.MediaMetadata
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.hud.BydSongMetadata
import com.shilapi.xcertplay.hud.SongUpdateDispatcher
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; BYD picks the session of the audio-focus
 * owner. Once CarPlay plays music, DiPlay holds audio focus and an active session until the
 * CarPlay session ends, so play also works after a pause. Keys go to the iPhone as CarPlay media
 * HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"
    private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metadataUpdates = SongUpdateDispatcher { action -> mainHandler.post { action() } }
    private var metadataLogs = 0
    private var lastMetadataLogNanos = 0L
    private var lastLoggedReceiptNanos = 0L
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var captureCreatedSession = false
    private var focusHeld = false
    private var appContext: Context? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) releaseLocked()
        appContext = context.applicationContext
        controller = next
        next.playbackListener = ::onIphonePlaying
        BydSongMetadata.listener = ::onMetadataChanged
        onMetadataChanged()
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        BydSongMetadata.listener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    fun onIphonePlaying(playing: Boolean) {
        if (playing) mainHandler.post { synchronized(this) { regainFocusLocked() } }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() {
        val request = focusRequest ?: return
        if (focusHeld) return
        val audio = appContext?.getSystemService(AudioManager::class.java) ?: return
        focusHeld = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        if (active && focusRequest == null) start(context) else if (active) regainFocusLocked()
        // NowPlaying is authoritative when available; an audio stream can persist during a pause.
        if (BydSongMetadata.snapshot() != null) {
            publishMetadataLocked()
            return
        }
        session?.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (active) 1f else 0f)
                .build(),
        )
    }

    private fun start(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener({ change ->
                Log.i(TAG, "audio focus change=$change")
                // Only a permanent loss moves the car's media keys elsewhere; transient losses come back.
                if (change == AudioManager.AUDIOFOCUS_LOSS) synchronized(this) { focusHeld = false }
            }, mainHandler)
            .build()
        val granted = audio?.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        focusRequest = request
        focusHeld = granted
        ensureSessionLocked(context)
        Log.i(TAG, "media keys active focusGranted=$granted")
    }

    private fun ensureSessionLocked(context: Context) {
        if (session == null) session = MediaSession(context, "DiPlay CarPlay").apply {
            setCallback(callback, mainHandler)
            isActive = true
        }
    }

    /** Read the latest snapshot on execution, so disconnects invalidate older queued updates. */
    private fun onMetadataChanged() {
        metadataUpdates.submit { synchronized(this) { publishMetadataLocked() } }
    }

    private fun publishMetadataLocked() {
        if (controller == null) return
        val song = BydSongMetadata.snapshot()
        if (song != null) appContext?.let { ensureSessionLocked(it) }
        session?.setMetadata(song?.let { carPlayMediaMetadata(it) })
        session?.setPlaybackState(carPlaySongPlaybackState(song))
        if (song != null && session != null) {
            val now = System.nanoTime()
            val received = BydSongMetadata.receivedAtNanos()
            if (received != lastLoggedReceiptNanos && (metadataLogs < 8 || now - lastMetadataLogNanos >= 30_000_000_000L)) {
                metadataLogs++
                lastMetadataLogNanos = now
                lastLoggedReceiptNanos = received
                Log.w(TAG, "song publish receiveToMainMs=${(now - received) / 1_000_000L}")
            }
        }
    }

    private fun releaseLocked() {
        session?.let {
            it.isActive = false
            it.release()
        }
        session = null
        focusRequest?.let { request -> appContext?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request) }
        focusRequest = null
        focusHeld = false
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
    }

    @Synchronized
    fun beginWheelCapture(context: Context) {
        appContext = context.applicationContext
        captureCreatedSession = session == null
        ensureSessionLocked(context.applicationContext)
    }

    @Synchronized
    fun endWheelCapture() {
        if (captureCreatedSession && focusRequest == null && BydSongMetadata.snapshot() == null) {
            session?.isActive = false
            session?.release()
            session = null
        }
        captureCreatedSession = false
    }

    fun sendHardwareMedia(index: Int) = send(index, "hardware")

    fun sendWheelAction(action: WheelAction) {
        val target = synchronized(this) { controller }
        val sent = when (action) {
            WheelAction.PREVIOUS -> { sendHardwareMedia(CarPlayMediaButton.PREVIOUS); return }
            WheelAction.NEXT -> { sendHardwareMedia(CarPlayMediaButton.NEXT); return }
            WheelAction.ANSWER -> target?.sendTelephonyButton(1) == true
            WheelAction.HANG_UP -> target?.sendTelephonyButton(3) == true
            WheelAction.SIRI -> target?.requestSiri() == true
            WheelAction.HOME -> target?.sendCarPlayHome() == true
        }
        Log.i(TAG, "wheel action=$action sent=$sent")
    }

    private val callback = CarPlayMediaCallback(::send, { event ->
        appContext?.let { SteeringWheelMappings.consume(it, event, defaults = true) } ?: false
    }, SteeringWheelMappings::capturing)
}

/**
 * Media-session input → CarPlay presses. Hardware keys arrive as button events and keep the toggle;
 * media controllers (not hardware keys) call [onPlay] and [onPause] with an explicit intent.
 */
internal class CarPlayMediaCallback(
    private val send: (index: Int, source: String) -> Unit,
    private val intercept: (KeyEvent) -> Boolean,
    private val suppressTransport: () -> Boolean,
) : MediaSession.Callback() {
    constructor(send: (Int, String) -> Unit) : this(send, { false }, { false })

    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        if (intercept(event)) return true
        val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return super.onMediaButtonEvent(mediaButtonIntent)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            send(index, KeyEvent.keyCodeToString(event.keyCode))
        }
        return true
    }

    override fun onPlay() { if (!suppressTransport()) send(CarPlayMediaButton.PLAY, "play") }
    override fun onPause() { if (!suppressTransport()) send(CarPlayMediaButton.PAUSE, "pause") }
    override fun onSkipToNext() { if (!suppressTransport()) send(CarPlayMediaButton.NEXT, "next") }
    override fun onSkipToPrevious() { if (!suppressTransport()) send(CarPlayMediaButton.PREVIOUS, "previous") }
}

/** Forward the phone's text verbatim; a phone app may itself put lyrics in its title. */
internal fun carPlayMediaMetadata(song: BydSongMetadata.Snapshot): MediaMetadata =
    MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, song.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, song.artist)
        .build()

internal fun carPlaySongPlaybackState(song: BydSongMetadata.Snapshot?): PlaybackState {
    val status = song?.playback ?: BydSongMetadata.Playback.STOPPED
    val state = when (status) {
        BydSongMetadata.Playback.PLAYING -> PlaybackState.STATE_PLAYING
        BydSongMetadata.Playback.PAUSED -> PlaybackState.STATE_PAUSED
        BydSongMetadata.Playback.SEEK_FORWARD -> PlaybackState.STATE_FAST_FORWARDING
        BydSongMetadata.Playback.SEEK_BACKWARD -> PlaybackState.STATE_REWINDING
        BydSongMetadata.Playback.STOPPED -> PlaybackState.STATE_STOPPED
    }
    val speed = when (status) {
        BydSongMetadata.Playback.PLAYING -> 1f
        BydSongMetadata.Playback.SEEK_FORWARD -> 2f
        BydSongMetadata.Playback.SEEK_BACKWARD -> -2f
        else -> 0f
    }
    return PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed)
        .build()
}
