package com.shilapi.xcertplay.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class BmmCameraCaptureTest {
    private class Fake : BmmCameraCapture.Backend {
        val calls = mutableListOf<String>()
        var publicRejected = true
        var missing = true
        var nativeAccepted = true
        var disableFails = false
        var startFails = false
        var emittedLengths = listOf(6, 6)
        var blockStart: CountDownLatch? = null
        val enteredStart = CountDownLatch(1)
        var callback: ((ByteArray, List<Int>, Long) -> Unit)? = null
        override fun publicOpen(id: Int): Any? { calls.add("public"); return if (publicRejected) null else this }
        override fun mappingMissing(): Boolean { calls.add("mapping"); return missing }
        override fun constructLegacy(id: Int): Any { calls.add("construct"); return this }
        override fun legacyOpen(camera: Any): Boolean { calls.add("native-open"); return nativeAccepted }
        override fun callback(camera: Any, consumer: (ByteArray, List<Int>, Long) -> Unit) { callback = consumer; calls.add("callback") }
        override fun clearCallback(camera: Any) { calls.add("clear"); callback = null }
        override fun enable(camera: Any, index: Int): Boolean { calls.add("enable"); return true }
        override fun start(camera: Any): Boolean {
            calls.add("start")
            enteredStart.countDown()
            blockStart?.await(2, TimeUnit.SECONDS)
            if (startFails) return false
            emittedLengths.forEachIndexed { index, length -> callback?.invoke(ByteArray(length), listOf(2, 2, 0, 6, 5), index.toLong()) }
            return true
        }
        override fun disable(camera: Any, index: Int) { calls.add("disable"); if (disableFails) error("synthetic failure") }
        override fun stop(camera: Any) { calls.add("stop") }
        override fun close(camera: Any) { calls.add("close") }
    }
    private fun source(proof: BmmCameraCapture.SourceProof = BmmCameraCapture.SourceProof.USER_CONFIGURATION, verified: Boolean = false) =
        BmmCameraCapture.Source(7, 5, null, proof, "synthetic source proof", verified)

    @Test fun publicOnlyNeverFallsBack() {
        val fake = Fake()
        assertEquals(BmmCameraCapture.Outcome.PUBLIC_OPEN_REJECTED, BmmCameraCapture(fake).metadataProbe(source()).outcome)
        assertEquals(listOf("public"), fake.calls)
    }
    @Test fun nativeRejectionClosesConstructedOwnerWithoutRetry() {
        val fake = Fake().apply { nativeAccepted = false }
        val result = BmmCameraCapture(fake).metadataProbe(source(), BmmCameraCapture.Policy.LEGACY_CONSTRUCTOR)
        assertEquals(BmmCameraCapture.Outcome.NATIVE_OPEN_REJECTED, result.outcome)
        assertEquals(listOf("public", "mapping", "construct", "native-open", "clear", "close"), fake.calls)
    }
    @Test fun legacyNeedsMissingMappingAndUserConfig() {
        for (proof in BmmCameraCapture.SourceProof.entries) {
            val fake = Fake().apply { missing = false }
            BmmCameraCapture(fake).metadataProbe(source(proof), BmmCameraCapture.Policy.LEGACY_CONSTRUCTOR)
            assertFalse(fake.calls.contains("construct"))
        }
        val sdk = Fake()
        BmmCameraCapture(sdk).metadataProbe(source(BmmCameraCapture.SourceProof.SDK_TAG), BmmCameraCapture.Policy.LEGACY_CONSTRUCTOR)
        assertFalse(sdk.calls.contains("mapping"))
        assertFalse(sdk.calls.contains("construct"))
    }
    @Test fun legacyMetadataHasNoGuessedLayoutAndClosesAfterTwoFrames() {
        val fake = Fake()
        val result = BmmCameraCapture(fake).metadataProbe(source(), BmmCameraCapture.Policy.LEGACY_CONSTRUCTOR)
        assertEquals(BmmCameraCapture.Outcome.FRAMES_RECEIVED, result.outcome)
        assertEquals(2, result.frames.size)
        assertNull(result.frames[0].declaredLayout)
        assertEquals(1, fake.calls.count { it == "close" })
        assertTrue(fake.calls.indexOf("disable") < fake.calls.indexOf("close"))
    }
    @Test fun failedDisableStillStopsAndCloses() {
        val fake = Fake().apply { publicRejected = false; disableFails = true }
        val result = BmmCameraCapture(fake).metadataProbe(source())
        assertTrue(fake.calls.containsAll(listOf("stop", "close")))
        assertEquals(1, result.cleanupErrors.size)
        assertFalse(result.legacyUsed)
    }
    @Test fun unverifiedStreamingIsRejectedBeforeOpening() {
        val fake = Fake()
        try { BmmCameraCapture(fake).stream(source(), BmmCameraCapture.FrameConsumer { _, _ -> }); fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(fake.calls.isEmpty())
    }
    @Test fun verifiedStreamBorrowsActualBytesAndStopsOnOwnerClose() {
        val fake = Fake().apply { publicRejected = false }
        val seen = mutableListOf<ByteArray>()
        val source = source(verified = true).copy(layoutExplicit = BmmCameraCapture.FrameLayout(2, 2, 2, 2, BmmCameraCapture.PixelFormat.NV12))
        val capture = BmmCameraCapture(fake)
        val session = capture.stream(source, BmmCameraCapture.FrameConsumer { bytes, _ -> seen.add(bytes.copyOf()) })
        assertTrue(session.ready.get(2, TimeUnit.SECONDS))
        session.close(); session.close()
        assertEquals(BmmCameraCapture.Outcome.FRAMES_RECEIVED, session.result.get(2, TimeUnit.SECONDS).outcome)
        assertEquals(2, seen.size)
        assertEquals(1, fake.calls.count { it == "close" })
    }
    @Test fun startRejectionStillDisablesStopsAndClosesOwnedObject() {
        val fake = Fake().apply { publicRejected = false; startFails = true }
        val result = BmmCameraCapture(fake).metadataProbe(source())
        assertEquals(BmmCameraCapture.Outcome.FAILED, result.outcome)
        assertEquals(listOf("public", "callback", "enable", "start", "clear", "disable", "stop", "close"), fake.calls)
    }
    @Test fun cancelledOwnerCannotBeReplacedUntilNativeCallAndCleanupReturn() {
        val release = CountDownLatch(1)
        val fake = Fake().apply { publicRejected = false; blockStart = release }
        val capture = BmmCameraCapture(fake)
        val verified = source(verified = true).copy(layoutExplicit = BmmCameraCapture.FrameLayout(2, 2, 2, 2, BmmCameraCapture.PixelFormat.NV12))
        val session = capture.stream(verified, BmmCameraCapture.FrameConsumer { _, _ -> fail("Cancelled owner delivered a frame") })
        assertTrue(fake.enteredStart.await(1, TimeUnit.SECONDS))
        session.close()
        try { capture.stream(verified, BmmCameraCapture.FrameConsumer { _, _ -> }); fail() }
        catch (_: IllegalStateException) { }
        release.countDown()
        assertEquals(BmmCameraCapture.Outcome.NO_FRAMES, session.result.get(2, TimeUnit.SECONDS).outcome)
        assertFalse(session.ready.get(1, TimeUnit.SECONDS))
        assertEquals(1, fake.calls.count { it == "close" })
        fake.blockStart = null
        assertEquals(BmmCameraCapture.Outcome.FRAMES_RECEIVED, capture.metadataProbe(source()).outcome)
    }

    @Test fun longStreamRetainsOnlyTwoMetadataRecordsAndNoPixelCopies() {
        val fake = Fake().apply { publicRejected = false; emittedLengths = List(10_000) { 6 } }
        var delivered = 0
        val verified = source(verified = true).copy(layoutExplicit = BmmCameraCapture.FrameLayout(2, 2, 2, 2, BmmCameraCapture.PixelFormat.NV12))
        val session = BmmCameraCapture(fake).stream(verified, BmmCameraCapture.FrameConsumer { bytes, metadata ->
            assertEquals(bytes.size, metadata.byteLength)
            delivered++
        })
        assertTrue(session.ready.get(2, TimeUnit.SECONDS))
        assertEquals(10_000, delivered)
        val stale = fake.callback!!
        session.close()
        stale(ByteArray(6), List(5) { 0 }, 99)
        val result = session.result.get(2, TimeUnit.SECONDS)
        assertEquals(2, result.frames.size)
        assertEquals(10_000, delivered)
    }
    @Test fun explicitLayoutRejectsShortAndOversizedArraysRatherThanGuessingPayload() {
        val fake = Fake().apply { publicRejected = false; emittedLengths = listOf(5, 7, 6) }
        var delivered = 0
        val verified = source(verified = true).copy(layoutExplicit = BmmCameraCapture.FrameLayout(2, 2, 2, 2, BmmCameraCapture.PixelFormat.NV12))
        val session = BmmCameraCapture(fake).stream(verified, BmmCameraCapture.FrameConsumer { _, _ -> delivered++ })
        assertTrue(session.ready.get(2, TimeUnit.SECONDS))
        session.close()
        assertEquals(1, session.result.get(2, TimeUnit.SECONDS).frames.size)
        assertEquals(1, delivered)
    }
    @Test fun throwingConsumerIsReportedAndCleanupStillCompletes() {
        val fake = Fake().apply { publicRejected = false }
        val verified = source(verified = true).copy(layoutExplicit = BmmCameraCapture.FrameLayout(2, 2, 2, 2, BmmCameraCapture.PixelFormat.NV12))
        val session = BmmCameraCapture(fake).stream(verified, BmmCameraCapture.FrameConsumer { _, _ -> error("synthetic") })
        val result = session.result.get(2, TimeUnit.SECONDS)
        assertEquals(BmmCameraCapture.Outcome.FAILED, result.outcome)
        assertTrue(result.detail!!.contains("Frame consumer failed"))
        assertEquals(1, fake.calls.count { it == "close" })
    }

}
