package com.shilapi.xcertplay.media

/** A retained phone/Siri renderer must not block navigation after its PCM has drained. */
internal class VoicePlaybackActivity(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val quietTailMillis: Long = 500L,
) {
    private var untilMillis: Long? = null
    private var closed = false

    @Synchronized
    fun played(bufferedMillis: Long) {
        if (closed) return
        untilMillis = nowMillis() + bufferedMillis.coerceAtLeast(0L) + quietTailMillis
    }

    @Synchronized
    fun active(): Boolean = !closed && untilMillis?.let { nowMillis() < it } == true

    @Synchronized
    fun close() {
        closed = true
        untilMillis = null
    }
}
