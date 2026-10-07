package com.shilapi.xcertplay.camera

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class CameraFrameBufferPoolTest {
    @Test fun thousandsOfLatestFramesUseTwoFixedSlabs() {
        var allocations = 0
        val pool = CameraFrameBufferPool<Int> { allocations++; ByteBuffer.allocateDirect(it) }
        val uploading = pool.acquire(6)!!
        pool.publish(uploading, -1)
        val upload = pool.takeLatest()!!
        repeat(10_000) { value ->
            val lease = pool.acquire(6)!!
            lease.bytes.put(0, (value % 127).toByte())
            assertTrue(pool.publish(lease, value))
        }
        assertEquals(2, allocations)
        assertEquals(16, pool.retainedBytes())
        val latest = pool.takeLatest()!!
        assertEquals(9_999, latest.metadata)
        assertEquals((9_999 % 127).toByte(), latest.lease.bytes.get(0))
        assertNotSame(upload.lease.bytes, latest.lease.bytes)
        pool.recycle(upload.lease); pool.recycle(latest.lease)
    }
    @Test fun budgetAndGrowthCannotAllocateExtraSlabs() {
        var allocations = 0
        val pool = CameraFrameBufferPool<Int> { allocations++; ByteBuffer.allocate(it) }
        assertNull(pool.acquire(CameraFrameBufferPool.MAX_FRAME_BYTES + 1))
        val a = pool.acquire(6)!!
        assertNull(pool.acquire(9)) // Frozen capacity is 8.
        val b = pool.acquire(8)!!
        assertNull(pool.acquire(1)) // Both writers are borrowed; never add a third.
        assertEquals(2, allocations)
        assertTrue(pool.retainedBytes() <= CameraFrameBufferPool.MAX_TOTAL_BYTES)
        pool.recycle(a); pool.recycle(b)
    }
    @Test fun pauseRejectsLatePublishAndResumeReusesStorage() {
        var allocations = 0
        val pool = CameraFrameBufferPool<Int> { allocations++; ByteBuffer.allocate(it) }
        val old = pool.acquire(6)!!
        pool.pause()
        assertNull(pool.acquire(6))
        pool.resume()
        assertFalse(pool.publish(old, 1))
        val fresh = pool.acquire(6)!!
        assertTrue(pool.publish(fresh, 2))
        assertEquals(2, pool.takeLatest()!!.metadata)
        assertEquals(1, allocations)
        pool.recycle(fresh)
    }
    @Test fun closeKeepsInFlightUploadValidButReleasesItOnReturn() {
        val pool = CameraFrameBufferPool<Int> { ByteBuffer.allocate(it) }
        val lease = pool.acquire(6)!!
        pool.publish(lease, 1)
        val upload = pool.takeLatest()!!
        val pending = pool.acquire(6)!!
        pool.publish(pending, 2)
        pool.close(); pool.close(); pool.resume()
        assertNull(pool.takeLatest())
        assertNull(pool.acquire(6))
        assertEquals(8, pool.retainedBytes())
        upload.lease.bytes.put(0, 42)
        pool.recycle(upload.lease)
        assertEquals(0, pool.retainedBytes())
    }
    @Test fun lateDoubleReturnAndRepublishCannotReleaseNewOwner() {
        val pool = CameraFrameBufferPool<Int> { ByteBuffer.allocate(it) }
        val old = pool.acquire(6)!!
        pool.publish(old, 1)
        assertFalse(pool.publish(old, 2))
        val ready = pool.takeLatest()!!
        assertFalse(pool.publish(old, 3))
        pool.recycle(ready.lease)
        val fresh = pool.acquire(6)!!
        pool.recycle(old)
        assertFalse(pool.publish(old, 4))
        assertTrue(pool.publish(fresh, 5))
        assertSame(fresh, pool.takeLatest()!!.lease)
    }
    @Test fun contextResetInvalidatesCopyInProgress() {
        val pool = CameraFrameBufferPool<Int> { ByteBuffer.allocate(it) }
        val old = pool.acquire(6)!!
        pool.clearPending()
        assertFalse(pool.publish(old, 1))
        val fresh = pool.acquire(6)!!
        assertTrue(pool.publish(fresh, 2))
    }
    @Test fun initialAndHiddenOverlayCanAcceptTheFrameThatTriggersAttachment() {
        val pool = CameraFrameBufferPool<Int> { ByteBuffer.allocate(it) }
        val first = pool.acquire(6)!! // No attachment required for the first frame.
        assertTrue(pool.publish(first, 1))
        pool.clearPending() // Overlay removal invalidates old frames without pausing acquisition.
        val hiddenFrame = pool.acquire(6)!!
        assertTrue(pool.publish(hiddenFrame, 2))
        pool.resume() // Reattachment must preserve this new latest frame.
        val visible = pool.takeLatest()!!
        assertEquals(2, visible.metadata)
        pool.recycle(visible.lease)
    }
    @Test fun detachCannotUndoActivityPauseAndResumeWorksWhileUnattached() {
        val pool = CameraFrameBufferPool<Int> { ByteBuffer.allocate(it) }
        pool.pause()
        pool.clearPending() // Detach after Activity pause does not re-enable capture.
        assertNull(pool.acquire(6))
        pool.resume() // Activity resumed, even before overlay attachment.
        val frame = pool.acquire(6)!!
        assertTrue(pool.publish(frame, 3))
        assertEquals(3, pool.takeLatest()!!.metadata)
    }

}
