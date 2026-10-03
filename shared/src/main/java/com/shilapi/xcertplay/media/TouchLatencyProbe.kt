package com.shilapi.xcertplay.media

/** Diagnostic link to the next received and rendered main-screen frame, not visible app readiness. */
internal object TouchLatencyProbe {
    private var pendingTouchNs = 0L
    private var pendingRenderTouchNs = 0L
    @Volatile var maxSendNs = 0L

    @Synchronized fun onTouchSent(sentAtNs: Long, sendDurationNs: Long) {
        if (pendingTouchNs == 0L) pendingTouchNs = sentAtNs
        if (pendingRenderTouchNs == 0L) pendingRenderTouchNs = sentAtNs
        if (sendDurationNs > maxSendNs) maxSendNs = sendDurationNs
    }

    /** Returns latency to the next received frame, which may still contain the old screen. */
    @Synchronized fun onFrame(nowNs: Long): Long {
        val touch = pendingTouchNs
        if (touch == 0L || nowNs < touch) return -1
        pendingTouchNs = 0L
        return nowNs - touch
    }

    /** Independent sample so reception cannot consume the render measurement. */
    @Synchronized fun onRendered(nowNs: Long): Long {
        val touch = pendingRenderTouchNs
        if (touch == 0L || nowNs < touch) return -1
        pendingRenderTouchNs = 0L
        return nowNs - touch
    }

    @Synchronized fun takeMaxSendNs(): Long = maxSendNs.also { maxSendNs = 0L }
}
