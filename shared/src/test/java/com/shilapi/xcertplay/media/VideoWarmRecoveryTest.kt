package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoWarmRecoveryTest {
    @Test fun startupAndNonQueueFailuresAlwaysRequireRebuild() {
        val recovery = VideoWarmRecovery()
        assertFalse(recovery.canFlush(true, true, true, true))
        recovery.pictureDecoded()
        assertFalse(recovery.canFlush(false, true, true, true))
        assertFalse(recovery.canFlush(true, false, true, true))
        assertFalse(recovery.canFlush(true, true, false, true))
        assertFalse(recovery.canFlush(true, true, true, false))
        assertTrue(recovery.canFlush(true, true, true, true))
    }

    @Test fun flushGetsOneBoundedChanceEvenWithNoFurtherPackets() {
        val recovery = VideoWarmRecovery(200L)
        recovery.pictureDecoded()
        recovery.flushed(1000L)
        assertFalse(recovery.canFlush(true, true, true, true))
        assertFalse(recovery.timedOut(1199L))
        assertTrue(recovery.timedOut(1200L))
    }

    @Test fun resumedPictureStopsTimeoutButCannotCreateAFlushLoop() {
        val recovery = VideoWarmRecovery(200L)
        recovery.pictureDecoded()
        recovery.flushed(1000L)
        recovery.pictureDecoded()
        assertFalse(recovery.timedOut(2000L))
        assertFalse(recovery.canFlush(true, true, true, true))
    }

    @Test fun replacementCodecMustProduceItsOwnPictureBeforeAnotherFlush() {
        val recovery = VideoWarmRecovery(200L)
        recovery.pictureDecoded()
        recovery.flushed(1000L)
        recovery.reset()
        assertFalse(recovery.timedOut(2000L))
        assertFalse(recovery.canFlush(true, true, true, true))
        recovery.pictureDecoded()
        assertTrue(recovery.canFlush(true, true, true, true))
    }

    @Test fun warmCodecKeepsReadinessWhileTimingRejectsThePreFlushEpoch() {
        val events = mutableListOf<String>()
        val readiness = VideoFrameReadiness({ true }, events::add)
        val timing = VideoFrameTiming()
        val recovery = VideoWarmRecovery()
        val oldEpoch = timing.reset()
        timing.submitted(1L, 10L, 20L)
        readiness.codecRendered()
        recovery.pictureDecoded()
        recovery.flushed(100L)
        val warmEpoch = timing.reset()
        timing.submitted(1L, 200L, 210L)
        assertNull(timing.presented(oldEpoch, 1L, 250L))
        assertEquals(VideoFrameTiming.Ages(40L, 50L), timing.presented(warmEpoch, 1L, 250L))
        readiness.codecRendered()
        recovery.pictureDecoded()
        assertEquals(listOf("codec_callback"), events)
        assertFalse(recovery.timedOut(3_000_000_000L))
    }
}
