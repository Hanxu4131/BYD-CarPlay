package com.shilapi.xcertplay.hud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BydHudBindRetryPolicyTest {
    @Test fun missingServiceWaitsAMinuteBetweenChecks() {
        val retry = BydHudBindRetryPolicy()
        assertTrue(retry.ready(100L))
        retry.missing(100L)
        for (now in 400L until 60_100L step 300L) assertFalse(retry.ready(now))
        assertTrue(retry.ready(60_100L))
        retry.missing(60_100L)
        assertFalse(retry.ready(120_099L))
        assertTrue(retry.ready(120_100L))
    }

    @Test fun failedBindingsBackOffAndStayAtOneMinute() {
        val retry = BydHudBindRetryPolicy()
        var now = 100L
        for (delay in listOf(5_000L, 15_000L, 60_000L, 60_000L)) {
            retry.failed(now)
            assertFalse(retry.ready(now + delay - 1L))
            now += delay
            assertTrue(retry.ready(now))
        }
    }

    @Test fun successfulConnectionOrNewSessionRestartsImmediately() {
        val retry = BydHudBindRetryPolicy()
        retry.failed(100L)
        retry.failed(5_100L)
        retry.missing(20_100L)
        retry.reset()
        assertTrue(retry.ready(20_101L))
        retry.failed(20_101L)
        assertFalse(retry.ready(25_100L))
        assertTrue(retry.ready(25_101L))
    }
}
