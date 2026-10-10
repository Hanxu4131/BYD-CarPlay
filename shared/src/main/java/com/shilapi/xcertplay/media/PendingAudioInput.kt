package com.shilapi.xcertplay.media

/** One ordered access unit may wait for a busy decoder, without an unbounded retry backlog. */
internal class PendingAudioInput(private val maxWaitNanos: Long = 500_000_000L) {
    data class AccessUnit(val payload: ByteArray, val presentationTimeUs: Long, val arrivalNanos: Long)

    var current: AccessUnit? = null
        private set
    private var waitStartedNanos = 0L

    fun offer(payload: ByteArray, presentationTimeUs: Long, arrivalNanos: Long, nowNanos: Long) {
        check(current == null) { "Previous audio input must finish before the next packet" }
        current = AccessUnit(payload, presentationTimeUs, arrivalNanos)
        waitStartedNanos = nowNanos
    }

    /** A failed attempt retains the same unit until the bounded wait expires. */
    fun unavailable(nowNanos: Long): Boolean {
        if (current == null || nowNanos - waitStartedNanos < maxWaitNanos) return false
        clear()
        return true
    }

    fun clear() {
        current = null
        waitStartedNanos = 0L
    }
}
