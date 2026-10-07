package com.shilapi.xcertplay.hud

/** Binding retries are independent of the connected HUD's 300 ms navigation updates. */
internal class BydHudBindRetryPolicy {
    private var nextAttempt = 0L
    private var failures = 0

    fun ready(now: Long): Boolean = now >= nextAttempt

    fun missing(now: Long) {
        nextAttempt = now + 60_000L
    }

    fun failed(now: Long) {
        val delay = when (failures) {
            0 -> 5_000L
            1 -> 15_000L
            else -> 60_000L
        }
        failures = (failures + 1).coerceAtMost(3)
        nextAttempt = now + delay
    }

    fun reset() {
        failures = 0
        nextAttempt = 0L
    }
}
