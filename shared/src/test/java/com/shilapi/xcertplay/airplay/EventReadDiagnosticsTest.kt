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
}
