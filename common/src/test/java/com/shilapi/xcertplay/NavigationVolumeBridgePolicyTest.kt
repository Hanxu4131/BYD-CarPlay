package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationVolumeBridgePolicyTest {
    @Test fun bydNavigationMinimumDoesNotCallTheRejectingPublicApi() {
        assertEquals(0, navigationBridgeMinimum(14) { error("Public API rejects private stream") })
    }

    @Test fun mediaMinimumStillUsesTheDeviceApi() {
        var requested = -1
        assertEquals(2, navigationBridgeMinimum(3) { requested = it; 2 })
        assertEquals(3, requested)
    }

    private class Port : NavigationVolumePort {
        var media = 6
        var navigation = 4
        var ignoreNavigationWrite = false
        var ignoreMediaWrite = false
        var navigationWritten: () -> Unit = {}
        val writes = mutableListOf<Pair<Int, Int>>()
        override fun volume(stream: Int) = if (stream == 3) media else navigation
        override fun minimum(stream: Int) = 0
        override fun maximum(stream: Int) = if (stream == 3) 39 else 10
        override fun setVolume(stream: Int, index: Int) {
            writes += stream to index
            if (stream == 14) {
                if (!ignoreNavigationWrite) navigation = index
                navigationWritten()
            } else if (!ignoreMediaWrite) media = index
        }
    }
    private val port = Port()
    private var ready = true
    private val reports = mutableListOf<String>()
    private val bridge = NavigationVolumeBridgePolicy(port, { ready }, { reports += it })
    private fun enable() = bridge.updateEligible(active = true, eligible = true)

    @Test fun singleWheelStepChangesNavigationThenRestoresMedia() {
        enable()
        bridge.onVolumeChanged(3, 5, 6, 0)
        assertEquals(listOf(14 to 5, 3 to 5), port.writes)
        assertEquals(5, port.navigation)
        assertEquals(5, port.media)
    }

    @Test fun exactSelfRestoreEventIsConsumedAndNextUserStepStillWorks() {
        enable()
        bridge.onVolumeChanged(3, 5, 6, 0)
        bridge.onVolumeChanged(3, 6, 5, 10)
        assertEquals(2, port.writes.size)
        port.media = 4
        bridge.onVolumeChanged(3, 5, 4, 20)
        assertEquals(listOf(14 to 5, 3 to 5, 14 to 4, 3 to 5), port.writes)
    }

    @Test fun unconfirmedNavigationWriteNeverRestoresMediaAndLogsFailureOnce() {
        enable()
        port.ignoreNavigationWrite = true
        bridge.onVolumeChanged(3, 5, 6, 0)
        bridge.onVolumeChanged(3, 5, 6, 1)
        assertEquals(listOf(14 to 5), port.writes)
        assertEquals(6, port.media)
        assertEquals(1, reports.size)
    }

    @Test fun staleBroadcastDoesNotWriteEitherVolume() {
        enable()
        port.media = 7
        bridge.onVolumeChanged(3, 5, 6, 0)
        bridge.onVolumeChanged(3, 6, 7, 1)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun concurrentWheelChangeAfterNavigationWriteIsNotOverwritten() {
        enable()
        port.navigationWritten = { port.media = 7 }
        bridge.onVolumeChanged(3, 5, 6, 0)
        assertEquals(listOf(14 to 5), port.writes)
        assertEquals(7, port.media)
        bridge.onVolumeChanged(3, 6, 7, 1)
        assertEquals(1, port.writes.size)
    }

    @Test fun voiceOrOwnershipChangeBetweenWritesLeavesMediaUntouched() {
        enable()
        port.navigationWritten = { ready = false }
        bridge.onVolumeChanged(3, 5, 6, 0)
        assertEquals(listOf(14 to 5), port.writes)
        assertEquals(6, port.media)
        ready = true
        bridge.onVolumeChanged(3, 5, 6, 1)
        assertEquals(1, port.writes.size)
    }

    @Test fun mediaRestoreFailureStopsFurtherBridgeAttempts() {
        enable()
        port.ignoreMediaWrite = true
        bridge.onVolumeChanged(3, 5, 6, 0)
        bridge.onVolumeChanged(3, 5, 6, 1)
        assertEquals(listOf(14 to 5, 3 to 5), port.writes)
        assertEquals(6, port.media)
    }

    @Test fun noNavigationOrDisabledSwitchLeavesOrdinaryMediaControlsAlone() {
        bridge.updateEligible(active = false, eligible = false)
        bridge.onVolumeChanged(3, 5, 6, 0)
        bridge.updateEligible(active = true, eligible = false)
        bridge.onVolumeChanged(3, 5, 6, 1)
        bridge.updateEligible(active = true, eligible = true)
        bridge.onVolumeChanged(3, 5, 6, 2)
        assertTrue(port.writes.isEmpty())
        bridge.updateEligible(active = false, eligible = false)
        enable()
        bridge.onVolumeChanged(3, 5, 6, 3)
        assertEquals(2, port.writes.size)
    }

    @Test fun mediaAliasesAndNavigationEventsAreNeverBridged() {
        enable()
        for (stream in listOf(0, 9, 10, 14, 16)) bridge.onVolumeChanged(stream, 5, 6, 0)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun wideChangesAndUnchangedBoundaryValuesAreSkipped() {
        enable()
        bridge.onVolumeChanged(3, 4, 6, 0)
        bridge.onVolumeChanged(3, 6, 6, 1)
        assertTrue(port.writes.isEmpty())
        bridge.onVolumeChanged(3, 5, 6, 2)
        assertEquals(2, port.writes.size)
    }

    @Test fun navigationStepClampsToItsOwnRange() {
        enable()
        port.navigation = 10
        bridge.onVolumeChanged(3, 5, 6, 0)
        assertEquals(listOf(14 to 10, 3 to 5), port.writes)
    }

    @Test fun unresolvedExpiredRestoreCannotBecomeAnInverseUserStep() {
        enable()
        bridge.onVolumeChanged(3, 5, 6, 0)
        bridge.onVolumeChanged(3, 6, 5, 1_000)
        assertEquals(2, port.writes.size)
        port.media = 6
        bridge.onVolumeChanged(3, 5, 6, 1_001)
        assertEquals(2, port.writes.size)
    }

    @Test fun inactivePlaybackResetsAFailedSegment() {
        enable()
        port.ignoreNavigationWrite = true
        bridge.onVolumeChanged(3, 5, 6, 0)
        bridge.updateEligible(active = false, eligible = false)
        port.ignoreNavigationWrite = false
        enable()
        bridge.onVolumeChanged(3, 5, 6, 1)
        assertEquals(listOf(14 to 5, 14 to 5, 3 to 5), port.writes)
    }

    @Test fun portExceptionDoesNotRestoreMedia() {
        enable()
        port.navigationWritten = { throw IllegalStateException("test failure") }
        bridge.onVolumeChanged(3, 5, 6, 0)
        assertEquals(listOf(14 to 5), port.writes)
        assertEquals(6, port.media)
    }

    @Test fun closeClearsEligibilityAndDiscardsQueuedEvents() {
        enable()
        bridge.close()
        bridge.onVolumeChanged(3, 5, 6, 0)
        assertTrue(port.writes.isEmpty())
    }
}
