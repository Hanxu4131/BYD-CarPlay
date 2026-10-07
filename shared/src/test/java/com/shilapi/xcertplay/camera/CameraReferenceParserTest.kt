package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test

class CameraReferenceParserTest {
    private fun parse(text: String) = CameraReferenceParser.parse(text.toByteArray())
    @Test fun whitelistDropsPrivateFieldsAndDoesNotVerify() {
        val parsed = parse("""{"CameraID":2,"CameraMode":4,"SrcW":1080,"SrcH":360,"ChannelOrder":1,"VIN":"private","IMEI":null,"nested":{"hotspot":["private"]}}""")
        assertEquals(CameraSourceReference(2, "4", 1080, 360, "1", false), parsed.source)
        assertTrue(parsed.angles.isEmpty()); assertTrue(parsed.corners.isEmpty())
    }
    @Test fun radiansUseOnlyDocumentedAngleIndices() {
        val parsed = parse("""{"BSD_FE_LB":[1.5707963267948966,-0.7853981633974483,0,1,2,3,4,5,6,7,0.5235987755982988]}""")
        val a = parsed.angles.getValue("BSD_FE_LB")
        assertEquals(90f, a.yawDegrees, .0001f); assertEquals(30f, a.pitchDegrees, .0001f); assertEquals(-45f, a.rollDegrees, .0001f)
        assertEquals(listOf(0.0,1.0,2.0,3.0,4.0,5.0,6.0,7.0), parsed.corners.getValue("BSD_FE_LB"))
        assertFalse(parsed.source.verified)
    }
    @Test fun encodedNumericArrayIsAccepted() {
        assertEquals(0f, parse("""{"BSD_FE_RF":"[0,0,0,0,0,0,0,0,0,0,0]"}""").angles.getValue("BSD_FE_RF").yawDegrees, 0f)
    }
    @Test fun localReferenceScalarStringsAndCsvAreAccepted() {
        val parsed = parse("""{"CameraID":"2","CameraMode":"4","SrcW":"1080","SrcH":"360","ChannelOrder":"1","BSD_FE_LF":"0,0,0,1,2,3,4,5,6,7,0"}""")
        assertEquals(CameraSourceReference(2,"4",1080,360,"1",false), parsed.source)
        assertEquals(8, parsed.corners.getValue("BSD_FE_LF").size)
        assertEquals(0f, parsed.angles.getValue("BSD_FE_LF").pitchDegrees, 0f)
        listOf("0,0", "0,0,0,0,0,0,0,0,0,0,NaN", "0,0,0,0,0,0,0,0,0,0,", "0,0,0,0,0,0,0,0,0,0,Infinity").forEach {
            try { parse("{\"BSD_FE_LB\":\"$it\"}"); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun malformedKnownFieldsAreRejected() {
        listOf("""{"CameraID":1.5}""", """{"SrcW":0}""",
            """{"CameraID":2147483648}""", """{"CameraID":1,"CameraID":2}""",
            """{"BSD_FE_LB":[0,0]}""", """{"BSD_FE_LB":[0,0,0,0,0,0,0,0,0,0,"0"]}""",
            """{"BSD_FE_LB":[0,0,0,0,0,0,0,0,0,0,1e999]}""",
            """{"CameraID":1} trailing""", """{"CameraID":01}""", """{"CameraID":1,}""").forEach {
            try { parse(it); fail(it) } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }
    @Test fun sizeDepthAndUnknownOnlyAreRejected() {
        try { CameraReferenceParser.parse(ByteArray(CameraReferenceParser.MAX_BYTES + 1)); fail() } catch (_: IllegalArgumentException) { }
        try { parse("{\"x\":" + "[".repeat(26) + "0" + "]".repeat(26) + ",\"CameraID\":1}"); fail() } catch (_: IllegalArgumentException) { }
        try { parse("""{"VIN":"discard"}"""); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun letterOrderIsOnlyAnUnverifiedCandidate() {
        val source = parse("""{"CameraID":"2","ChannelOrder":"B L R F"}""").source
        assertEquals("B L R F", source.channelOrder)
        assertFalse(source.verified)
        assertEquals("AVM", parse("""{"CameraMode":"AVM"}""").source.cameraMode)
        assertTrue(parse("""{"ChannelOrder":"R B F L"}""").angles.isEmpty())
        listOf("B L R R", "B L R X", "private-token", "B,L,R,F").forEach {
            try { parse("{\"ChannelOrder\":\"$it\"}"); fail() } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
        }
    }
}
