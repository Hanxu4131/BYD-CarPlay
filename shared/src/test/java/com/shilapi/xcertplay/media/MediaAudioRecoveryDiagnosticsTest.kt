package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MediaAudioRecoveryDiagnosticsTest {
    private fun snapshot(now: Long, head: Int = 0, lastRx: Long = 0) =
        MediaAudioRecoveryDiagnostics.Snapshot(now, head, 9_344, 4, 6, true, 2, lastRx)

    @Test fun ordinaryStartupAndRepeatedResumeDoNotProduceRecoveryEvents() {
        val diagnostics = MediaAudioRecoveryDiagnostics()
        assertNull(diagnostics.resumed(snapshot(1_000_000)))
        diagnostics.paused(snapshot(2_000_000))
        assertNotNull(diagnostics.resumed(snapshot(3_000_000)))
        assertNull(diagnostics.resumed(snapshot(4_000_000)))
    }

    @Test fun eventIncludesUnsignedHeadResidualAndCaptureGapWithoutPayload() {
        val line = MediaAudioRecoveryDiagnostics().paused(snapshot(1_312_000_000, -1, 1_000_000_000))
        assertTrue(line.contains("headFrames=4294967295"))
        assertTrue(line.contains("remainingPcmFrames=2336"))
        assertTrue(line.contains("queuePackets=6 pendingInput=1 underrunDelta=2 sinceRxMs=312 pauseMs=0"))
    }

    @Test fun eachRecoveryUsesItsOwnPauseDurationAndUnknownArrival() {
        val diagnostics = MediaAudioRecoveryDiagnostics()
        diagnostics.paused(snapshot(1_000_000_000))
        val first = diagnostics.resumed(snapshot(1_646_000_000))!!
        assertTrue(first.contains("pauseMs=646"))
        assertTrue(first.contains("sinceRxMs=-1"))
        diagnostics.paused(snapshot(2_000_000_000))
        assertTrue(diagnostics.resumed(snapshot(2_040_000_000))!!.contains("pauseMs=40"))
    }
}
