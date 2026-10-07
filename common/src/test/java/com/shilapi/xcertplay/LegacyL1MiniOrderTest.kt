package com.shilapi.xcertplay

import android.content.pm.PackageInfo
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LegacyL1MiniOrderTest {
    private fun installDashboard(exported: Boolean, enabled: Boolean = true) {
        val app = ApplicationInfo().apply { packageName = "l1tech.com.l1mini"; this.enabled = true }
        shadowOf(RuntimeEnvironment.getApplication().packageManager).installPackage(PackageInfo().apply {
            packageName = app.packageName
            applicationInfo = app
            activities = arrayOf(ActivityInfo().apply {
                packageName = app.packageName
                name = "l1tech.com.l1mini.Dashboard"
                applicationInfo = app
                this.exported = exported
                this.enabled = enabled
            })
        })
    }

    @Test fun dashboardMustBeVisibleAndExportedBeforeCreatingIt() {
        val packageManager = RuntimeEnvironment.getApplication().packageManager
        assertFalse(LegacyL1MiniOrder.dashboardLaunchable(packageManager))
        installDashboard(exported = true)
        assertTrue(LegacyL1MiniOrder.dashboardLaunchable(packageManager))
    }

    @Test fun anUnexportedDashboardCannotBeCreated() {
        installDashboard(exported = false)
        assertFalse(LegacyL1MiniOrder.dashboardLaunchable(RuntimeEnvironment.getApplication().packageManager))
    }

    @Test fun aDisabledDashboardCannotBeCreated() {
        installDashboard(exported = true, enabled = false)
        assertFalse(LegacyL1MiniOrder.dashboardLaunchable(RuntimeEnvironment.getApplication().packageManager))
    }

    @Test fun reportsL1MiniInstalledOnlyWhenItsPackageIsVisible() {
        val packageManager = RuntimeEnvironment.getApplication().packageManager
        assertFalse(LegacyL1MiniOrder.isInstalled(packageManager))

        shadowOf(packageManager).installPackage(PackageInfo().apply {
            packageName = "l1tech.com.l1mini"
        })

        assertTrue(LegacyL1MiniOrder.isInstalled(packageManager))
    }

    @Test fun recognizesAnExistingL1DashboardTask() {
        assertTrue(LegacyL1MiniOrder.hasExistingDashboard(
            "  * Hist #0: ActivityRecord{abc u0 l1tech.com.l1mini/.Dashboard, StackId =302 t278}"))
    }

    @Test fun aLauncherOrHistoricalIntentDoesNotAuthorizeDashboardCreation() {
        assertFalse(LegacyL1MiniOrder.hasExistingDashboard(
            "intent={cmp=l1tech.com.l1mini/.Dashboard}\n" +
                "ActivityRecord{abc u0 l1tech.com.l1mini/.MainActivity_v2, t277}"))
        assertFalse(LegacyL1MiniOrder.hasExistingDashboard(
            "ActivityRecord{abc u0 other.package/.Dashboard, t277}"))
    }
}
