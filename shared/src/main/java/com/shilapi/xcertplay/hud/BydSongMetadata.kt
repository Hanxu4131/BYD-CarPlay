package com.shilapi.xcertplay.hud

/** Phone metadata for the existing CarPlay media session; no audio or focus is created here. */
object BydSongMetadata {
    enum class Playback { STOPPED, PLAYING, PAUSED, SEEK_FORWARD, SEEK_BACKWARD }

    data class Snapshot(val title: String, val artist: String?, val playback: Playback)

    @Volatile private var latest: Snapshot? = null
    @Volatile private var receivedNanos = 0L

    /** Called on the receiving thread. Consumers should read [snapshot] when queued work runs. */
    @Volatile var listener: (() -> Unit)? = null

    fun snapshot(): Snapshot? = latest

    /** Monotonic receipt time for bounded diagnostics, never a predicted lyric timestamp. */
    fun receivedAtNanos(): Long = receivedNanos

    internal fun publish(next: Snapshot?, receivedAtNanos: Long = System.nanoTime()) {
        if (next == latest) return
        receivedNanos = receivedAtNanos
        latest = next
        listener?.invoke()
    }
}

/** Keep one pending song update; a slow consumer always continues with the newest complete state. */
class SongUpdateDispatcher(private val schedule: (() -> Unit) -> Unit) {
    private var queued = false
    private var latest: (() -> Unit)? = null

    fun submit(update: () -> Unit) {
        synchronized(this) {
            latest = update
            if (queued) return
            queued = true
        }
        schedule {
            val action = synchronized(this) {
                queued = false
                latest.also { latest = null }
            }
            action?.invoke()
        }
    }
}
