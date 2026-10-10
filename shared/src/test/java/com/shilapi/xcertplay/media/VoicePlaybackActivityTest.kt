package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VoicePlaybackActivityTest {
    private var now = 0L
    private val activity = VoicePlaybackActivity(nowMillis = { now })

    @Test fun retainedVoiceStreamStopsBlockingWhenWrittenPcmDrains() {
        assertFalse(activity.active())
        activity.played(200)
        now = 699
        assertTrue(activity.active())
        now = 700
        assertFalse(activity.active())
        now = 60_000
        assertFalse(activity.active())
    }

    @Test fun pendingPcmLongerThanTailRemainsActiveAndNextBurstReactivates() {
        activity.played(2000)
        now = 1800
        assertTrue(activity.active())
        now = 2500
        assertFalse(activity.active())
        activity.played(100)
        assertTrue(activity.active())
        now = 3100
        assertFalse(activity.active())
    }

    @Test fun recentSuccessfulWriteProtectsVoiceWhenPlaybackHeadIsUnavailable() {
        activity.played(0)
        now = 499
        assertTrue(activity.active())
        activity.played(0)
        now = 998
        assertTrue(activity.active())
        now = 999
        assertFalse(activity.active())
    }

    @Test fun closeImmediatelyClearsActivityAndRejectsLateWrites() {
        activity.played(5000)
        activity.close()
        assertFalse(activity.active())
        activity.played(5000)
        assertFalse(activity.active())
    }

    @Test fun closingOldRendererDoesNotClearAnotherVoiceRenderer() {
        val other = VoicePlaybackActivity(nowMillis = { now })
        activity.played(100)
        other.played(300)
        activity.close()
        assertTrue(other.active())
        now = 800
        assertFalse(other.active())
    }
}
