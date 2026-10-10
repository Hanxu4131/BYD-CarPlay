package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MicrophoneEffectStartupTest {
    @Test fun eitherWorkingEffectSurvivesConfirmedFailureOfTheOther() {
        for ((aec, ns) in listOf(VoiceEffectState.ENABLED to VoiceEffectState.DISABLED,
            VoiceEffectState.DISABLED to VoiceEffectState.ENABLED)) {
            val result = MicrophoneEffectStartup(true, true, aec, ns)
            assertFalse(result.needsRawFallback)
            assertTrue(result.hasEnabledEffect)
            assertTrue(result.partial)
        }
    }
    @Test fun unknownRequestedEffectKeepsOriginalRawFallbackEvenWithWorkingPeer() {
        for ((aec, ns) in listOf(VoiceEffectState.ENABLED to VoiceEffectState.UNCONFIRMED,
            VoiceEffectState.UNCONFIRMED to VoiceEffectState.ENABLED)) {
            assertTrue(MicrophoneEffectStartup(true, true, aec, ns).needsRawFallback)
        }
    }
    @Test fun bothRequestedEffectsFailedKeepsOriginalRawFallback() {
        assertTrue(MicrophoneEffectStartup(true, true,
            VoiceEffectState.DISABLED, VoiceEffectState.DISABLED).needsRawFallback)
        assertTrue(MicrophoneEffectStartup(true, false,
            VoiceEffectState.DISABLED, VoiceEffectState.DISABLED).needsRawFallback)
    }
    @Test fun disabledAndBypassedProcessingDoesNotCreateANewFallback() {
        for (state in listOf(VoiceEffectState.DISABLED, VoiceEffectState.UNCONFIRMED)) {
            val result = MicrophoneEffectStartup(false, false, state, state)
            assertFalse(result.needsRawFallback)
            assertFalse(result.hasEnabledEffect)
            assertFalse(result.partial)
        }
    }
}
