package com.shilapi.xcertplay.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Two fixed slabs: one GL upload and one latest frame. No resizing or per-frame native allocation. */
internal class CameraFrameBufferPool<T>(
    private val allocate: (Int) -> ByteBuffer = { ByteBuffer.allocateDirect(it).order(ByteOrder.nativeOrder()) },
) {
    companion object { const val MAX_FRAME_BYTES = 8 * 1024 * 1024; const val MAX_TOTAL_BYTES = 16 * 1024 * 1024 }
    internal class Lease internal constructor(val bytes: ByteBuffer, internal val slot: Int, internal val generation: Long) { internal var state = 0 }
    internal data class Ready<T>(val lease: Lease, val metadata: T)
    private class Slot(var bytes: ByteBuffer? = null, var owner: Lease? = null)
    private val slots = Array(2) { Slot() }
    private var capacity = 0
    private var generation = 0L
    private var active = true
    private var closed = false
    private var pending: Ready<T>? = null

    @Synchronized fun acquire(size: Int): Lease? {
        if (!active || closed || size !in 1..MAX_FRAME_BYTES) return null
        if (capacity == 0) {
            var rounded = 1
            while (rounded < size) rounded *= 2
            capacity = rounded
        }
        // Source shape may shrink within the slab. A larger source needs a new view, not repeated reallocations.
        if (size > capacity) return null
        var index = slots.indexOfFirst { it.owner == null }
        if (index < 0) {
            discardPending()
            index = slots.indexOfFirst { it.owner == null }
        }
        if (index < 0) return null // Both are being copied/uploaded; drop instead of allocating a third.
        val slot = slots[index]
        val bytes = slot.bytes ?: allocate(capacity).also { slot.bytes = it }
        bytes.clear(); bytes.limit(size)
        return Lease(bytes, index, generation).also { slot.owner = it }
    }

    @Synchronized fun publish(lease: Lease, metadata: T): Boolean {
        if (slots[lease.slot].owner !== lease || lease.state != 0) return false
        if (!active || closed || lease.generation != generation) { recycle(lease); return false }
        discardPending()
        lease.state = 1
        pending = Ready(lease, metadata)
        return true
    }

    @Synchronized fun takeLatest(): Ready<T>? {
        if (!active || closed) { discardPending(); return null }
        return pending.also { it?.lease?.state = 2; pending = null }
    }

    @Synchronized fun recycle(lease: Lease) {
        val slot = slots[lease.slot]
        if (slot.owner !== lease) return // Late/double return must not free a newer owner.
        if (pending?.lease === lease) pending = null
        lease.state = 3
        slot.owner = null
        if (closed) slot.bytes = null
    }

    @Synchronized fun clearPending() { generation++; discardPending() }
    @Synchronized fun pause() { active = false; clearPending() }
    @Synchronized fun resume() { if (!closed) active = true }
    @Synchronized fun close() {
        closed = true
        pause()
        slots.filter { it.owner == null }.forEach { it.bytes = null }
    }

    private fun discardPending() { pending?.let { recycle(it.lease) }; pending = null }
    @Synchronized internal fun retainedBytes(): Int = slots.sumOf { it.bytes?.capacity() ?: 0 }
}
