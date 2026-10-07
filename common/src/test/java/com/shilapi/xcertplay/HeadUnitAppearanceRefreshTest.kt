package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class HeadUnitAppearanceRefreshTest {
    @Test fun successfulInitialWriteStillRequiresOneDelayedReassertion() {
        val refresh = HeadUnitAppearanceRefresh(2_000)
        assertTrue(refresh.request(100))
        refresh.sent(100)
        assertFalse(refresh.isDue(2_099))
        assertTrue(refresh.isDue(2_100))
        refresh.sent(2_100)
        assertFalse(refresh.isDue(20_000))
    }

    @Test fun resumeFocusAndSurfaceCallbacksCoalesceWithoutPostponingRetry() {
        val refresh = HeadUnitAppearanceRefresh(2_000)
        assertTrue(refresh.request(100))
        assertFalse(refresh.request(200))
        assertFalse(refresh.request(2_000))
        assertTrue(refresh.isDue(2_100))
    }

    @Test fun delayedInitialCompletionCannotConsumeTheReassertion() {
        val refresh = HeadUnitAppearanceRefresh(2_000)
        refresh.request(100)
        assertTrue(refresh.isDue(10_000))
        // Even if the write finishes later, it was requested before the retry deadline.
        refresh.sent(100)
        assertTrue(refresh.isDue(10_000))
    }

    @Test fun unavailableEventChannelDoesNotDiscardTheRetry() {
        val refresh = HeadUnitAppearanceRefresh(2_000)
        refresh.request(100)
        assertTrue(refresh.isDue(2_100))
        // No sent() on a false/failed send.
        assertTrue(refresh.isDue(4_100))
        refresh.sent(4_100)
        assertFalse(refresh.isDue(6_100))
        assertTrue(refresh.request(6_100))
        assertTrue(refresh.isDue(8_100))
    }

    @Test fun sessionEndClearsRetryBeforeTheNextConnection() {
        val refresh = HeadUnitAppearanceRefresh(2_000)
        refresh.request(100)
        refresh.clear()
        assertFalse(refresh.isDue(10_000))
        assertTrue(refresh.request(10_000))
        assertFalse(refresh.isDue(10_000))
    }
}
