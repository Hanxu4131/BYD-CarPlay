package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class AdaptiveResizeTransitionTest {
    @Test fun geometryAloneNeverRevealsAndNextPresentationMustUseSameSurface() {
        val state = AdaptiveResizeTransition()
        val surface = Any()
        state.begin(1); state.arm(1, 20)
        assertFalse(state.presented(4, surface))
        state.confirm(1,20,4,surface)
        assertFalse(state.presented(4,surface))
        assertFalse(state.presented(5,Any()))
        assertTrue(state.presented(5,surface))
        assertFalse(state.active)
    }
    @Test fun oldGeometryAndOldTimeoutCannotReleaseNewTarget() {
        val state = AdaptiveResizeTransition(); val surface = Any()
        val old = state.begin(1); state.arm(1,10)
        val next = state.begin(0); state.arm(0,11)
        state.confirm(1,10,0,surface)
        assertFalse(state.presented(1,surface))
        assertFalse(state.expire(old))
        assertTrue(state.active)
        assertTrue(state.expire(next))
        assertFalse(state.active)
    }
    @Test fun repeatedTargetDoesNotExtendCoverTimeoutAndCancelIsFinal() {
        val state = AdaptiveResizeTransition()
        val token = state.begin(1)
        assertEquals(token,state.begin(1))
        state.cancel()
        assertFalse(state.expire(token))
        assertFalse(state.presented(999,Any()))
    }
}
