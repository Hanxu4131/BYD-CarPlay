package com.shilapi.xcertplay.media

/** Free ready output before giving up on a temporarily full codec input queue. */
internal object CodecInputRetry {
    fun acquire(
        dequeue: () -> Int,
        drain: () -> Unit,
        running: () -> Boolean,
        onRetry: () -> Unit = {},
        nowNanos: () -> Long = System::nanoTime,
        budgetNanos: Long = 100_000_000L,
    ): Int {
        if (!running()) return -1
        val deadline = nowNanos() + budgetNanos
        var index = dequeue()
        while (index < 0 && running() && nowNanos() < deadline) {
            onRetry()
            drain()
            if (!running()) return -1
            // Draining output can take time in AudioTrack.write. Accept the newly freed slot
            // even then, without spending another retry period on the same packet.
            index = dequeue()
        }
        return index
    }
}
