package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationPlaybackTest {
    private var now = 0L
    private val tracker = NavigationPlaybackTracker(nowMillis = { now })

    @Test fun creationAloneDoesNotActivateNavigation() {
        tracker.open()
        assertEquals(NavigationPlaybackSnapshot(false, null), tracker.snapshot())
    }

    @Test fun holdsThroughQueuedPlaybackThenExpiresAfterQuietTail() {
        val token = tracker.open()
        tracker.played(token, 14, 500)
        now = 1399
        assertEquals(NavigationPlaybackSnapshot(true, 14), tracker.snapshot())
        now = 1400
        assertEquals(NavigationPlaybackSnapshot(false, null), tracker.snapshot())
    }

    @Test fun laterPcmExtendsActivityAndExpiredStreamCanResume() {
        val token = tracker.open()
        tracker.played(token, 14, 0)
        now = 800
        tracker.played(token, 14, 100)
        now = 1700
        assertEquals(NavigationPlaybackSnapshot(true, 14), tracker.snapshot())
        now = 1800
        assertEquals(NavigationPlaybackSnapshot(false, null), tracker.snapshot())
        tracker.played(token, 14, 0)
        assertEquals(NavigationPlaybackSnapshot(true, 14), tracker.snapshot())
    }

    @Test fun closingOneRendererDoesNotClearAnotherOrAllowClosedRendererToReturn() {
        val first = tracker.open()
        val second = tracker.open()
        tracker.played(first, 14, 0)
        tracker.played(second, 14, 0)
        tracker.close(first)
        assertEquals(NavigationPlaybackSnapshot(true, 14), tracker.snapshot())
        tracker.close(second)
        tracker.played(first, 14, 9999)
        assertEquals(NavigationPlaybackSnapshot(false, null), tracker.snapshot())
    }

    @Test fun usageFallbackAndConflictingRoutesRemainUnspecified() {
        val first = tracker.open()
        val second = tracker.open()
        tracker.played(first, null, 0)
        assertEquals(NavigationPlaybackSnapshot(true, null), tracker.snapshot())
        tracker.played(second, 14, 0)
        assertEquals(NavigationPlaybackSnapshot(true, null), tracker.snapshot())
        tracker.played(first, 13, 0)
        assertEquals(NavigationPlaybackSnapshot(true, null), tracker.snapshot())
        tracker.close(first)
        assertEquals(NavigationPlaybackSnapshot(true, 14), tracker.snapshot())
    }
}
