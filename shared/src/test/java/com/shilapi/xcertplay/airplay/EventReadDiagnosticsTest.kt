package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class EventReadDiagnosticsTest {
    @Test fun incompleteFramesAndParsedMessagesRemainDistinguishableWithBoundedReports() {
        val diagnostics = EventReadDiagnostics()
        assertEquals("airplay event read reads=1 count=20 decrypted=0 parsed=0 encryptedRestBytes=20 plaintextRestBytes=0",
            diagnostics.received(20, 0, 0, 20, 0))
        assertEquals("airplay event read reads=2 count=180 decrypted=150 parsed=1 encryptedRestBytes=0 plaintextRestBytes=5",
            diagnostics.received(180, 150, 1, 0, 5))
        val later = (3..1000).mapNotNull { diagnostics.received(100, 80, 1, 0, 0) }
        assertEquals(5, later.size)
        assertTrue(later.last().startsWith("airplay event read reads=64 "))
        assertTrue(EventReadDiagnostics().received(10, 0, 0, 10, 0)!!.startsWith("airplay event read reads=1 "))
    }
    @Test fun establishedConnectionReportsPeriodicallyAndKeepsIncompleteMessageAtClose() {
        var now = 0L
        val diagnostics = EventReadDiagnostics { now }
        repeat(1000) { diagnostics.received(100, 80, 1, 0, 0) }
        now = 59_999_000_000L
        assertNull(diagnostics.received(20, 0, 0, 20, 0))
        now = 60_000_000_000L
        assertNotNull(diagnostics.received(30, 5, 0, 0, 5))
        assertNull(diagnostics.received(10, 0, 0, 10, 5))
        now += 2_000_000_000L
        val summary = diagnostics.summary()
        assertTrue(summary.contains("reads=1003 receivedBytes=100060 messages=1000"))
        assertTrue(summary.contains("lastReadAgeMs=2000 encryptedRestBytes=10 plaintextRestBytes=5"))
    }

    @Test fun closeSummarySeparatesNoTrafficSuccessfulWritesAndWriteFailures() {
        var now = 0L
        val diagnostics = EventReadDiagnostics { now }
        assertTrue(diagnostics.summary().contains("lastReadAgeMs=-1"))
        assertTrue(diagnostics.summary().contains("lastTransmissionAgeMs=-1"))
        diagnostics.transmitted(500_000_000L, true)
        now = 1_000_000_000L
        diagnostics.transmitted(10_000_000L, false)
        now += 3_000_000_000L
        val summary = diagnostics.summary()
        assertTrue(summary.contains("transmissions=2 transmissionErrors=1"))
        assertTrue(summary.contains("lastTransmissionAgeMs=3000 lastWriteMs=10 maxWriteMs=500"))
    }
}
