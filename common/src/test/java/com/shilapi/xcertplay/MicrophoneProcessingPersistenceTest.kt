package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class MicrophoneProcessingPersistenceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test fun existingAndFreshInstallStartWithoutExtraProcessing() {
        assertFalse(AirPlayPersistence.loadMicrophoneNoiseSuppression(context))
        assertFalse(AirPlayPersistence.loadMicrophoneEchoCancellation(context))
    }

    @Test fun togglesPersistIndependentlyAndCanBeTurnedOff() {
        AirPlayPersistence.saveMicrophoneNoiseSuppression(context, true)
        assertTrue(AirPlayPersistence.loadMicrophoneNoiseSuppression(context))
        assertFalse(AirPlayPersistence.loadMicrophoneEchoCancellation(context))
        AirPlayPersistence.saveMicrophoneEchoCancellation(context, true)
        AirPlayPersistence.saveMicrophoneNoiseSuppression(context, false)
        assertFalse(AirPlayPersistence.loadMicrophoneNoiseSuppression(context))
        assertTrue(AirPlayPersistence.loadMicrophoneEchoCancellation(context))
        AirPlayPersistence.saveMicrophoneEchoCancellation(context, false)
        assertFalse(AirPlayPersistence.loadMicrophoneEchoCancellation(context))
    }
}
