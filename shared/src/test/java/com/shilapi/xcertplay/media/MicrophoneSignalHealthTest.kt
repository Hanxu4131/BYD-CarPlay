package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class MicrophoneSignalHealthTest {
    @Test fun silenceRecoveryUsesCapturedSampleDurationAndRunsOnlyOnce() {
        val signal = MicrophoneSignalHealth(16000)
        val second = ByteArray(32000)
        assertFalse(signal.observe(second, second.size))
        assertTrue(signal.observe(second, second.size))
        assertFalse(signal.observe(second, second.size))
    }

    @Test fun realSignalResetsTheContinuousZeroWindow() {
        val signal = MicrophoneSignalHealth(16000)
        val second = ByteArray(32000)
        assertFalse(signal.observe(second, second.size))
        assertFalse(signal.observe(byteArrayOf(1, 0), 2))
        assertFalse(signal.observe(second, second.size))
        assertTrue(signal.observe(second, second.size))
    }

    @Test fun amplitudeStatisticsDecodeSignedLittleEndianPcmAndResetTheWindow() {
        val signal = MicrophoneSignalHealth(2)
        assertFalse(signal.observe(byteArrayOf(0, -128, -1, 127), 4))
        assertEquals("samples=2 peak=32768 rms=32767 zeroMs=0", signal.summary())
        assertEquals("samples=0 peak=0 rms=0 zeroMs=0", signal.summary())
    }

    @Test fun reportingCannotResetTheRecoveryThreshold() {
        val signal = MicrophoneSignalHealth(16000)
        val second = ByteArray(32000)
        assertFalse(signal.observe(second, second.size))
        signal.summary()
        assertTrue(signal.observe(second, second.size))
    }
}
