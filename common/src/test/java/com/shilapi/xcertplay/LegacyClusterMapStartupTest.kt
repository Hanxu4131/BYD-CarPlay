package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageInfo
import org.robolectric.Shadows.shadowOf
import android.view.View
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class LegacyClusterMapStartupTest {
    private lateinit var controller: ActivityController<ClusterMapActivity>
    private lateinit var window: ClusterMapActivity
    private lateinit var cover: ClusterStartupView

    @Before fun setup() {
        controller = Robolectric.buildActivity(ClusterMapActivity::class.java).create()
        window = controller.get()
        shadowOf(window.packageManager).installPackage(PackageInfo().apply { packageName = "l1tech.com.l1mini" })
        cover = ClusterStartupView(window)
        field(ClusterMapActivity::class.java, "startupView").set(window, cover)
        field(ClusterMapActivity::class.java, "mapSurfaceAttached").setBoolean(window, true)
        field(LegacyClusterMap::class.java, "activity").set(LegacyClusterMap, window)
        window.getSharedPreferences("diplay_legacy_cluster", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putInt("target", 1)
            .putBoolean("restore_l1_after_map", true).apply()
        cover.waitForFrame()
    }

    @After fun cleanup() {
        LegacyL1MiniOrder.cancel()
        LegacyL1WakeRecovery.cancel()
        field(LegacyClusterMap::class.java, "activity").set(LegacyClusterMap, null)
        cover.dispose()
        controller.destroy()
    }

    @Test fun surfaceAndOptInWaitUntilTheCoverIsFullyHidden() {
        LegacyClusterMap.mapSurfaceReady(window)
        assertNull(recoveryContext())
        LegacyClusterMap.setRestoreL1AfterMap(window, true)
        LegacyClusterMap.mapStartupComplete(window)
        assertNull(recoveryContext())
        cover.alpha = 0.01f
        LegacyClusterMap.mapStartupComplete(window)
        assertNull(recoveryContext())
        cover.visibility = View.GONE
        LegacyClusterMap.mapStartupComplete(window)
        assertNotNull(recoveryContext())
    }

    @Test fun aNewSurfaceAndSurfaceLossCancelExistingRecovery() {
        cover.visibility = View.GONE
        LegacyClusterMap.mapStartupComplete(window)
        assertNotNull(recoveryContext())
        cover.waitForFrame()
        LegacyClusterMap.mapSurfaceReady(window)
        assertNull(recoveryContext())
        cover.visibility = View.GONE
        LegacyClusterMap.mapStartupComplete(window)
        assertNotNull(recoveryContext())
        field(ClusterMapActivity::class.java, "mapSurfaceAttached").setBoolean(window, false)
        LegacyClusterMap.mapSurfaceUnavailable(window)
        assertNull(recoveryContext())
        LegacyClusterMap.setRestoreL1AfterMap(window, true)
        LegacyClusterMap.mapStartupComplete(window)
        assertNull(recoveryContext())
    }

    @Test fun hiddenCoverDoesNotBypassOptOutOrWindowOwnership() {
        cover.visibility = View.GONE
        LegacyClusterMap.setRestoreL1AfterMap(window, false)
        LegacyClusterMap.mapStartupComplete(window)
        assertNull(recoveryContext())
        field(LegacyClusterMap::class.java, "activity").set(LegacyClusterMap, null)
        LegacyClusterMap.setRestoreL1AfterMap(window, true)
        LegacyClusterMap.mapStartupComplete(window)
        assertNull(recoveryContext())
    }

    private fun recoveryContext() = field(LegacyL1WakeRecovery::class.java, "registered")
        .get(LegacyL1WakeRecovery)

    private fun field(type: Class<*>, name: String) = type.getDeclaredField(name).apply { isAccessible = true }
}
