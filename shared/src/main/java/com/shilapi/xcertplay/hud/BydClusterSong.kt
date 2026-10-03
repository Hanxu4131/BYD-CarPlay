package com.shilapi.xcertplay.hud

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import android.util.Log
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executors

/** What the dashboard's music card shows. */
internal data class ClusterSong(val text: String, val playing: Boolean)

/**
 * The CarPlay song for the dashboard, from iAP2 NowPlayingUpdate (0x5001): title (1) and artist (12)
 * in MediaItemAttributes, playback status in PlaybackAttributes. Updates carry only what changed; a
 * missing fields keep their previous value, including when a phone app updates only the title
 * with a lyric line. Explicitly clearing the title clears the card and its artist.
 */
internal class ClusterSongState {
    private var title: String? = null
    private var artist: String? = null
    private var playing = false
    private var playbackStatus = BydSongMetadata.Playback.STOPPED
    private var last: ClusterSong? = null

    /** Updates the cached card; null can mean unchanged or cleared, so consumers compare [current]. */
    fun accept(frame: Iap2Frame): ClusterSong? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        runCatching { body.optionalGroup(ITEM) }.getOrNull()?.let { item ->
            val nextTitle = runCatching { item.optionalString(TITLE) }.getOrNull()
            if (nextTitle != null) {
                title = nextTitle
                if (nextTitle.isBlank()) artist = null
            }
            runCatching { item.optionalString(ARTIST) }.getOrNull()?.let { artist = it }
        }
        runCatching { body.optionalGroup(PLAYBACK)?.optionalU8(STATUS) }.getOrNull()?.let { status ->
            playbackStatus = when (status) {
                STATUS_PLAYING -> BydSongMetadata.Playback.PLAYING
                2 -> BydSongMetadata.Playback.PAUSED
                STATUS_SEEK_FORWARD -> BydSongMetadata.Playback.SEEK_FORWARD
                STATUS_SEEK_BACKWARD -> BydSongMetadata.Playback.SEEK_BACKWARD
                else -> BydSongMetadata.Playback.STOPPED
            }
            playing = status == STATUS_PLAYING || status == STATUS_SEEK_FORWARD || status == STATUS_SEEK_BACKWARD
        }
        val next = text(title, artist)?.let { ClusterSong(it, playing) }
        if (next == last) return null
        last = next
        return next
    }

    /** The card for the song known so far, if any. */
    fun current(showArtist: Boolean = true): ClusterSong? =
        if (showArtist) last else text(title, null)?.let { ClusterSong(it, playing) }

    /** Separate fields stay intact for Android; only the dashboard card is shortened. */
    fun mediaCurrent(): BydSongMetadata.Snapshot? = title?.trim()?.takeIf { it.isNotEmpty() }?.let {
        BydSongMetadata.Snapshot(it, artist?.trim()?.takeIf { name -> name.isNotEmpty() }, playbackStatus)
    }

    /** The session ended: forget the song. */
    fun clear() {
        title = null
        artist = null
        playing = false
        playbackStatus = BydSongMetadata.Playback.STOPPED
        last = null
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val ITEM = 0
        private const val TITLE = 1
        private const val ARTIST = 12
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_SEEK_FORWARD = 3
        private const val STATUS_SEEK_BACKWARD = 4

        /** The dashboard takes at most 255 bytes of UTF-16LE. */
        const val MAX_TEXT_BYTES = 255

        /** "Title — Artist", shortened to what the dashboard takes; null without a title. */
        fun text(title: String?, artist: String?): String? {
            val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val full = artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$name — $it" } ?: name
            var end = full.length
            while (full.substring(0, end).toByteArray(Charsets.UTF_16LE).size > MAX_TEXT_BYTES) {
                end--
                if (end > 0 && Character.isLowSurrogate(full[end])) end--
            }
            return full.substring(0, end)
        }
    }
}

/**
 * Optional, needs ADB over network: shows the CarPlay song in the dashboard's music card. Apps cannot
 * write it (BYDAutoInstrumentDevice checks a BYD permission), but autoservice accepts the adb shell
 * user, so DiPlay runs [BydClusterSongTool] from its own APK under the head unit's adb shell. The card
 * appears as BYD's "other" music source; CarPlay's own source value is not drawn on this dashboard.
 */
internal object BydClusterSong {
    private const val TAG = "DiPlay-BYD-Song"
    private const val STATE_PLAYING = 1
    private const val STATE_PAUSED = 2
    private const val STATE_STOPPED = 3

    private val writer = Executors.newSingleThreadScheduledExecutor { Thread(it, "diplay-cluster-song").apply { isDaemon = true } }
    private val worker = ClusterSongWorkerClient()
    private val state = ClusterSongState() // guards wanted too
    @Volatile private var context: Context? = null
    private data class Write(val song: ClusterSong, val playback: BydSongMetadata.Playback)
    private val updates = SongUpdateDispatcher { action -> writer.execute { action() } }
    private var requestedNanos = 0L // guarded by state
    private var timingLogs = 0 // writer thread
    private var lastTimingLogNanos = 0L // writer thread
    private var wanted: Write? = null
    private var shown: Write? = null // writer thread
    private var firstLogged = false // writer thread
    private val controls = ClusterSongControlCache()
    private val lease = ClusterSongLeaseState() // guarded by state
    private var workerTouched = false // writer thread

    init {
        writer.scheduleWithFixedDelay({
            runCatching {
                val app = context ?: return@runCatching
                if (!allowed(app)) return@runCatching
                if (!worker.keepAlive()) {
                    controls.reset()
                    if (!warm(app)) return@runCatching
                }
                val request = synchronized(state) { wanted } ?: return@runCatching
                if (controls.needsRefresh(playbackValue(request), System.nanoTime())) write(app, request)
            }.onFailure { Log.w(TAG, "song worker renewal failed", it) }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS)
    }

    fun attach(appContext: Context, sessionStarted: Boolean = true) {
        val app = appContext.applicationContext
        context = app
        if (!sessionStarted) return
        synchronized(state) { lease.start() }
        updates.submit { refresh(app) }
    }

    /** NowPlayingUpdate frames; the song is followed even while the setting is off, so it can show at once. */
    fun onFrame(frame: Iap2Frame) {
        val receivedNanos = System.nanoTime()
        val app = context ?: return
        synchronized(state) {
            val previous = state.current()
            val previousMedia = state.mediaCurrent()
            state.accept(frame)
            BydSongMetadata.publish(state.mediaCurrent(), receivedNanos)
            val song = state.current(BydOutputSettings.clusterSongArtist(app))
            if (state.current() == previous && state.mediaCurrent() == previousMedia) return
            if (allowed(app)) {
                if (song == null) stop(app) else show(app, song)
            } else stop(app)
        }
    }

    /** The setting changed: show the current song now, or stop the card DiPlay set. */
    fun settingChanged() {
        val app = context ?: return
        synchronized(state) {
            lease.settingChanged()
            BydSongMetadata.publish(state.mediaCurrent())
            wanted = if (allowed(app)) state.current(BydOutputSettings.clusterSongArtist(app))?.let {
                Write(it, state.mediaCurrent()?.playback ?: BydSongMetadata.Playback.STOPPED)
            } else null
            requestedNanos = System.nanoTime()
            updates.submit { refresh(app) }
        }
    }

    /** The session ended: forget the song and stop the card DiPlay set. */
    fun end() {
        val app = context ?: return
        synchronized(state) {
            lease.end()
            state.clear()
            BydSongMetadata.publish(null)
            stop(app)
        }
    }

    private fun show(app: Context, song: ClusterSong) {
        synchronized(state) {
            wanted = Write(song, state.mediaCurrent()?.playback ?: BydSongMetadata.Playback.STOPPED)
            requestedNanos = System.nanoTime()
        }
        updates.submit { refresh(app) }
    }

    private fun stop(app: Context) {
        synchronized(state) { wanted = null }
        updates.submit { refresh(app) }
    }

    private fun allowed(app: Context): Boolean = synchronized(state) {
        lease.allowed(BydOutputSettings.clusterSongLegacy(app))
    }

    private fun refresh(app: Context) {
        if (!allowed(app)) { closeWorker(); return }
        val request = synchronized(state) { wanted }
        if (request != null) write(app, request)
        else {
            // An explicit empty title clears our old card, but a warmed empty session has no setters.
            if (workerTouched) closeWorker()
            warm(app)
        }
    }

    private fun warm(app: Context): Boolean {
        val ticket = synchronized(state) { lease.ticket(BydOutputSettings.clusterSongLegacy(app)) } ?: return false
        val ready = worker.ensure(app)
        if (!synchronized(state) { lease.valid(ticket, BydOutputSettings.clusterSongLegacy(app)) }) {
            closeWorker()
            return false
        }
        return ready
    }

    private fun write(app: Context, request: Write) {
        // Only the newest song matters; older queued ones are skipped.
        val requested = synchronized(state) {
            if (wanted != request || (request == shown && !controls.needsRefresh(playbackValue(request), System.nanoTime()))) return
            requestedNanos
        }
        val started = System.nanoTime()
        if (!warm(app)) { controls.reset(); return }
        // Startup may take several seconds. Never send a stale lyric or write after end/off.
        if (!allowed(app) || synchronized(state) { wanted } != request) return
        val song = request.song
        val text = Base64.encodeToString(song.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val playing = playbackValue(request)
        val command = controls.command(playing, text, System.nanoTime())
        workerTouched = true
        val output = worker.write(command.args)
        val success = output != null && ClusterSongWriteCommand.succeeded(output, command.fields)
        controls.accept(output, command, playing, System.nanoTime())
        output?.lineSequence()?.firstOrNull { it.startsWith("timing ") }?.let {
            if (timingLogs < 8 || System.nanoTime() - lastTimingLogNanos >= 30_000_000_000L) Log.w(TAG, it)
        }
        val finished = System.nanoTime()
        if (timingLogs < 8 || finished - lastTimingLogNanos >= 30_000_000_000L) {
            timingLogs++
            lastTimingLogNanos = finished
            Log.w(TAG, "song OEM queueMs=${(started - requested) / 1_000_000L} writeMs=${(finished - started) / 1_000_000L} success=$success")
        }
        // Track acknowledged text separately from full control success; source=1 is never success.
        if (output?.lineSequence()?.any { it.trim() == "text=0" } == true && controls.playback == playing) {
            shown = request
            if (!firstLogged) {
                firstLogged = true
                Log.i(TAG, "OEM song text acknowledged")
            }
        }
    }

    private fun playbackValue(request: Write): Int =
        if (request.song.playing) STATE_PLAYING else if (request.playback == BydSongMetadata.Playback.STOPPED) STATE_STOPPED else STATE_PAUSED

    private fun closeWorker() {
        val result = worker.stop(clearCard = workerTouched)
        if (workerTouched && result != null && !ClusterSongWriteCommand.succeeded(result, setOf("state"))) {
            Log.w(TAG, "song worker stop failed: ${result.take(160)}")
        }
        workerTouched = false
        shown = null
        controls.reset()
    }
}

/** Session generation invalidates initialization that finishes after disconnect or a setting change. */
internal class ClusterSongLeaseState {
    private var active = false
    private var generation = 0L
    fun start() { if (!active) { active = true; generation++ } }
    fun end() { active = false; generation++ }
    fun settingChanged() { generation++ }
    fun allowed(enabled: Boolean): Boolean = active && enabled
    fun ticket(enabled: Boolean): Long? = generation.takeIf { allowed(enabled) }
    fun valid(ticket: Long, enabled: Boolean): Boolean = allowed(enabled) && ticket == generation
}

/** Skip unchanged OEM control writes; lyric updates need only the existing text operation. */
internal data class ClusterSongWriteCommand(val args: String, val fields: Set<String>) {
    companion object {
        fun forSong(previousPlayback: Int?, playback: Int, encodedText: String): ClusterSongWriteCommand {
            val source = if (previousPlayback == null) "11" else "-"
            val state = if (previousPlayback != playback) playback.toString() else "-"
            val fields = buildSet {
                if (source != "-") add("source")
                if (state != "-") add("state")
                add("text")
            }
            return ClusterSongWriteCommand("$source $state $encodedText", fields)
        }

        fun succeeded(output: String, fields: Set<String>): Boolean {
            val results = output.lineSequence().map { it.trim() }.filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
            return results.keys == fields && fields.all { results[it] == "0" }
        }
    }
}

/** Cache each acknowledged OEM control independently; failed source writes are not retried per lyric. */
internal class ClusterSongControlCache {
    private var sourceWritten = false
    private var retrySourceAt = 0L
    var playback: Int? = null
        private set
    private var retryPlaybackAt = 0L

    fun needsRefresh(nextPlayback: Int, now: Long): Boolean =
        (!sourceWritten && now >= retrySourceAt) || (playback != nextPlayback && now >= retryPlaybackAt)

    fun command(nextPlayback: Int, text: String, now: Long): ClusterSongWriteCommand {
        val source = if (!sourceWritten && now >= retrySourceAt) "11" else "-"
        val state = if (playback != nextPlayback && now >= retryPlaybackAt) nextPlayback.toString() else "-"
        return ClusterSongWriteCommand("$source $state $text", buildSet {
            if (source != "-") add("source")
            if (state != "-") add("state")
            add("text")
        })
    }

    fun accept(output: String?, command: ClusterSongWriteCommand, nextPlayback: Int, now: Long) {
        if (output == null) { reset(); return }
        val results = output.lineSequence().map { it.trim() }.filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=').trim() }
        if ("source" in command.fields) {
            sourceWritten = results["source"] == "0"
            retrySourceAt = if (sourceWritten) 0L else now + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
        }
        if ("state" in command.fields) {
            playback = if (results["state"] == "0") nextPlayback else null
            retryPlaybackAt = if (playback != null) 0L else now + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        }
    }

    fun reset() { sourceWritten = false; playback = null; retrySourceAt = 0L; retryPlaybackAt = 0L }
}

/**
 * Runs under the head unit's adb shell through app_process, not in DiPlay: writes the dashboard's music
 * source, play state and song text to the instrument (device 1007), as BYD's media controller does
 * (source 0x33F00030, state 0x43E0000A, text 0x43FB1008 in UTF-16LE). Arguments: source, state and
 * base64 UTF-8 text, "-" to skip one. Prints "name=result" per write; 0 is success.
 */
object BydClusterSongTool {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            println(ClusterSongDeviceWriter().write(args))
        } catch (error: Throwable) {
            println("write=ERR ${clusterSongError(error)}")
        } finally {
            System.exit(0)
        }
    }
}

/** Initializes only the previously approved instrument device and methods. */
@SuppressLint("PrivateApi")
internal class ClusterSongDeviceWriter {
    private val started = System.nanoTime()
    private val device: Any
    private val setState: java.lang.reflect.Method
    private val setInfo: java.lang.reflect.Method
    private val contextMs: Long
    private val deviceMs: Long

    init {
        runCatching { android.os.Looper.prepareMainLooper() }
        val thread = Class.forName("android.app.ActivityThread")
        val main = thread.getMethod("systemMain").invoke(null)
        val context = thread.getMethod("getSystemContext").invoke(main)
        val contextReady = System.nanoTime()
        val deviceClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        device = try {
            deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (_: InvocationTargetException) {
            deviceClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        }
        setState = deviceClass.getMethod("setMediaState", Int::class.java, Int::class.java, Int::class.java)
        setInfo = deviceClass.getMethod("setMediaInfo", Int::class.java, Int::class.java, ByteArray::class.java)
        contextMs = (contextReady - started) / 1_000_000L
        deviceMs = (System.nanoTime() - contextReady) / 1_000_000L
    }

    fun write(args: Array<String>): String {
        val response = mutableListOf<String>()
        val start = System.nanoTime()
        fun result(name: String, action: () -> Any?) {
            response += "$name=${runCatching { action().toString() }.getOrElse { "ERR ${clusterSongError(it)}" }}"
        }
        args.getOrNull(0)?.takeIf { it != "-" }?.let { result("source") { setState.invoke(device, 1007, 0x33F00030, it.toInt()) } }
        val sourceDone = System.nanoTime()
        args.getOrNull(1)?.takeIf { it != "-" }?.let { result("state") { setState.invoke(device, 1007, 0x43E0000A, it.toInt()) } }
        val stateDone = System.nanoTime()
        args.getOrNull(2)?.takeIf { it != "-" }?.let { encoded ->
            result("text") {
                val text = String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8).toByteArray(Charsets.UTF_16LE)
                if (text.size > ClusterSongState.MAX_TEXT_BYTES) "ERR too long" else setInfo.invoke(device, 1007, 0x43FB1008, text)
            }
        }
        val textDone = System.nanoTime()
        response += "timing contextMs:$contextMs deviceMs:$deviceMs sourceMs:${(sourceDone - start) / 1_000_000L} stateMs:${(stateDone - sourceDone) / 1_000_000L} textMs:${(textDone - stateDone) / 1_000_000L}"
        return response.joinToString("\n")
    }
}

private fun clusterSongError(error: Throwable): String {
    val cause = error.cause ?: error
    return (cause.javaClass.name + (cause.message?.let { ": $it" } ?: "")).replace('\n', ' ').replace('\r', ' ').take(160)
}
