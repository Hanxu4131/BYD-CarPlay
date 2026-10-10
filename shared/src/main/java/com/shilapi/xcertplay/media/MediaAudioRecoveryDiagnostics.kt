package com.shilapi.xcertplay.media

/** Numeric snapshots only, emitted once per media recovery transition. */
internal class MediaAudioRecoveryDiagnostics {
    data class Snapshot(
        val nowNs: Long,
        val head: Int,
        val remainingPcmBytes: Long,
        val frameBytes: Int,
        val queuePackets: Int,
        val pendingInput: Boolean,
        val underrunDelta: Int,
        val lastRxNs: Long,
    )

    private var pausedAtNs: Long? = null
    val isPaused: Boolean get() = pausedAtNs != null

    fun paused(snapshot: Snapshot): String {
        pausedAtNs = snapshot.nowNs
        return line("paused", snapshot, 0)
    }

    fun resumed(snapshot: Snapshot): String? {
        val paused = pausedAtNs ?: return null
        pausedAtNs = null
        return line("resumed", snapshot, ((snapshot.nowNs - paused) / 1_000_000L).coerceAtLeast(0))
    }

    private fun line(phase: String, s: Snapshot, pauseMs: Long): String =
        "audio recovery phase=$phase headFrames=${s.head.toLong() and 0xffff_ffffL} " +
            "remainingPcmFrames=${s.remainingPcmBytes.coerceAtLeast(0) / s.frameBytes.coerceAtLeast(1)} " +
            "queuePackets=${s.queuePackets} pendingInput=${if (s.pendingInput) 1 else 0} " +
            "underrunDelta=${s.underrunDelta.coerceAtLeast(0)} " +
            "sinceRxMs=${if (s.lastRxNs == 0L) -1 else ((s.nowNs - s.lastRxNs) / 1_000_000L).coerceAtLeast(0)} " +
            "pauseMs=$pauseMs"
}
