package com.shilapi.xcertplay.camera

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CameraCaptureTcpTest {
    @Test fun loopbackHandshakeAndSyntheticFrame() {
        CameraCaptureTcp.listen().use { server ->
            assertEquals(CameraCaptureTcp.address,server.inetAddress)
            assertFalse(server.inetAddress.isAnyLocalAddress)
            assertEquals(server.localPort,CameraCaptureTcp.validPort(server.localPort))
            Socket().use { helper ->
                helper.connect(InetSocketAddress(CameraCaptureTcp.address,server.localPort),1000)
                server.accept().use { owner ->
                    owner.soTimeout=1000
                    val layout=CameraFrameLayout(4,4,4,4,CameraPixelFormat.NV21)
                    val nonce="1".repeat(32)
                    val output=DataOutputStream(helper.outputStream)
                    CameraCaptureProtocol.hello(output,nonce,layout)
                    output.writeInt(CameraCaptureProtocol.READY)
                    val bytes=ByteArray(24) { it.toByte() }
                    CameraCaptureProtocol.frame(output,bytes,7);output.flush()
                    val input=DataInputStream(owner.inputStream)
                    CameraCaptureProtocol.readHello(input,nonce,layout)
                    assertEquals(CameraCaptureProtocol.READY,input.readInt())
                    assertEquals(CameraCaptureProtocol.FRAME,input.readInt())
                    val received=ByteArray(24)
                    assertEquals(7L,CameraCaptureProtocol.readFrame(input,received))
                    assertArrayEquals(bytes,received)
                }
            }
        }
    }

    @Test fun listenerAndReaderCloseInterruptBlockedOperations() {
        val executor=Executors.newSingleThreadExecutor()
        val server=CameraCaptureTcp.listen()
        try {
            val entered=CountDownLatch(1)
            val accepting=executor.submit<Boolean> {
                entered.countDown()
                try { server.accept().close();false } catch (_:java.io.IOException) { true }
            }
            assertTrue(entered.await(1,TimeUnit.SECONDS))
            server.close()
            assertTrue(accepting.get(2,TimeUnit.SECONDS))
            CameraCaptureTcp.listen().use { listener ->
                Socket().use { helper ->
                    helper.connect(InetSocketAddress(CameraCaptureTcp.address,listener.localPort),1000)
                    val owner=listener.accept()
                    try {
                        val reading=executor.submit<Int> {
                            try { owner.inputStream.read() } catch (_:java.io.IOException) { -1 }
                        }
                        CameraCaptureTcp.close(owner)
                        assertEquals(-1,reading.get(2,TimeUnit.SECONDS).toInt())
                    } finally { CameraCaptureTcp.close(owner) }
                }
            }
        } finally { server.close();executor.shutdownNow() }
    }

    @Test fun cancellationDoesNotWaitForBlockedWriterLock() {
        val executor=Executors.newSingleThreadExecutor()
        CameraCaptureTcp.listen().use { listener ->
            Socket().use { helper ->
                helper.receiveBufferSize=1024
                helper.connect(InetSocketAddress(CameraCaptureTcp.address,listener.localPort),1000)
                val owner=listener.accept()
                owner.sendBufferSize=1024
                val entered=CountDownLatch(1)
                try {
                    val writing=executor.submit<Boolean> {
                        try {
                            val output=owner.outputStream
                            synchronized(output) {
                                entered.countDown()
                                val block=ByteArray(65536)
                                while(true) output.write(block)
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        } catch (_:java.io.IOException) { true }
                    }
                    assertTrue(entered.await(1,TimeUnit.SECONDS))
                    CameraCaptureTcp.close(owner)
                    assertTrue(writing.get(2,TimeUnit.SECONDS))
                } finally { CameraCaptureTcp.close(owner);executor.shutdownNow() }
            }
        }
    }
}
