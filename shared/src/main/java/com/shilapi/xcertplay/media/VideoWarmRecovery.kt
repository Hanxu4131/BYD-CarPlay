package com.shilapi.xcertplay.media

/** One flush per working codec; another failure returns to the ordinary rebuild path. */
internal class VideoWarmRecovery(private val pictureTimeoutNs: Long = 2_000_000_000L) {
    private var hasPicture = false
    private var used = false
    private var waitingSinceNs: Long? = null

    fun canFlush(queueRecovery: Boolean, validSurface: Boolean, hasConfig: Boolean, codecHealthy: Boolean): Boolean =
        queueRecovery && validSurface && hasConfig && codecHealthy && hasPicture && !used

    fun flushed(nowNs: Long) {
        used = true
        waitingSinceNs = nowNs
    }

    fun pictureDecoded() {
        hasPicture = true
        waitingSinceNs = null
    }

    fun timedOut(nowNs: Long): Boolean = waitingSinceNs?.let { nowNs - it >= pictureTimeoutNs } ?: false

    fun reset() {
        hasPicture = false
        used = false
        waitingSinceNs = null
    }
}
