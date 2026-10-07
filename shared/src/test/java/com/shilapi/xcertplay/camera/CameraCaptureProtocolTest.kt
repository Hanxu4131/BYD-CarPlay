package com.shilapi.xcertplay.camera

import java.io.*
import org.junit.Assert.*
import org.junit.Test

class CameraCaptureProtocolTest {
    private val layout=CameraFrameLayout(4,4,4,4,CameraPixelFormat.NV21)
    private val nonce="0123456789abcdef0123456789abcdef"
    @Test fun exactHandshakeAndFrameRoundTrip() {
        val encoded=ByteArrayOutputStream(); val out=DataOutputStream(encoded)
        val bytes=ByteArray(24) { it.toByte() }
        CameraCaptureProtocol.hello(out,nonce,layout); CameraCaptureProtocol.frame(out,bytes,123L)
        val input=DataInputStream(ByteArrayInputStream(encoded.toByteArray()))
        CameraCaptureProtocol.readHello(input,nonce,layout)
        assertEquals(CameraCaptureProtocol.FRAME,input.readInt())
        val result=ByteArray(24)
        assertEquals(123L,CameraCaptureProtocol.readFrame(input,result)); assertArrayEquals(bytes,result)
    }
    @Test fun wrongNonceLayoutAndLengthRejectBeforePayloadUse() {
        val encoded=ByteArrayOutputStream(); CameraCaptureProtocol.hello(DataOutputStream(encoded),nonce,layout)
        try { CameraCaptureProtocol.readHello(DataInputStream(ByteArrayInputStream(encoded.toByteArray())),"0".repeat(32),layout); fail() } catch (_:IllegalArgumentException) {}
        try { CameraCaptureProtocol.readHello(DataInputStream(ByteArrayInputStream(encoded.toByteArray())),nonce,layout.copy(format=CameraPixelFormat.NV12)); fail() } catch (_:IllegalArgumentException) {}
        val bad=ByteArrayOutputStream(); DataOutputStream(bad).writeInt(CameraCaptureProtocol.MAX_FRAME_BYTES+1)
        try { CameraCaptureProtocol.readFrame(DataInputStream(ByteArrayInputStream(bad.toByteArray())),ByteArray(24)); fail() } catch (_:IllegalArgumentException) {}
    }
    @Test fun truncatedFrameRejects() {
        val bad=ByteArrayOutputStream(); DataOutputStream(bad).apply { writeInt(24);writeLong(1);write(ByteArray(23)) }
        try { CameraCaptureProtocol.readFrame(DataInputStream(ByteArrayInputStream(bad.toByteArray())),ByteArray(24));fail() } catch (_:EOFException) {}
    }
    @Test fun latestQueueCopiesVendorPrefixAndBoundsSlabs() {
        val queue=CameraCaptureLatest(24)
        val vendor=ByteArray(31) { 1 }
        assertTrue(queue.offer(vendor,24,1)); vendor.fill(9)
        val first=requireNotNull(queue.take(1)); assertEquals(1,first.bytes[0].toInt())
        assertTrue(queue.offer(ByteArray(24) { 2 },24,2))
        assertTrue(queue.offer(ByteArray(24) { 3 },24,3))
        queue.release(first)
        val latest=requireNotNull(queue.take(1)); assertEquals(3L,latest.timestamp);assertEquals(3,latest.bytes[0].toInt())
        assertNotSame(first.bytes,latest.bytes)
        queue.release(latest);queue.close()
        assertFalse(queue.offer(ByteArray(24),24,4));assertNull(queue.take(1))
    }
    @Test fun sourceContractOnlyAcceptsExactVerifiedShape() {
        val source=CameraCaptureProfiles.confirmedBydAvm().source
        val raw=listOf(2560,1920,21,7372800,5)
        val contract=requireNotNull(source.callbackContract);val declared=requireNotNull(source.layoutExplicit)
        assertTrue(contract.matches(7372807,raw,declared))
        assertFalse(contract.matches(7372808,raw,declared))
        assertFalse(contract.matches(7372807,raw.toMutableList().also { it[2]=17 },declared))
        assertFalse(contract.matches(7372807,raw,declared.copy(yStride=2562)))
        assertEquals(BmmCameraCapture.PixelFormat.NV21,declared.format) // Independently owner-verified pixel order; raw21 remains opaque.
    }
}
