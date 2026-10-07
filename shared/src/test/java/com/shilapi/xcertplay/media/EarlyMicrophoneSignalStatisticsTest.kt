package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class EarlyMicrophoneSignalStatisticsTest {
    @Test fun reportsAreBoundedToFirstThreeSecondsAndTwelveWindows() {
        val stats = EarlyMicrophoneSignalStatistics()
        stats.start(0)
        val pcm = byteArrayOf(0, 0)
        assertNull(stats.observe(pcm, pcm.size, 249_000_000))
        repeat(12) { index ->
            assertNotNull(stats.observe(pcm, pcm.size, (index + 1) * 250_000_000L))
        }
        assertNull(stats.observe(pcm, pcm.size, 3_250_000_000))
        // A fallback recorder must not open a second logging window.
        stats.start(4_000_000_000)
        assertNull(stats.observe(pcm, pcm.size, 4_250_000_000))
    }

    @Test fun reportsMeasureSignedPcmAndKeepFirstNonzeroAcrossWindows() {
        val stats = EarlyMicrophoneSignalStatistics()
        stats.start(1_000_000_000)
        val first = stats.observe(byteArrayOf(0, 0, 0, -128), 4, 1_250_000_000)
        assertEquals("Microphone startup signal elapsedMs=250 samples=2 peak=32768 rms=23170 zeroPermille=500 firstNonzeroMs=250", first)
        val second = stats.observe(byteArrayOf(0, 0, 0, 0), 4, 1_500_000_000)
        assertEquals("Microphone startup signal elapsedMs=500 samples=2 peak=0 rms=0 zeroPermille=1000 firstNonzeroMs=250", second)
    }

    @Test fun zeroOnlyAndLateFirstReadDoNotInventSignalOrExtendWindow() {
        val stats = EarlyMicrophoneSignalStatistics()
        stats.start(0)
        assertTrue(stats.observe(byteArrayOf(0, 0), 2, 250_000_000)!!.endsWith("firstNonzeroMs=none"))
        assertNull(stats.observe(byteArrayOf(1, 0), 2, 3_001_000_000))
    }
}
