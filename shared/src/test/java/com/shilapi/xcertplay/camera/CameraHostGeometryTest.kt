package com.shilapi.xcertplay.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHostGeometryTest {
    @Test fun hostBoundsDetectSplitEvenWhenEncodedCanvasRemainsFixed() {
        // The classifier receives host/window pixels only; a 1920x1080 encoded canvas is irrelevant.
        val window = CameraHostWindowGeometry(
            hostWidthPx = 960,
            hostHeightPx = 1080,
            displayWidthPx = 1920,
            displayHeightPx = 1080,
            inMultiWindowMode = true,
        )
        assertTrue(CameraHostGeometry.classify(window) == true)
    }

    @Test fun fullWindowWithSmallSystemInsetsIsNotClassifiedAsSplit() {
        val window = CameraHostWindowGeometry(
            hostWidthPx = 1920,
            hostHeightPx = 1008,
            displayWidthPx = 1920,
            displayHeightPx = 1080,
            inMultiWindowMode = false,
        )
        assertFalse(CameraHostGeometry.classify(window) == true)
        assertEquals(false, CameraHostGeometry.classify(window))
    }

    @Test fun rotationUsesLongAndShortEdgesRatherThanLandscapeAssumptions() {
        val fullPortrait = CameraHostWindowGeometry(1032, 1880, 1080, 1920, false)
        val splitPortrait = CameraHostWindowGeometry(540, 1880, 1080, 1920, false)
        assertEquals(false, CameraHostGeometry.classify(fullPortrait))
        assertEquals(true, CameraHostGeometry.classify(splitPortrait))
    }

    @Test fun narrowEmbeddedHostStaysSplitEvenWhenWindowModeReportsFullscreen() {
        // Windowing-mode metadata is deliberately not an input to the classifier; only
        // actual host bounds relative to the physical display determine this state.
        val embedded = CameraHostWindowGeometry(1280, 1080, 1920, 1080, inMultiWindowMode = false)
        assertEquals(true, CameraHostGeometry.classify(embedded))
    }

    @Test fun unknownBoundsBlockActivationAndFullscreenForcesFalseImmediately() {
        assertNull(CameraHostGeometry.classify(CameraHostWindowGeometry(0, 0, 1920, 1080, false)))
        assertEquals(false, CameraHostGeometry.classify(
            CameraHostWindowGeometry(900, 1080, 1920, 1080, true, forceFullscreen = true),
        ))
    }

    @Test fun lifecyclePublishesFalseWhenPausedAndRecomputesOnResume() {
        val token = CameraHostGeometry.attachHost()
        try {
            val changes = mutableListOf<Boolean?>()
            val subscription = CameraHostGeometry.subscribe { changes.add(it) }
            val split = CameraHostWindowGeometry(960, 1080, 1920, 1080, true)
            assertTrue(CameraHostGeometry.setHostActive(token, true))
            assertNull(CameraHostGeometry.currentSplit())
            assertTrue(CameraHostGeometry.updateHost(token, split))
            assertEquals(true, CameraHostGeometry.currentSplit())
            assertTrue(CameraHostGeometry.setHostActive(token, false))
            assertEquals(false, CameraHostGeometry.currentSplit())
            assertTrue(CameraHostGeometry.setHostActive(token, true))
            assertNull(CameraHostGeometry.currentSplit()) // remeasure before opening the gate
            assertTrue(CameraHostGeometry.updateHost(token, split))
            assertEquals(true, CameraHostGeometry.currentSplit())
            assertEquals(listOf(null, true, false, null, true), changes)
            subscription.close()
        } finally {
            CameraHostGeometry.detachHost(token)
        }
    }

    @Test fun staleHostCannotPublishOrClearNewHostState() {
        val oldToken = CameraHostGeometry.attachHost()
        val currentToken = CameraHostGeometry.attachHost()
        try {
            assertFalse(CameraHostGeometry.updateHost(oldToken,
                CameraHostWindowGeometry(960, 1080, 1920, 1080, true)))
            assertFalse(CameraHostGeometry.detachHost(oldToken))
            assertNull(CameraHostGeometry.currentSplit())
            assertTrue(CameraHostGeometry.setHostActive(currentToken, true))
            assertTrue(CameraHostGeometry.updateHost(currentToken,
                CameraHostWindowGeometry(960, 1080, 1920, 1080, true)))
            assertEquals(true, CameraHostGeometry.currentSplit())
        } finally {
            CameraHostGeometry.detachHost(currentToken)
        }
    }
}
