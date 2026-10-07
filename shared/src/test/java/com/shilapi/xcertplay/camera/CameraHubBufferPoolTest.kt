package com.shilapi.xcertplay.camera

import org.junit.Assert.*
import org.junit.Test

class CameraHubBufferPoolTest {
    @Test fun releasedSlabReusesWithoutChangingInflightBytes() {
        val pool=CameraHubBufferPool(48)
        val inflight=requireNotNull(pool.acquire(24));inflight.fill(7)
        val pending=requireNotNull(pool.acquire(24));pending.fill(8)
        pool.release(pending)
        val replacement=requireNotNull(pool.acquire(24))
        assertSame(pending,replacement)
        replacement.fill(9)
        assertEquals(7,inflight[0].toInt())
        assertEquals(48,pool.retainedBytes())
        assertNull(pool.acquire(1))
    }
    @Test fun staleOrDoubleReleaseCannotLeaseSameSlabTwice() {
        val pool=CameraHubBufferPool(24)
        val bytes=requireNotNull(pool.acquire(24))
        pool.release(bytes);pool.release(bytes)
        assertSame(bytes,pool.acquire(24))
        assertNull(pool.acquire(24))
    }
    @Test fun shapeChangeOnlyEvictsFreeSlabsWithinHardBound() {
        val pool=CameraHubBufferPool(48)
        val inflight=requireNotNull(pool.acquire(24))
        val pending=requireNotNull(pool.acquire(24))
        pool.release(pending)
        assertNull(pool.acquire(25))
        pool.release(inflight)
        val resized=pool.acquire(48)
        assertNotNull(resized)
        assertEquals(48,pool.retainedBytes())
        assertNull(pool.acquire(0));assertNull(pool.acquire(49))
    }
}
