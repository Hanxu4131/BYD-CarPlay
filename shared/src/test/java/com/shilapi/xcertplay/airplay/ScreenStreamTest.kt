package com.shilapi.xcertplay.airplay

import java.io.EOFException
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ScreenStreamTest {
    @Test fun eofBetweenMessagesIsCleanPeerEof() {
        assertNull(receiveClose(byteArrayOf()))
    }

    @Test fun partialHeaderIsReportedAsTruncatedHeader() {
        val failure = receiveClose(byteArrayOf(1, 2))
        assertTrue(failure is EOFException)
        assertEquals("truncated video header expected=128 received=2", failure?.message)
    }

    @Test fun partialBodyIsReportedAsTruncatedBody() {
        val failure = receiveClose(header(4) + byteArrayOf(1, 2))
        assertTrue(failure is EOFException)
        assertEquals("truncated video body expected=4 received=2", failure?.message)
    }

    @Test fun eofBeforeDeclaredBodyIsNotCleanPeerEof() {
        val failure = receiveClose(header(4))
        assertTrue(failure is EOFException)
        assertEquals("truncated video body expected=4 received=0", failure?.message)
    }

    @Test fun oversizedAndUnsignedNegativeBodyLengthsAreProtocolFailures() {
        for (size in listOf(8 * 1024 * 1024 + 1, -1)) {
            val failure = receiveClose(header(size))
            assertTrue(failure is IOException)
            assertFalse(failure is EOFException)
            assertEquals("invalid video body size=${size.toLong() and 0xffff_ffffL} max=8388608", failure?.message)
        }
    }

    @Test fun locallyClosedListenerDoesNotReportPeerEof() {
        val ended = CountDownLatch(1)
        val stream = ScreenStream(ByteArray(32))
        stream.listen(object : ScreenStream.Listener {
            override fun onClosed(cause: Throwable?) { ended.countDown() }
        })
        stream.close()
        assertFalse(ended.await(100, TimeUnit.MILLISECONDS))
    }

    private fun receiveClose(bytes: ByteArray): Throwable? {
        val ended = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val stream = ScreenStream(ByteArray(32))
        try {
            val port = stream.listen(object : ScreenStream.Listener {
                override fun onClosed(cause: Throwable?) {
                    failure.set(cause)
                    ended.countDown()
                }
            })
            Socket("127.0.0.1", port).use { peer ->
                peer.getOutputStream().write(bytes)
                peer.getOutputStream().flush()
                peer.shutdownOutput()
                assertTrue("Video receiver must report stream closure", ended.await(3, TimeUnit.SECONDS))
            }
            return failure.get()
        } finally {
            stream.close()
        }
    }

    private fun header(bodySize: Int): ByteArray = ByteArray(128).apply {
        for (index in 0..3) this[index] = (bodySize ushr (index * 8)).toByte()
    }
}
