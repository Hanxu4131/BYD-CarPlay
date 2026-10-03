package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VideoFrameTimingTest {
    @Test fun tracksQueueCodecOutputAndActualPresentationSeparately() {
        val timing = VideoFrameTiming()
        val epoch = timing.reset()
        assertEquals(20_000_000L, timing.submitted(30_000L, 10_000_000L, 30_000_000L))
        assertEquals(VideoFrameTiming.Ages(40_000_000L, 60_000_000L),
            timing.released(30_000L, 70_000_000L))
        assertEquals(VideoFrameTiming.Ages(50_000_000L, 70_000_000L),
            timing.presented(epoch, 30_000L, 80_000_000L))
        assertNull(timing.presented(epoch, 30_000L, 90_000_000L))
    }

    @Test fun oldCodecOrSurfaceCallbacksCannotMatchReusedPts() {
        val timing = VideoFrameTiming()
        val oldEpoch = timing.reset()
        timing.submitted(1L, 10L, 20L)
        val newEpoch = timing.reset()
        timing.submitted(1L, 100L, 200L)
        assertFalse(timing.isCurrent(oldEpoch))
        assertNull(timing.presented(oldEpoch, 1L, 300L))
        assertEquals(VideoFrameTiming.Ages(100L, 200L), timing.presented(newEpoch, 1L, 300L))
    }

    @Test fun missingCallbacksKeepTrackingBoundedAndDoNotInventPresentation() {
        val timing = VideoFrameTiming(maxTrackedFrames = 2)
        val epoch = timing.reset()
        for (pts in 1L..3L) timing.submitted(pts, pts * 10, pts * 10 + 1)
        assertNull(timing.released(1L, 100L))
        assertNull(timing.presented(epoch, 1L, 100L))
        assertEquals(VideoFrameTiming.Ages(79L, 80L), timing.released(2L, 100L))
        val stats = VideoStats(nanoTime = { 100L })
        stats.onSubmitted(20_000_000L)
        stats.onReleasedAge(VideoFrameTiming.Ages(40_000_000L, 60_000_000L))
        val summary = stats.timingSummary(5.0)
        org.junit.Assert.assertTrue(summary.contains("recvToRelease avg=60ms max=60ms n=1"))
        org.junit.Assert.assertTrue(summary.contains("recvToPresent avg=-1ms max=-1ms n=0"))
    }

    @Test fun rejectsNonMonotonicEventsWithoutConsumingTheValidFrame() {
        val timing = VideoFrameTiming()
        val epoch = timing.reset()
        assertNull(timing.submitted(1L, 20L, 10L))
        timing.submitted(2L, 10L, 20L)
        assertNull(timing.released(2L, 19L))
        assertNull(timing.presented(epoch, 2L, 19L))
        assertEquals(VideoFrameTiming.Ages(10L, 20L), timing.presented(epoch, 2L, 30L))
    }
}
