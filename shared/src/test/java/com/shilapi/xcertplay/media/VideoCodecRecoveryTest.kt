package com.shilapi.xcertplay.media

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VideoCodecRecoveryTest {
    @Test fun bothStreamsKeepTheirPendingRequestWhileMirrorsCoalesce() {
        val tasks = ArrayDeque<Runnable>()
        val events = mutableListOf<Int>()
        val dispatch = VideoRecoveryDispatch(Executor(tasks::add), events::add) { throw AssertionError(it) }
        dispatch.request(110)
        dispatch.request(110)
        dispatch.request(111)
        dispatch.request(111)
        assertEquals(2, tasks.size)
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf(110, 111), events)
        dispatch.request(110)
        tasks.removeFirst().run()
        assertEquals(listOf(110, 111, 110), events)
    }

    @Test fun anotherStreamRequestedInsideAHandlerIsStillDispatched() {
        val tasks = ArrayDeque<Runnable>()
        val events = mutableListOf<Int>()
        lateinit var dispatch: VideoRecoveryDispatch
        dispatch = VideoRecoveryDispatch(Executor(tasks::add), { type ->
            events += type
            if (type == 110) dispatch.request(111)
        }) { throw AssertionError(it) }
        dispatch.request(110)
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(listOf(110, 111), events)
    }

    @Test fun failedHandlerDoesNotLeaveItsStreamPermanentlyPending() {
        val tasks = ArrayDeque<Runnable>()
        var calls = 0
        val failure = IllegalStateException("IDR request failed")
        val errors = mutableListOf<Exception>()
        val dispatch = VideoRecoveryDispatch(Executor(tasks::add), { calls++; throw failure }, errors::add)
        repeat(2) {
            dispatch.request(111)
            tasks.removeFirst().run()
        }
        assertEquals(2, calls)
        assertEquals(listOf(failure, failure), errors)
    }

    @Test fun rejectedExecutorDoesNotRetainPendingStreams() {
        var reject = true
        val events = mutableListOf<Int>()
        val dispatch = VideoRecoveryDispatch(Executor { task ->
            if (reject) throw RejectedExecutionException()
            task.run()
        }, events::add) { throw AssertionError(it) }
        dispatch.request(110)
        dispatch.request(111)
        reject = false
        dispatch.request(110)
        dispatch.request(111)
        assertEquals(listOf(110, 111), events)
    }

    @Test fun sinkDispatchesInstrumentRequestWhileTheMainHandlerIsBusy() {
        val sink = AndroidMediaSink()
        val mainStarted = CountDownLatch(1)
        val mainRelease = CountDownLatch(1)
        val instrumentCalled = CountDownLatch(1)
        sink.setVideoRecoveryHandler(110) {
            mainStarted.countDown()
            assertTrue(mainRelease.await(5, TimeUnit.SECONDS))
        }
        sink.setVideoRecoveryHandler(111) { instrumentCalled.countDown() }
        val request = sink.javaClass.getDeclaredMethod("requestVideoRecovery", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            request.invoke(sink, 110)
            assertTrue(mainStarted.await(5, TimeUnit.SECONDS))
            request.invoke(sink, 111)
            mainRelease.countDown()
            assertTrue(instrumentCalled.await(5, TimeUnit.SECONDS))
        } finally {
            mainRelease.countDown()
            sink.close()
        }
    }

    @Test fun normalDecoderHasNoCooldownAndFirstReclaimWaitsThroughTheDeadline() {
        val retry = VideoCodecRetryGate()
        assertTrue(retry.canRetry(0))
        assertFalse(retry.awaitingPicture)
        assertEquals(500L, retry.failed(10 * MS))
        assertTrue(retry.awaitingPicture)
        assertFalse(retry.canRetry(509 * MS))
        assertFalse(retry.canRetry(510 * MS - 1))
        assertTrue(retry.canRetry(510 * MS))
    }

    @Test fun failedReallocationsBackOffWithABoundedMaximum() {
        val retry = VideoCodecRetryGate()
        var now = 0L
        for (delayMs in listOf(500L, 1000L, 2000L, 4000L, 4000L, 4000L)) {
            assertEquals(delayMs, retry.failed(now))
            val due = now + delayMs * MS
            assertFalse(retry.canRetry(due - 1))
            assertTrue(retry.canRetry(due))
            // Allowing allocation must not reset backoff before a decoded picture arrives.
            assertTrue(retry.canRetry(due + 1))
            assertTrue(retry.awaitingPicture)
            now = due
        }
    }

    @Test fun mainAndInstrumentHaveIndependentRetryDeadlines() {
        val main = VideoCodecRetryGate()
        val instrument = VideoCodecRetryGate()
        main.failed(0)
        assertFalse(main.canRetry(100 * MS))
        assertTrue(instrument.canRetry(100 * MS))
        instrument.failed(400 * MS)
        assertTrue(main.canRetry(500 * MS))
        assertFalse(instrument.canRetry(500 * MS))
    }

    @Test fun decodedPictureResetsTheResourceFailureEpisode() {
        val retry = VideoCodecRetryGate()
        retry.failed(0)
        retry.failed(500 * MS)
        retry.pictureDecoded()
        assertFalse(retry.awaitingPicture)
        assertTrue(retry.canRetry(600 * MS))
        assertEquals(500L, retry.failed(600 * MS))
    }

    @Test fun resourceLossStopsFallbackButFormatErrorsKeepCompatibilityFallback() {
        for (code in listOf(1101, 1100, -32)) assertTrue(VideoCodecFailurePolicy.resourceUnavailable(code))
        for (code in listOf(-22, -38, 0, 1234)) assertFalse(VideoCodecFailurePolicy.resourceUnavailable(code))
    }

    private companion object { const val MS = 1_000_000L }
}
