package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test

class CameraCaptureWorkerTest {
    private fun args() = arrayOf("--source-id=7", "--callback-index=5", "--layout=unknown", "--format=unknown", "--policy=PUBLIC_ONLY", "--source-proof=USER_CONFIGURATION")
    private fun rejects(values: Array<String>) {
        try { CameraCaptureWorker.parse(values); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun explicitMetadataArgumentsDoNotInventPixelLayoutOrVerification() {
        val options = CameraCaptureWorker.parse(args())
        assertEquals(5_000L, options.timeoutMillis)
        assertNull(options.source.layoutExplicit)
        assertFalse(options.source.userVerifiedSource)
        assertEquals(BmmCameraCapture.Policy.PUBLIC_ONLY, options.policy)
    }
    @Test fun missingDuplicateAndUnknownArgumentsAreRejected() {
        rejects(args().drop(1).toTypedArray())
        rejects(args() + "--source-id=8")
        rejects(args() + "--camera-scan=true")
        rejects(args().map { if (it.startsWith("--source-id")) "--source-id=-1" else it }.toTypedArray())
    }
    @Test fun deadlinesAreBoundedAndStreamCannotReachCapture() {
        rejects(args() + "--timeout-ms=4999")
        rejects(args() + "--timeout-ms=8001")
        rejects(args() + "--mode=stream")
        assertEquals(8_000L, CameraCaptureWorker.parse(args() + "--timeout-ms=8000").timeoutMillis)
    }
    @Test fun layoutAndChromaMustBeExplicitTogether() {
        rejects(args().map { if (it == "--format=unknown") "--format=NV12" else it }.toTypedArray())
        val explicit = args().map {
            when (it) { "--format=unknown" -> "--format=NV12"; "--layout=unknown" -> "--layout=2,2,2,2"; else -> it }
        }.toTypedArray()
        assertEquals(6L, CameraCaptureWorker.parse(explicit).source.layoutExplicit!!.minimumBytes)
    }
    @Test fun outputIsBoundedAndContainsNoSdkFailureTextOrPixelData() {
        val frame = BmmCameraCapture.Metadata(7372800, List(10_000) { it }, 55, null)
        val result = BmmCameraCapture.Result(BmmCameraCapture.Outcome.FAILED, List(100) { frame }, true, "secret SDK exception", List(100) { "close:" + "x".repeat(10_000) })
        val json = CameraCaptureWorker.encode(result)
        assertTrue(json.length < 1500)
        assertEquals(2, Regex("byteLength").findAll(json).count())
        assertFalse(json.contains("secret"))
        assertTrue(json.contains("\"declaredLayout\":null"))
        assertTrue(json.contains("\"rawIntegers\":[0,1,2,3,4]"))
    }
}
