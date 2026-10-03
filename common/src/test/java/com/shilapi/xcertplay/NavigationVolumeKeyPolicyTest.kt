package com.shilapi.xcertplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationVolumeKeyPolicyTest {
    @Test fun selectsOnlyResumedOwnedConnectedNavigationWithLegacy14() {
        assertTrue(NavigationVolumeKeyPolicy.shouldSelectNavigation(true, true, true, true, true, 14))
        for (gate in 0..4) {
            val flags = BooleanArray(5) { it != gate }
            assertFalse(NavigationVolumeKeyPolicy.shouldSelectNavigation(
                flags[0], flags[1], flags[2], flags[3], flags[4], 14,
            ))
        }
    }

    @Test fun rejectsFallbackUnknownAndOtherLegacyRoutes() {
        for (route in listOf(null, 0, 3, 13, 15)) {
            assertFalse(NavigationVolumeKeyPolicy.shouldSelectNavigation(true, true, true, true, true, route))
        }
    }
}
