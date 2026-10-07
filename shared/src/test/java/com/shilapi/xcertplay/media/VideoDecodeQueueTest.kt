package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class VideoDecodeQueueTest {
    @Test fun backlogSnapshotCountsOnlyFramesBeforeTheNextControlJob() {
        val barriers = listOf(
            VideoJob.Config(VideoCodec.H264, byteArrayOf(1)),
            VideoJob.SurfaceChanged(null),
            VideoJob.Resync,
        )
        for (barrier in barriers) {
            val queue = VideoDecodeQueue()
            val current = VideoJob.Frame(byteArrayOf(1), receivedNs = 10)
            queue.offer(current)
            queue.offer(VideoJob.Frame(byteArrayOf(2), receivedNs = 30))
            queue.offer(VideoJob.Frame(byteArrayOf(3), receivedNs = 20))
            queue.offer(barrier)
            queue.offer(VideoJob.Frame(byteArrayOf(4), receivedNs = 100))
            assertSame(current, queue.poll(0))
            assertEquals(VideoDecodeQueue.Backlog(2, 30L), queue.backlogAfterCurrent())
            assertNotNull(queue.poll(0)); assertNotNull(queue.poll(0))
            assertEquals(VideoDecodeQueue.Backlog(0, null), queue.backlogAfterCurrent())
            assertSame(barrier, queue.poll(0))
            assertEquals(VideoDecodeQueue.Backlog(1, 100L), queue.backlogAfterCurrent())
        }
    }

    @Test fun backlogSnapshotDoesNotConsumeTheStaticTail() {
        val queue = VideoDecodeQueue()
        val frame = VideoJob.Frame(byteArrayOf(1), receivedNs = 42)
        queue.offer(frame)
        assertEquals(VideoDecodeQueue.Backlog(1, 42L), queue.backlogAfterCurrent())
        assertSame(frame, queue.poll(0))
        assertEquals(VideoDecodeQueue.Backlog(0, null), queue.backlogAfterCurrent())
    }

    @Test fun lostReferenceChainWaitsForSuccessfullyQueuedKeyframe() {
        val chain = VideoReferenceChain()
        val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 1)
        val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
        assertTrue(chain.accepts(idr, VideoCodec.H264))
        assertTrue(chain.needsKeyFrame) // Receiving it is insufficient if the codec is still busy.
        chain.onQueued()
        assertTrue(chain.accepts(predicted, VideoCodec.H264))
        chain.reset()
        assertFalse(chain.accepts(predicted, VideoCodec.H264))
    }

    @Test fun overflowPreservesConfigurationAndResetsBeforeNewReferenceChain() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val surface = VideoJob.SurfaceChanged(null)
        queue.offer(config)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.offer(surface)
        queue.offer(VideoJob.Frame(byteArrayOf(2)))
        queue.offer(VideoJob.Frame(byteArrayOf(3)))
        assertSame(config, queue.poll(0))
        assertSame(surface, queue.poll(0))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertArrayEquals(byteArrayOf(3), (queue.poll(0) as VideoJob.Frame).nalus)
        assertNull(queue.poll(0))
    }

    @Test fun byteBudgetAlsoTriggersRecoveryAndRejectsOversizedFrame() {
        val queue = VideoDecodeQueue(maxFrames = 8, maxBytes = 5)
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertEquals(3, (queue.poll(0) as VideoJob.Frame).nalus.size)
        queue.offer(VideoJob.Frame(ByteArray(6)))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun diagnosticsObserveQueueWithoutConsumingFramesAndResetOnlyWindowCounters() {
        val queue = VideoDecodeQueue(maxFrames = 2, maxBytes = 8)
        val config = VideoJob.Config(VideoCodec.H264, byteArrayOf(1))
        val first = VideoJob.Frame(ByteArray(3))
        queue.offer(config); queue.offer(first)
        val initial = queue.takeDiagnostics()
        assertTrue(initial.contains("pendingFrames=1 pendingBytes=3"))
        assertTrue(initial.contains("peakPendingFrames=1 peakPendingBytes=3"))
        assertSame(config, queue.poll(0)); assertSame(first, queue.poll(0))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.offer(VideoJob.Frame(ByteArray(3)))
        queue.recordStaleRecovery()
        val overflow = queue.takeDiagnostics()
        assertTrue(overflow.contains("queueOverflows=1 staleRecoveries=1"))
        assertTrue(overflow.contains("peakPendingFrames=2 peakPendingBytes=6"))
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNotNull(queue.poll(0)); assertNull(queue.poll(0))
        assertTrue(queue.takeDiagnostics().contains("queueOverflows=0 staleRecoveries=0"))
    }

    @Test fun invalidatedChainDropsOldFramesAndResyncButPreservesTheNextControlSegment() {
        for (barrier in listOf(
            VideoJob.Config(VideoCodec.H264, byteArrayOf(1)), VideoJob.SurfaceChanged(null),
        )) {
            val queue = VideoDecodeQueue()
            queue.offer(VideoJob.Frame(byteArrayOf(1)))
            queue.offer(VideoJob.Resync)
            queue.offer(VideoJob.Frame(byteArrayOf(2)))
            queue.offer(barrier)
            val next = VideoJob.Frame(byteArrayOf(3))
            queue.offer(next)
            queue.offer(VideoJob.Resync)
            queue.discardCurrentChain()
            assertSame(barrier, queue.poll(0))
            assertSame(next, queue.poll(0))
            assertSame(VideoJob.Resync, queue.poll(0))
            assertNull(queue.poll(0))
        }
    }

    @Test fun invalidatedChainWithoutControlBarrierIsCompletelyDiscarded() {
        val queue = VideoDecodeQueue()
        queue.offer(VideoJob.Resync)
        queue.offer(VideoJob.Frame(byteArrayOf(1)))
        queue.discardCurrentChain()
        assertNull(queue.poll(0))
        assertEquals(VideoDecodeQueue.Backlog(0, null), queue.backlogAfterCurrent())
        val fresh = VideoJob.Frame(byteArrayOf(2))
        queue.offer(fresh)
        assertSame(fresh, queue.poll(0))
    }

    @Test fun slowConfigurationRejectsOnlyHardStaleKeyframesWithNewerBacklog() {
        val now = 2_000_000_000L
        assertTrue(VideoRecoveryFrameAge.isObsolete(now, 500_000_000L,
            VideoDecodeQueue.Backlog(3, 600_000_000L)))
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, 500_000_001L,
            VideoDecodeQueue.Backlog(3, 600_000_001L)))
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, 0,
            VideoDecodeQueue.Backlog(0, null))) // A static tail is still useful.
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, 0,
            VideoDecodeQueue.Backlog(2, 99_999_999L)))
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, 0,
            VideoDecodeQueue.Backlog(3, now + 1)))
        assertFalse(VideoRecoveryFrameAge.isObsolete(now, now + 1,
            VideoDecodeQueue.Backlog(3, now + 2)))
    }

    @Test fun fullOutputMustBeDrainedWhileRetryingTheSameInput() {
        var heldOutputs = 2
        var dequeues = 0
        val submitted = mutableListOf<Int>()
        for (frame in 1..3) {
            val index = VideoInputPump.acquire(
                running = { true },
                drain = { if (heldOutputs > 0) heldOutputs-- },
                dequeue = { dequeues++; if (heldOutputs > 0) -1 else 0 },
            )
            assertEquals(0, index)
            submitted.add(frame)
            heldOutputs = 2
        }
        assertEquals(listOf(1, 2, 3), submitted)
        assertEquals(6, dequeues)
    }

    @Test fun stalledDecoderHasFiniteWaitAndShutdownCancelsImmediately() {
        var time = 0L
        var attempts = 0
        assertEquals(-1, VideoInputPump.acquire(
            running = { true }, drain = {}, dequeue = { attempts++; -1 },
            nanoTime = { time.also { time += 10 } }, timeoutNs = 30,
        ))
        assertEquals(3, attempts)
        assertEquals(-1, VideoInputPump.acquire(running = { false }, drain = { fail() }, dequeue = { fail(); 0 }))
    }
}
