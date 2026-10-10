package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class PendingAudioInputTest {
    @Test fun unavailableInputRetainsPayloadAndTimestampForTheNextPump() {
        val pending = PendingAudioInput(500L)
        val payload = byteArrayOf(1, 2, 3)
        pending.offer(payload, 1234L, 10L, 1000L)
        assertFalse(pending.unavailable(1100L))
        assertSame(payload, pending.current!!.payload)
        assertEquals(1234L, pending.current!!.presentationTimeUs)
        assertEquals(10L, pending.current!!.arrivalNanos)
        assertFalse(pending.unavailable(1499L))
        assertTrue(pending.unavailable(1500L))
        assertNull(pending.current)
    }

    @Test fun retryWindowStartsWhenDecodedNotWhenAnOlderPacketArrived() {
        val pending = PendingAudioInput(500L)
        pending.offer(byteArrayOf(1), 1L, 0L, 2000L)
        assertFalse(pending.unavailable(2100L))
        assertNotNull(pending.current)
    }

    @Test(expected = IllegalStateException::class)
    fun nextUnitCannotOvertakeOrReplaceHeldAudio() {
        val pending = PendingAudioInput()
        pending.offer(byteArrayOf(1), 1L, 0L, 0L)
        pending.offer(byteArrayOf(2), 2L, 0L, 0L)
    }

    @Test fun completionOrShutdownClearsTheUnitAndTheNextWaitStartsFresh() {
        val pending = PendingAudioInput(500L)
        pending.offer(byteArrayOf(1), 1L, 0L, 0L)
        pending.clear()
        assertNull(pending.current)
        assertFalse(pending.unavailable(1000L))
        pending.offer(byteArrayOf(2), 2L, 1000L, 1000L)
        assertFalse(pending.unavailable(1499L))
        assertEquals(2L, pending.current!!.presentationTimeUs)
    }
}
