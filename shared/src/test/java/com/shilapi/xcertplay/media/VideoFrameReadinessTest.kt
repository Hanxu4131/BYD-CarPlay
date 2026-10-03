package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoFrameReadinessTest {
    @Test fun releasedPicturesConfirmWhenThePlatformCallbackIsMissing() {
        val events = mutableListOf<String>()
        val readiness = VideoFrameReadiness({ true }, events::add)
        assertFalse(readiness.outputReleased())
        assertTrue(readiness.outputReleased())
        readiness.decodedOutputsReady()
        readiness.codecRendered()
        assertEquals(listOf("decoded_output"), events)
        assertFalse(readiness.outputReleased())
    }

    @Test fun actualPresentationTakesPriorityAndOnlySignalsOnce() {
        val events = mutableListOf<String>()
        val readiness = VideoFrameReadiness({ true }, events::add)
        readiness.codecRendered()
        readiness.codecRendered()
        readiness.outputReleased()
        assertFalse(readiness.outputReleased())
        readiness.decodedOutputsReady()
        assertEquals(listOf("codec_callback"), events)
    }

    @Test fun oldDecoderCannotConfirmAReplacementSurface() {
        var current = true
        val events = mutableListOf<String>()
        val readiness = VideoFrameReadiness({ current }, events::add)
        readiness.outputReleased()
        assertTrue(readiness.outputReleased())
        current = false
        readiness.decodedOutputsReady()
        readiness.codecRendered()
        assertTrue(events.isEmpty())
    }

    @Test fun earlyCallbackBeforeDecoderAssignmentDoesNotConsumeConfirmation() {
        var current = false
        val events = mutableListOf<String>()
        val readiness = VideoFrameReadiness({ current }, events::add)
        readiness.codecRendered()
        current = true
        readiness.outputReleased()
        assertTrue(readiness.outputReleased())
        readiness.decodedOutputsReady()
        assertEquals(listOf("decoded_output"), events)
    }
}
