package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LegacyClusterRetryPolicyTest {
    @Test fun launchFailuresContinueButValidSurfaceWaitsWithoutTakingTop() {
        val policy=LegacyClusterRetryPolicy(); policy.start(); val epoch=policy.epoch
        repeat(4) { assertEquals(LegacyClusterRetryPolicy.Action.LAUNCH,policy.action(epoch,true,true,false,false,false)) }
        assertEquals(LegacyClusterRetryPolicy.Action.WAIT,policy.action(epoch,true,true,true,false,false))
        assertEquals(LegacyClusterRetryPolicy.Action.WAIT,policy.action(epoch,true,true,false,true,true))
        assertFalse(policy.completed)
    }
    @Test fun onlyActualCurrentTargetPresentationStopsRetry() {
        val policy=LegacyClusterRetryPolicy(); policy.start(); val epoch=policy.epoch
        assertFalse(policy.presented(epoch,true,true,false))
        assertFalse(policy.presented(epoch,false,true,true))
        assertFalse(policy.presented(epoch,true,false,true))
        assertTrue(policy.presented(epoch,true,true,true))
        assertEquals(LegacyClusterRetryPolicy.Action.STOP,policy.action(epoch,true,true,false,false,false))
    }
    @Test fun cancelAndNewTargetRejectOldGeneration() {
        val policy=LegacyClusterRetryPolicy(); policy.start(); val old=policy.epoch
        policy.cancel(); assertFalse(policy.presented(old,true,true,true))
        assertEquals(LegacyClusterRetryPolicy.Action.STOP,policy.action(old,true,true,false,false,false))
        policy.start(); assertFalse(policy.presented(old,true,true,true))
        assertEquals(LegacyClusterRetryPolicy.Action.STOP,policy.action(policy.epoch,true,false,false,false,false))
        assertEquals(LegacyClusterRetryPolicy.Action.STOP,policy.action(policy.epoch,false,true,false,false,false))
    }
}
