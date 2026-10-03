package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchSendDiagnosticsTest {
    @Test fun onlyDownChangesLogWhileMoveContributesQueueAndSendMaxima() {
        val probe = TouchSendDiagnostics()
        assertEquals("touch uplink contacts=1 down=1 queue=2ms send=3ms sent=true moves=0 queueMax=2ms sendMax=3ms",
            probe.onSent(1, 1, 1_000_000, 3_000_000, 6_000_000, true))
        assertNull(probe.onSent(1, 1, 10_000_000, 18_000_000, 29_000_000, true))
        assertEquals("touch uplink contacts=1 down=0 queue=1ms send=2ms sent=false moves=1 queueMax=8ms sendMax=11ms",
            probe.onSent(1, 0, 30_000_000, 31_000_000, 33_000_000, false))
        assertEquals("touch uplink contacts=1 down=1 queue=0ms send=0ms sent=true moves=0 queueMax=0ms sendMax=0ms",
            probe.onSent(1, 1, 34_000_000, 34_000_000, 34_000_000, true))
    }

    @Test fun additionalFingerIsAStateTransition() {
        val probe = TouchSendDiagnostics()
        probe.onSent(1, 1, 1, 2, 3, true)
        assertEquals("touch uplink contacts=2 down=2 queue=0ms send=0ms sent=true moves=0 queueMax=0ms sendMax=0ms",
            probe.onSent(2, 2, 4, 5, 6, true))
    }
}
