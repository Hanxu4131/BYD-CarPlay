package com.shilapi.xcertplay

import android.content.Intent

/** Launcher relaunches keep the current projection; explicit app pages remain user controlled. */
internal class DiPlayEntryRoute {
    private var projectionPreferred: Boolean? = null
    private var restorationPending = false
    private var lastConnected: Boolean? = null

    fun connectionChanged(connected: Boolean, onHome: Boolean): Boolean {
        val newlyConnected = lastConnected == false && connected
        lastConnected = connected
        return newlyConnected && onHome
    }

    fun projectionOpened() { projectionPreferred = true; restorationPending = true }

    fun shouldRestoreProjection(connected: Boolean): Boolean =
        connected && projectionPreferred == true && restorationPending

    /** A failed or interrupted redirect must not retry on every resume. Real Host focus rearms it. */
    fun projectionRedirected() { restorationPending = false }

    fun userLeaving(connected: Boolean, openingExternalPage: Boolean = false) {
        if (openingExternalPage) return
        if (connected) projectionOpened()
        else restorationPending = false
    }

    fun entryIntent(intent: Intent, connected: Boolean, restoring: Boolean = false): Boolean {
        if (!connected) lastConnected = false
        // A retained page extra belongs to the old visit, not the new launcher restoration.
        if (restoring) return false
        if (intent.hasExtra("page")) {
            projectionPreferred = false
            restorationPending = false
            return false
        }
        return connected && projectionPreferred != false &&
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
    }
}

internal object DiPlayEntryRouting {
    val route = DiPlayEntryRoute()
}
