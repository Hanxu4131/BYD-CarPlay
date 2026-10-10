package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class NavigationWheelRecoveryStartTest {
    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val starts = mutableListOf<ComponentName>()
        val stops = mutableListOf<ComponentName>()
        var denied = false
        override fun startService(intent: Intent): ComponentName? {
            if (denied) throw SecurityException("not permitted")
            return requireNotNull(intent.component).also { starts.add(it) }
        }
        override fun stopService(intent: Intent): Boolean {
            stops.add(requireNotNull(intent.component))
            return true
        }
    }
    private lateinit var context: RecordingContext
    @Before fun prepare() {
        context = RecordingContext(RuntimeEnvironment.getApplication())
        NavigationWheelServiceRecovery.releaseOwnServiceStart(context, false, false)
        context.stops.clear()
        AirPlayPersistence.saveNavigationVolumeWheelEnabled(context, true)
    }
    @Test fun startsOnlyItsExplicitComponentAndRetainsItUntilActualBinding() {
        assertTrue(NavigationWheelServiceRecovery.startOwnService(context))
        assertEquals(listOf(ComponentName(context, NavigationWheelAccessibilityService::class.java)), context.starts)
        NavigationWheelServiceRecovery.releaseOwnServiceStart(context, true, false)
        assertTrue(context.stops.isEmpty())
        NavigationWheelServiceRecovery.releaseOwnServiceStart(context, true, true)
        assertEquals(context.starts, context.stops)
        NavigationWheelServiceRecovery.releaseOwnServiceStart(context, true, true)
        assertEquals(1, context.stops.size)
    }
    @Test fun masterOffNeverStartsAndReleasesAnUnresolvedStart() {
        assertTrue(NavigationWheelServiceRecovery.startOwnService(context))
        AirPlayPersistence.saveNavigationVolumeWheelEnabled(context, false)
        NavigationWheelServiceRecovery.releaseOwnServiceStart(context, false, false)
        assertEquals(context.starts, context.stops)
        assertFalse(NavigationWheelServiceRecovery.startOwnService(context))
        assertEquals(1, context.starts.size)
    }
    @Test fun refusedStartDoesNotClaimOwnershipOrStopAnotherService() {
        context.denied = true
        assertFalse(NavigationWheelServiceRecovery.startOwnService(context))
        NavigationWheelServiceRecovery.releaseOwnServiceStart(context, true, true)
        assertTrue(context.starts.isEmpty())
        assertTrue(context.stops.isEmpty())
    }
}
