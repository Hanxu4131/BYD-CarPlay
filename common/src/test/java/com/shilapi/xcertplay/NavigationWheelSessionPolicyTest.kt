package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class NavigationWheelSessionPolicyTest {
    @Test fun ownedBackgroundGuidanceDoesNotDependOnResumedActivity() {
        assertTrue(NavigationWheelSessionPolicy.canRoute(true, true, true, true, 14, true, false, true))
        assertFalse(NavigationWheelSessionPolicy.canRoute(true, false, true, true, 14, true, false, true))
    }
    @Test fun everyFreshSessionAudioAndSwitchGateIsRequired() {
        for (gate in 0..5) {
            val flags = BooleanArray(6) { it != gate }
            assertFalse(NavigationWheelSessionPolicy.canRoute(flags[0], flags[1], flags[2], flags[3], 14,
                flags[4], false, flags[5]))
        }
        assertFalse(NavigationWheelSessionPolicy.canRoute(true, true, true, true, 14, true, true, true))
        for (route in listOf(null, 0, 3, 13, 15)) {
            assertFalse(NavigationWheelSessionPolicy.canRoute(true, true, true, true, route, true, false, true))
        }
    }
    @Test fun pausedOwnerRetainsRegistrationButReleasedOrReplacedOwnerCannotRoute() {
        val oldOwner = Any(); val newOwner = Any()
        var hasSession = true
        NavigationWheelRoutingState.attach(oldOwner) { hasSession }
        assertTrue(NavigationWheelRoutingState.canRouteNow())
        // UI pause does not change the session-owned registration.
        assertTrue(NavigationWheelRoutingState.canRouteNow())
        hasSession = false
        assertFalse(NavigationWheelRoutingState.canRouteNow())
        NavigationWheelRoutingState.attach(newOwner) { true }
        NavigationWheelRoutingState.detach(oldOwner)
        assertTrue(NavigationWheelRoutingState.canRouteNow())
        NavigationWheelRoutingState.detach(newOwner)
        assertFalse(NavigationWheelRoutingState.canRouteNow())
    }
    @Test fun destroyedUiRetainsOwnedLiveSessionUntilActualSessionClear() {
        val owner = Any()
        var connected = true
        NavigationWheelRoutingState.attach(owner) { connected }
        val keep = NavigationWheelRegistrationPolicy.retain(true, true, true, true)
        NavigationWheelRoutingState.hostDestroyed(owner, keep)
        assertTrue(NavigationWheelRoutingState.canRouteNow())
        connected = false
        assertFalse(NavigationWheelRoutingState.canRouteNow())
        connected = true // Registration also survives a connection gap; fresh gate resumes it.
        assertTrue(NavigationWheelRoutingState.canRouteNow())
        NavigationWheelRoutingState.detach(owner) // Real background session clear.
        assertFalse(NavigationWheelRoutingState.canRouteNow())
    }
    @Test fun missingClosedOrChangedSessionMustNotRetainAndOldDestroyCannotRemoveNewOwner() {
        for (gate in 0..3) {
            val values = BooleanArray(4) { it != gate }
            assertFalse(NavigationWheelRegistrationPolicy.retain(values[0], values[1], values[2], values[3]))
        }
        val old = Any(); val fresh = Any()
        NavigationWheelRoutingState.attach(old) { true }
        NavigationWheelRoutingState.attach(fresh) { true }
        NavigationWheelRoutingState.hostDestroyed(old, false)
        assertTrue(NavigationWheelRoutingState.canRouteNow())
        NavigationWheelRoutingState.hostDestroyed(fresh, false)
        assertFalse(NavigationWheelRoutingState.canRouteNow())
    }

}
