package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CommunicationAudioModeTest {
    private var mode = 1
    private var failWrite = false
    private var failures = 0
    private val writes = mutableListOf<Int>()
    private val session = CommunicationAudioMode<String>(3, { mode }, {
        if (failWrite) throw IllegalStateException("unsupported")
        mode = it
        writes.add(it)
    }, { failures++ })

    @Test fun lastOwnerRestoresOriginalMode() {
        session.acquire("first")
        session.acquire("first")
        session.acquire("second")
        session.release("first")
        assertEquals(3, mode)
        session.release("second")
        assertEquals(1, mode)
        assertEquals(listOf(3, 1), writes)
    }

    @Test fun unknownOwnerDoesNotEndActiveCall() {
        session.acquire("call")
        session.release("unknown")
        assertEquals(3, mode)
        session.close()
        assertEquals(1, mode)
    }

    @Test fun closeRestoresAndIsIdempotent() {
        session.acquire("first")
        session.acquire("second")
        session.close()
        session.close()
        session.release("first")
        assertEquals(listOf(3, 1), writes)
    }

    @Test fun failedAcquireCanRetryWithoutLosingOriginalMode() {
        failWrite = true
        session.acquire("call")
        assertEquals(1, mode)
        failWrite = false
        session.acquire("call")
        session.release("call")
        assertEquals(listOf(3, 1), writes)
        assertEquals(1, failures)
    }

    @Test fun failedRestoreCanRetryOnClose() {
        session.acquire("call")
        failWrite = true
        session.release("call")
        assertEquals(3, mode)
        failWrite = false
        session.close()
        assertEquals(1, mode)
        assertEquals(1, failures)
    }

    @Test fun externalModeChangeIsPreserved() {
        session.acquire("call")
        mode = 2
        session.release("call")
        assertEquals(2, mode)
        assertEquals(listOf(3), writes)
        session.acquire("next")
        session.close()
        assertEquals(2, mode)
    }

    @Test fun unavailableModeReadDoesNotClaimOwnership() {
        var failRead = true
        val unsupported = CommunicationAudioMode<String>(3, {
            if (failRead) throw SecurityException()
            mode
        }, { mode = it }, { failures++ })
        unsupported.acquire("call")
        unsupported.close()
        assertEquals(1, mode)
        failRead = false
        unsupported.acquire("call")
        unsupported.close()
        assertEquals(1, mode)
        assertEquals(1, failures)
    }

    @Test fun lateReleaseFromOldInstanceDoesNotRestoreNewCall() {
        session.acquire("old")
        session.release("old")
        session.acquire("new")
        session.release("old")
        assertEquals(3, mode)
        session.release("new")
        assertEquals(1, mode)
    }
}
