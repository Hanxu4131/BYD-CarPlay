package com.shilapi.xcertplay.airplay

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class AppearanceResponseDiagnosticsTest {
    private fun response(cseq: Int, status: Int = 200, body: ByteArray = ByteArray(0)) =
        RtspMessage.Request("RTSP/1.0", status.toString(), "OK", mapOf("cseq" to cseq.toString()), body)

    @Test fun transportSuccessStillShowsNegativeApplicationStatusWithoutPrivateFields() {
        val diagnostics = AppearanceResponseDiagnostics()
        diagnostics.record(7, "uiAppearanceUpdate")
        // Independent plistlib fixture: status=-6722, error=0, uuid=private-value-must-not-appear.
        val body = Base64.getDecoder().decode(
            "YnBsaXN0MDDTAQIDBAUGVWVycm9yVnN0YXR1c1R1dWlkEAAT////////5b5fEB1wcml2YXRlLXZhbHVlLW11c3Qtbm90LWFwcGVhcggPFRwhIywAAAAAAAABAQAAAAAAAAAHAAAAAAAAAAAAAAAAAAAATA==")
        assertEquals("airplay appearance response type=uiAppearanceUpdate cseq=7 status=200 body.status=-6722 body.error=0",
            diagnostics.response(RtspMessage.parseMessages(("RTSP/1.0 200 OK\r\nCSeq: 7\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII) + body).messages.single()))
        assertNull(diagnostics.response(response(7, body = body)))
    }

    @Test fun touchResponsesAndIncomingRequestsDoNotProduceAppearanceLogs() {
        val diagnostics = AppearanceResponseDiagnostics()
        diagnostics.record(1, "hidSendReport")
        assertNull(diagnostics.response(response(1)))
        diagnostics.record(2, "mapAppearanceUpdate")
        assertNull(diagnostics.response(RtspMessage.Request("POST", "/command", "RTSP/1.0", mapOf("cseq" to "2"), ByteArray(0))))
        assertEquals("airplay appearance response type=mapAppearanceUpdate cseq=2 status=400",
            diagnostics.response(response(2, 400).copy(method = "HTTP/1.1")))
    }

    @Test fun unansweredCommandsAreBoundedAndClearedAtSessionEnd() {
        val diagnostics = AppearanceResponseDiagnostics()
        for (cseq in 1..33) diagnostics.record(cseq, "setNightMode")
        assertNull(diagnostics.response(response(1)))
        assertNotNull(diagnostics.response(response(2)))
        assertNotNull(diagnostics.response(response(33)))
        diagnostics.clear()
        assertNull(diagnostics.response(response(3)))
        diagnostics.record(34, "setNightMode")
        diagnostics.forget(34)
        assertNull(diagnostics.response(response(34)))
    }
    @Test fun uncorrelatedEventRepliesHaveBoundedNumericSummaries() {
        val diagnostics = AppearanceResponseDiagnostics()
        val missing = response(1).copy(headers = emptyMap())
        val reports = (1..100).mapNotNull { diagnostics.unmatchedResponse(missing) }
        assertEquals(7, reports.size)
        assertEquals("airplay event response unmatched count=64 missingCseq=64 status=200 cseq=none", reports.last())
        diagnostics.clear()
        assertEquals("airplay event response unmatched count=1 missingCseq=0 status=400 cseq=55",
            diagnostics.unmatchedResponse(response(55, 400)))
        assertNull(diagnostics.unmatchedResponse(response(1).copy(method = "POST")))
    }

    @Test fun transmissionSummariesIncludeOnlyAppearancePolicyAndSequence() {
        val diagnostics = AppearanceResponseDiagnostics()
        val commands = AppearanceCommands.build(CarPlayAppearance(false, true), false)
        assertEquals("airplay appearance tx type=setNightMode cseq=1 night=false",
            diagnostics.transmission(1, commands[0]))
        assertEquals("airplay appearance tx type=uiAppearanceUpdate cseq=2 mode=0 setting=2",
            diagnostics.transmission(2, commands[1]))
        assertEquals("airplay appearance tx type=mapAppearanceUpdate cseq=3 mode=0 setting=2",
            diagnostics.transmission(3, commands[2]))
        assertNotNull(diagnostics.response(response(2)))
        assertNull(diagnostics.transmission(4, mapOf("type" to "hidSendReport", "params" to mapOf("hidReport" to byteArrayOf(1)))))
        assertNull(diagnostics.response(response(4)))
    }

}
