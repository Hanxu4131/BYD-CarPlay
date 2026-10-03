package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchLatencyProbeTest {
    @Test fun receptionAndRenderingConsumeIndependentSamples() {
        TouchLatencyProbe.onFrame(Long.MAX_VALUE)
        TouchLatencyProbe.onRendered(Long.MAX_VALUE)
        TouchLatencyProbe.takeMaxSendNs()
        TouchLatencyProbe.onTouchSent(100L, 7L)
        TouchLatencyProbe.onTouchSent(110L, 3L)
        assertEquals(-1L, TouchLatencyProbe.onRendered(99L))
        assertEquals(20L, TouchLatencyProbe.onFrame(120L))
        assertEquals(-1L, TouchLatencyProbe.onFrame(130L))
        assertEquals(50L, TouchLatencyProbe.onRendered(150L))
        assertEquals(-1L, TouchLatencyProbe.onRendered(160L))
        assertEquals(7L, TouchLatencyProbe.takeMaxSendNs())
        assertEquals(0L, TouchLatencyProbe.takeMaxSendNs())
    }
}
