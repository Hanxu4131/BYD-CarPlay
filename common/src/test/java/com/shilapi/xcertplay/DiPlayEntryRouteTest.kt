package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiPlayEntryRouteTest {
    @Test fun completingConnectionOnHomeOpensProjectionOnce() {
        val route = DiPlayEntryRoute()
        route.entryIntent(launcher(), false)
        assertTrue(route.connectionChanged(true, true)) // Connection may finish before the first tick.
        assertFalse(route.connectionChanged(false, true))
        assertTrue(route.connectionChanged(true, true))
        assertFalse(route.connectionChanged(true, true))
        assertFalse(route.connectionChanged(false, true))
        assertTrue(route.connectionChanged(true, true))
    }

    @Test fun completingConnectionWhileEditingSettingsDoesNotInterruptThePage() {
        val route = DiPlayEntryRoute()
        assertFalse(route.connectionChanged(false, false))
        assertFalse(route.connectionChanged(true, false))
        assertFalse(route.connectionChanged(true, true))
    }
    private fun launcher() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    @Test fun fullscreenLauncherRelaunchReturnsToTheConnectedProjection() {
        val route = DiPlayEntryRoute()
        route.projectionOpened()
        assertTrue(route.entryIntent(launcher().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), true))
        assertTrue(route.entryIntent(launcher(), true)) // Split launcher relaunch follows the same rule.
    }

    @Test fun anExplicitBackToHomeRemainsHomeOnLaterLauncherRelaunch() {
        val route = DiPlayEntryRoute()
        route.projectionOpened()
        assertFalse(route.entryIntent(Intent().putExtra("page", "home"), true))
        assertFalse(route.entryIntent(launcher(), true))
        route.projectionOpened()
        assertTrue(route.entryIntent(launcher(), true))
    }

    @Test fun explicitSettingsAndConnectionPagesAreNeverRedirected() {
        for (page in listOf("settings", "connection", "home")) {
            val route = DiPlayEntryRoute()
            route.projectionOpened()
            assertFalse(route.entryIntent(launcher().putExtra("page", page), true))
            assertFalse(route.entryIntent(launcher(), true))
        }
    }

    @Test fun disconnectedSessionsAndRestoredOrNonLauncherEntriesStayInTheApp() {
        val route = DiPlayEntryRoute()
        assertFalse(route.entryIntent(launcher(), false))
        assertFalse(route.entryIntent(launcher(), true, restoring = true))
        assertFalse(route.entryIntent(Intent(Intent.ACTION_MAIN), true))
        assertFalse(route.entryIntent(Intent(), true))
        assertFalse(route.entryIntent(Intent("android.hardware.usb.action.USB_DEVICE_ATTACHED"), true))
        assertFalse(route.shouldRestoreProjection(true)) // Unknown preference cannot hijack a cached page.
    }

    @Test fun homeCanRestoreAnExistingEntryWithoutDeliveringANewIntent() {
        val route = DiPlayEntryRoute()
        route.entryIntent(Intent().putExtra("page", "settings"), true)
        route.projectionOpened() // Host's real focus callback records that CarPlay is visible again.
        assertTrue(route.shouldRestoreProjection(true))
        assertFalse(route.shouldRestoreProjection(false))
    }

    @Test fun restoringAnOldPageIntentDoesNotOverrideTheNewerHostPreference() {
        val route = DiPlayEntryRoute()
        val oldSettings = Intent().putExtra("page", "settings")
        route.entryIntent(oldSettings, true)
        route.projectionOpened()
        assertFalse(route.entryIntent(oldSettings, true, restoring = true))
        assertTrue(route.shouldRestoreProjection(true))
        // The same page extra delivered as a fresh user request must still take precedence.
        assertFalse(route.entryIntent(oldSettings, true))
        assertFalse(route.shouldRestoreProjection(true))
    }

    @Test fun automaticRedirectIsConsumedUntilHostActuallyReturnsToThePicture() {
        val route = DiPlayEntryRoute()
        route.projectionOpened()
        assertTrue(route.shouldRestoreProjection(true))
        route.projectionOpened() // openProjection attempts the redirect.
        route.projectionRedirected()
        assertFalse(route.shouldRestoreProjection(true))
        assertFalse(route.shouldRestoreProjection(true))
        route.projectionOpened() // Actual Host focus enables the next Home restoration.
        assertTrue(route.shouldRestoreProjection(true))
        route.entryIntent(Intent().putExtra("page", "home"), true)
        assertFalse(route.shouldRestoreProjection(true))
    }

    @Test fun systemHomeFromAConnectedConfigurationPageReturnsToProjection() {
        val route = DiPlayEntryRoute()
        route.entryIntent(Intent().putExtra("page", "settings"), true)
        assertFalse(route.shouldRestoreProjection(true)) // Opening settings itself stays there.
        route.userLeaving(connected = true)
        assertTrue(route.shouldRestoreProjection(true))
        route.projectionRedirected()
        assertFalse(route.shouldRestoreProjection(true))
    }

    @Test fun homeWithoutAConnectionAndExternalSettingsReturnKeepTheConfigurationPage() {
        val route = DiPlayEntryRoute()
        route.entryIntent(Intent().putExtra("page", "settings"), false)
        route.userLeaving(connected = false)
        assertFalse(route.shouldRestoreProjection(false))
        assertFalse(route.shouldRestoreProjection(true)) // No Home restoration was armed.
        route.entryIntent(Intent().putExtra("page", "settings"), true)
        route.userLeaving(connected = true, openingExternalPage = true)
        assertFalse(route.shouldRestoreProjection(true))
        route.userLeaving(connected = true) // A later real Home press is still independent.
        assertTrue(route.shouldRestoreProjection(true))
    }
}
