package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoStatsTest {
    @Test fun secondaryStreamCannotConsumeMainTouchOrSendMeasurements() {
        TouchLatencyProbe.onFrame(Long.MAX_VALUE)
        TouchLatencyProbe.onRendered(Long.MAX_VALUE)
        TouchLatencyProbe.takeMaxSendNs()
        TouchLatencyProbe.onTouchSent(100L, 9L)
        val secondary = VideoStats(" stream=110") { 120L }
        secondary.onReceived(12)
        secondary.onRendered()
        assertEquals(30L, TouchLatencyProbe.onFrame(130L))
        assertEquals(40L, TouchLatencyProbe.onRendered(140L))
        assertEquals(9L, TouchLatencyProbe.takeMaxSendNs())
    }

    @Test fun mainRenderingConsumesOnlyRenderSample() {
        TouchLatencyProbe.onFrame(Long.MAX_VALUE)
        TouchLatencyProbe.onRendered(Long.MAX_VALUE)
        TouchLatencyProbe.onTouchSent(100L, 1L)
        val main = VideoStats(nanoTime = { 120L })
        main.onRendered()
        assertEquals(-1L, TouchLatencyProbe.onRendered(130L))
        assertEquals(30L, TouchLatencyProbe.onFrame(130L))
        TouchLatencyProbe.takeMaxSendNs()
    }
}
