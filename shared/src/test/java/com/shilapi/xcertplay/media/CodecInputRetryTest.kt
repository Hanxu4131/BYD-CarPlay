package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class CodecInputRetryTest {
    @Test fun fullInputIsRetriedAfterReadyOutputIsDrained() {
        var outputHeld = true; var retries = 0
        val result = CodecInputRetry.acquire(
            dequeue = { if (outputHeld) -1 else 3 },
            drain = { outputHeld = false }, running = { true }, onRetry = { retries++ },
            nowNanos = { 0L }, budgetNanos = 100L,
        )
        assertEquals(3, result); assertEquals(1, retries)
    }
    @Test fun permanentlyUnavailableInputHasABoundedRetryPeriod() {
        var time = 0L; var retries = 0
        val result = CodecInputRetry.acquire(dequeue = { -1 }, drain = { time += 10L },
            running = { true }, onRetry = { retries++ }, nowNanos = { time }, budgetNanos = 30L)
        assertEquals(-1, result); assertEquals(3, retries)
    }
    @Test fun stoppingDuringDrainDoesNotFeedTheCodecAgain() {
        var active = true; var dequeues = 0
        val result = CodecInputRetry.acquire(dequeue = { dequeues++; -1 },
            drain = { active = false }, running = { active }, nowNanos = { 0L })
        assertEquals(-1, result); assertEquals(1, dequeues)
    }
    @Test fun slowDrainCanReturnItsFreedSlotWithoutStartingAnotherRetryPeriod() {
        var time = 0L; var slot = -1; var retries = 0
        val result = CodecInputRetry.acquire(dequeue = { slot },
            drain = { time = 200L; slot = 2 }, running = { true }, onRetry = { retries++ },
            nowNanos = { time }, budgetNanos = 100L)
        assertEquals(2, result); assertEquals(1, retries)
    }
    @Test fun availableInputDoesNotDrainOrWait() {
        val result = CodecInputRetry.acquire(dequeue = { 1 }, drain = { fail("unnecessary drain") },
            running = { true }, onRetry = { fail("unnecessary retry") })
        assertEquals(1, result)
    }
}
