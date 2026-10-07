package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MicrophoneCapturePolicyTest {
    @Test fun tangWithBothSwitchesOffKeepsTheWorkingMicPathWithoutEffectHandles() {
        val policy = MicrophoneCapturePolicy.from(MicrophoneProcessing(preferMicSource = true))
        assertTrue(policy.useMicSource)
        assertFalse(policy.configureSystemEffects)
        assertFalse(policy.noiseSuppression)
        assertFalse(policy.echoCancellation)
    }

    @Test fun tangCanRequestBothEffectsWithoutChangingItsInputSource() {
        val policy = MicrophoneCapturePolicy.from(MicrophoneProcessing(
            noiseSuppression = true, echoCancellation = true, preferMicSource = true))
        assertTrue(policy.useMicSource)
        assertTrue(policy.configureSystemEffects)
        assertTrue(policy.noiseSuppression)
        assertTrue(policy.echoCancellation)
    }
    @Test fun diagnosticMicSourceDoesNotDisableRequestedProcessing() {
        val policy = MicrophoneCapturePolicy.from(MicrophoneProcessing(
            noiseSuppression = true, preferMicSource = true))
        assertTrue(policy.useMicSource)
        assertTrue(policy.configureSystemEffects)
        assertTrue(policy.noiseSuppression)
        assertFalse(policy.echoCancellation)
    }
    @Test fun incompatibleDeviceUsesMicAndSkipsEffectsEvenWithSavedRequests() {
        listOf(
            MicrophoneProcessing(noiseSuppression = true, systemEffectsAllowed = false),
            MicrophoneProcessing(echoCancellation = true, systemEffectsAllowed = false),
            MicrophoneProcessing(true, true, false),
            MicrophoneProcessing(systemEffectsAllowed = false),
        ).forEach {
            val policy = MicrophoneCapturePolicy.from(it)
            assertTrue(policy.useMicSource)
            assertFalse(policy.configureSystemEffects)
            assertFalse(policy.noiseSuppression)
            assertFalse(policy.echoCancellation)
        }
    }

    @Test fun ordinaryDeviceKeepsBothEffectsIndependentAndPreservesItsSource() {
        val noise = MicrophoneCapturePolicy.from(MicrophoneProcessing(noiseSuppression = true))
        assertFalse(noise.useMicSource)
        assertTrue(noise.configureSystemEffects)
        assertTrue(noise.noiseSuppression)
        assertFalse(noise.echoCancellation)
        val echo = MicrophoneCapturePolicy.from(MicrophoneProcessing(echoCancellation = true))
        assertTrue(echo.echoCancellation)
        assertFalse(echo.noiseSuppression)
    }

    @Test fun defaultsKeepEffectsOffAndAllowOtherDevicesToConfigureThem() {
        val policy = MicrophoneCapturePolicy.from(MicrophoneProcessing())
        assertFalse(policy.useMicSource)
        assertTrue(policy.configureSystemEffects)
        assertFalse(policy.noiseSuppression)
        assertFalse(policy.echoCancellation)
    }
}
