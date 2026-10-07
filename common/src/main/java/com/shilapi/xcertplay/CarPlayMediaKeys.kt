package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import com.shilapi.xcertplay.media.CarPlayNowPlaying
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
    private val metadataPublication = NowPlayingMetadataPublication<Bitmap>()
    private var metadataLogs = 0
    private var lastMetadataLogNanos = 0L
    private var lastLoggedReceiptNanos = 0L
    private val artworkQueue = NowPlayingArtworkQueue(
        worker = Executors.newSingleThreadExecutor { task ->
            Thread(task, "diplay-now-playing-artwork").apply { isDaemon = true }
        },
        main = Executor { mainHandler.post(it) },
        decode = ::decodeAmbientArtwork,
        publish = ::onArtworkDecoded,
        discard = { it.bitmap.recycle() },
    )
    private var artworkOwner: Any? = null
    private var nowPlaying = CarPlayNowPlaying()
    private var elapsedUpdatedAt = 0L
    private var artwork: Bitmap? = null
    private val artworkCache = LinkedHashMap<Int, Bitmap?>()
    private val ambientColorCache = LinkedHashMap<Int, Int?>()
    private var nowPlayingReceivedAtNanos = 0L
    private var controller: CarPlayController? = null
    private var session: MediaSession? = null
    private var focusRequest: AudioFocusRequest? = null
    private var captureCreatedSession = false
    private var focusHeld = false
    private var appContext: Context? = null

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) {
            releaseLocked()
            artworkOwner = artworkQueue.newSession()
        }
        appContext = context.applicationContext
        controller = next
        com.shilapi.xcertplay.media.AmbientMusicController.claimPlaybackOwner(next)
        next.playbackListener = { playing ->
            if (controller === next) {
                com.shilapi.xcertplay.media.AmbientMusicController.phonePlaybackChanged(next, playing)
                onIphonePlaying(playing)
            }
        }
        next.nowPlayingListener = { update -> onNowPlayingChanged(next, update) }
        next.artworkListener = { id, bytes -> onArtworkChanged(next, id, bytes) }
        BydSongMetadata.listener = ::onMetadataChanged
        onMetadataChanged()
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        com.shilapi.xcertplay.media.AmbientMusicController.releasePlaybackOwner(expected)
        expected.playbackListener = null
        expected.nowPlayingListener = null
        expected.artworkListener = null
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

    @Synchronized
    private fun onNowPlayingChanged(expected: CarPlayController, update: CarPlayNowPlaying) {
        if (controller !== expected) return
        if (nowPlaying.artworkTransferId != update.artworkTransferId) {
            artwork = nextArtwork(update.artworkTransferId, artworkCache, artwork)
            // Pending artwork belongs to a new song: use fallback until its own decode arrives.
            com.shilapi.xcertplay.media.AmbientMusicController.albumColorChanged(expected,
                update.artworkTransferId?.let { ambientColorCache[it] })
        }
        if (nowPlaying.elapsedMillis != update.elapsedMillis) elapsedUpdatedAt = SystemClock.elapsedRealtime()
        nowPlaying = update
        nowPlayingReceivedAtNanos = System.nanoTime()
        onMetadataChanged()
    }

    @Synchronized
    private fun onArtworkChanged(expected: CarPlayController, id: Int, bytes: ByteArray) {
        if (controller !== expected) return
        artworkOwner?.let { artworkQueue.submit(it, id, bytes) }
    }

    @Synchronized
    private fun onArtworkDecoded(expected: Any, id: Int, result: AmbientArtwork?) {
        val decoded = result?.bitmap
        if (artworkOwner !== expected) {
            decoded?.recycle()
            return
        }
        artworkCache.remove(id)
        artworkCache[id] = decoded
        ambientColorCache.remove(id)
        ambientColorCache[id] = result?.color
        while (ambientColorCache.size > MAX_CACHED_ARTWORK) ambientColorCache.remove(ambientColorCache.keys.first())
        while (artworkCache.size > MAX_CACHED_ARTWORK) artworkCache.remove(artworkCache.keys.first())
        if (nowPlaying.artworkTransferId == id) {
            artwork = decoded
            controller?.let { com.shilapi.xcertplay.media.AmbientMusicController.albumColorChanged(it, result?.color) }
            publishMetadataLocked()
        }
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
        if (BydSongMetadata.snapshot() != null || nowPlaying.title != null) {
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
        val song = BydSongMetadata.snapshot() ?: nowPlaying.title?.let {
            BydSongMetadata.Snapshot(it, nowPlaying.artist,
                if (nowPlaying.playing) BydSongMetadata.Playback.PLAYING else BydSongMetadata.Playback.PAUSED)
        }
        if (song != null || nowPlaying.title != null) appContext?.let { ensureSessionLocked(it) }
        val info = if (nowPlaying.title == null && song != null) {
            nowPlaying.copy(title = song.title, artist = song.artist)
        } else nowPlaying
        session?.let { current ->
            // Position-only updates still publish playback state, without resending the bitmap.
            metadataPublication.publish(current, info, artwork) {
                current.setMetadata(androidMetadata(info, artwork))
            }
        }
        session?.setPlaybackState(carPlaySongPlaybackState(song, nowPlaying.elapsedMillis, elapsedUpdatedAt))
        if (song != null && session != null) {
            val now = System.nanoTime()
            val received = maxOf(BydSongMetadata.receivedAtNanos(), nowPlayingReceivedAtNanos)
            if (received != lastLoggedReceiptNanos && (metadataLogs < 8 || now - lastMetadataLogNanos >= 30_000_000_000L)) {
                metadataLogs++
                lastMetadataLogNanos = now
                lastLoggedReceiptNanos = received
                Log.w(TAG, "song publish receiveToMainMs=${(now - received) / 1_000_000L}")
            }
        }
    }

    private fun releaseLocked() {
        metadataPublication.reset()
        artworkOwner = null
        artworkQueue.clear()
        nowPlaying = CarPlayNowPlaying()
        elapsedUpdatedAt = 0L
        nowPlayingReceivedAtNanos = 0L
        artwork = null
        artworkCache.clear()
        ambientColorCache.clear()
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

    internal fun androidMetadata(info: CarPlayNowPlaying, artwork: Bitmap? = null): MediaMetadata =
        MediaMetadata.Builder().apply {
            info.title?.let {
                putString(MediaMetadata.METADATA_KEY_TITLE, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, it)
            }
            info.artist?.let {
                putString(MediaMetadata.METADATA_KEY_ARTIST, it)
                putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, it)
            }
            info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
            info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
            info.sourceApp?.let { putString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION, it) }
            artwork?.let {
                putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
                putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, it)
            }
        }.build()

    /** A pending transfer keeps the previous image; an explicit null or cached failure clears it. */
    internal fun nextArtwork(id: Int?, cache: Map<Int, Bitmap?>, current: Bitmap?): Bitmap? = when {
        id == null -> null
        cache.containsKey(id) -> cache[id]
        else -> current
    }

    private data class AmbientArtwork(val bitmap: Bitmap, val color: Int?)

    private fun decodeAmbientArtwork(bytes: ByteArray): AmbientArtwork? {
        val bitmap = decodeArtwork(bytes) ?: return null
        // Runs once per decoded cover on the existing bounded artwork worker (576 pixels).
        val small = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        val pixels = IntArray(24 * 24)
        val rgb = try {
            small.getPixels(pixels, 0, 24, 0, 0, 24, 24)
            com.shilapi.xcertplay.media.AmbientAlbumPalette.dominantRgb(pixels)
        } finally { if (small !== bitmap) small.recycle() }
        return AmbientArtwork(bitmap, rgb?.let { com.shilapi.xcertplay.media.AmbientAlbumPalette.bydColor(it) })
    }

    private fun decodeArtwork(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..MAX_ARTWORK_SOURCE_DIMENSION ||
            bounds.outHeight !in 1..MAX_ARTWORK_SOURCE_DIMENSION
        ) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_ARTWORK_DIMENSION * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val largest = maxOf(decoded.width, decoded.height)
        if (largest <= MAX_ARTWORK_DIMENSION) return decoded
        val scale = MAX_ARTWORK_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        ).also { scaled -> if (scaled !== decoded) decoded.recycle() }
    }

    private const val MAX_ARTWORK_DIMENSION = 384
    private const val MAX_ARTWORK_SOURCE_DIMENSION = 8_192
    private const val MAX_CACHED_ARTWORK = 4

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

internal fun carPlaySongPlaybackState(
    song: BydSongMetadata.Snapshot?,
    elapsedMillis: Long? = null,
    elapsedUpdatedAt: Long = 0L,
): PlaybackState {
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
        .setState(state, elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN, speed, elapsedUpdatedAt)
        .build()
}
