package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NtpCorrectionDiagnosticsTest {
    @Test
    fun reportsAppliedCorrectionAndSelectedRoundTripTime() {
        var nowMs = 10L
        val diagnostics = NtpCorrectionDiagnostics(nowMs = { nowMs })

        val first = diagnostics.record(250_000_000L, isStep = true, selectedRttMs = 12.5)

        assertTrue(first!!.isStep)
        assertEquals(250.0, first.appliedCorrectionMs, 0.001)
        assertEquals(12.5, first.selectedRttMs, 0.001)
        assertEquals(250.0, first.cumulativeCorrectionMs, 0.001)
        assertEquals(1L, first.correctionCount)
        assertEquals(1L, first.stepCount)
        assertEquals(0L, first.coalescedCorrectionCount)

        nowMs += 29_999
        assertNull(diagnostics.record(8_000_000L, isStep = false, selectedRttMs = 8.0))
        nowMs++
        val next = diagnostics.record(-1_000_000L, isStep = false, selectedRttMs = 9.0)

        assertFalse(next!!.isStep)
        assertEquals(-1.0, next.appliedCorrectionMs, 0.001)
        assertEquals(257.0, next.cumulativeCorrectionMs, 0.001)
        assertEquals(3L, next.correctionCount)
        assertEquals(1L, next.stepCount)
        assertEquals(1L, next.coalescedCorrectionCount)
    }

    @Test
    fun coalescesLargeStepsButRetainsTheirCountsAndNetCorrection() {
        var nowMs = 0L
        val diagnostics = NtpCorrectionDiagnostics(minimumIntervalMs = 30_000, nowMs = { nowMs })

        diagnostics.record(200_000_000L, isStep = true, selectedRttMs = 15.0)
        nowMs = 2_000
        assertNull(diagnostics.record(-400_000_000L, isStep = true, selectedRttMs = 18.0))
        nowMs = 30_000
        val summary = diagnostics.record(100_000_000L, isStep = true, selectedRttMs = 20.0)

        assertTrue(summary!!.isStep)
        assertEquals(100.0, summary.appliedCorrectionMs, 0.001)
        assertEquals(-100.0, summary.cumulativeCorrectionMs, 0.001)
        assertEquals(3L, summary.correctionCount)
        assertEquals(3L, summary.stepCount)
        assertEquals(1L, summary.coalescedCorrectionCount)
    }
}
