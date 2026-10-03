package com.shilapi.xcertplay.media

/** Runs on the touch executor; logs state changes while MOVE costs only counters. */
internal class TouchSendDiagnostics {
    private var lastDownCount = 0
    private var moves = 0
    private var maxQueueNs = 0L
    private var maxSendNs = 0L

    fun onSent(contacts: Int, downCount: Int, queuedAtNs: Long, startNs: Long, endNs: Long, sent: Boolean): String? {
        val queueNs = (startNs - queuedAtNs).coerceAtLeast(0)
        val sendNs = (endNs - startNs).coerceAtLeast(0)
        maxQueueNs = maxOf(maxQueueNs, queueNs)
        maxSendNs = maxOf(maxSendNs, sendNs)
        if (downCount == lastDownCount) { moves++; return null }
        lastDownCount = downCount
        val line = "touch uplink contacts=$contacts down=$downCount queue=${queueNs / 1_000_000}ms " +
            "send=${sendNs / 1_000_000}ms sent=$sent moves=$moves " +
            "queueMax=${maxQueueNs / 1_000_000}ms sendMax=${maxSendNs / 1_000_000}ms"
        moves = 0
        maxQueueNs = 0L
        maxSendNs = 0L
        return line
    }
}
