package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationAudioBufferTest {
    @Test fun monoNavigationQueuesThreeHundredMillisecondsBeforePlaying() {
        val plan = NavigationAudioBuffer.plan(48_000, 1, 7_688)
        assertEquals(57_600, plan.trackBufferBytes)
        assertEquals(28_800, plan.startBytes)
        assertTrue(plan.startBytes * 1000L / 96_000 > 238L)
    }

    @Test fun stereoAndPlatformMinimumsAreRespected() {
        assertEquals(MediaAudioBuffer.Plan(115_200, 57_600),
            NavigationAudioBuffer.plan(48_000, 2, 7_680))
        val required = NavigationAudioBuffer.plan(48_000, 1, 30_000)
        assertEquals(120_000, required.trackBufferBytes)
        assertEquals(30_000, required.startBytes)
    }

    @Test fun smallerGrantedTrackStillStartsBeforeItsBufferFills() {
        val plan = NavigationAudioBuffer.plan(48_000, 1, 7_688)
        assertEquals(17_952, MediaAudioBuffer.startBytesFor(plan.startBytes, 20_000, 2_048))
    }

    @Test fun phoneAssistantAndMediaPlansStayAtTheirExistingValues() {
        assertEquals(MediaAudioBuffer.Plan(16_384, 4_096),
            MediaAudioBuffer.plan(false, 16_000, 1, 1_280, 1000))
        assertEquals(MediaAudioBuffer.Plan(230_400, 192_000),
            MediaAudioBuffer.plan(true, 48_000, 2, 7_680, 1000))
    }
}
