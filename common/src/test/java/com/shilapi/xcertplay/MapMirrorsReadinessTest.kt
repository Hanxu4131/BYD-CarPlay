package com.shilapi.xcertplay

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class MapMirrorsReadinessTest {
    private val key = "readiness-test"
    private val textures = mutableListOf<SurfaceTexture>()
    private val surfaces = mutableListOf<Surface>()
    private val listeners = mutableListOf<(String, Surface?, Boolean) -> Unit>()

    private fun surface(): Surface = Surface(SurfaceTexture(0).also { textures.add(it) })
        .also { surfaces.add(it) }

    @After fun cleanUp() {
        listeners.forEach(MapMirrors::removeReadinessListener)
        MapMirrors.sink = null
        MapMirrors.set(key, null)
        MapMirrors.setStreamActive(false)
        shadowOf(Looper.getMainLooper()).idle()
        surfaces.forEach(Surface::release)
        textures.forEach(SurfaceTexture::release)
    }

    @Test fun oldSurfaceCannotMarkItsReplacementReady() {
        val old = surface()
        val replacement = surface()
        MapMirrors.set(key, old)
        val staleFrame = MapMirrors.frameRenderedCallback(key, old)
        MapMirrors.set(key, replacement)
        staleFrame()
        assertFalse(MapMirrors.isReady(key, replacement))
        MapMirrors.frameRenderedCallback(key, replacement)()
        assertTrue(MapMirrors.isReady(key, replacement))
        assertFalse(MapMirrors.isReady(key, old))
    }

    @Test fun reconnectInvalidatesQueuedFrameEvenForTheSameSurface() {
        val surface = surface()
        MapMirrors.set(key, surface)
        val staleFrame = MapMirrors.frameRenderedCallback(key, surface)
        Thread { staleFrame() }.apply { start(); join() }
        MapMirrors.sink = { _, _ -> }
        MapMirrors.reapply()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(MapMirrors.isReady(key, surface))
        MapMirrors.frameRenderedCallback(key, surface)()
        assertTrue(MapMirrors.isReady(key, surface))
    }

    @Test fun streamStartDoesNotRecreateMirrorsAndStopClearsReadiness() {
        val surface = surface()
        var applications = 0
        MapMirrors.sink = { _, _ -> applications++ }
        MapMirrors.set(key, surface)
        MapMirrors.setStreamActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, applications)
        assertFalse(MapMirrors.isReady(key, surface))
        val frame = MapMirrors.frameRenderedCallback(key, surface)
        frame()
        assertTrue(MapMirrors.isReady(key, surface))
        MapMirrors.setStreamActive(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(MapMirrors.isReady(key, surface))
        MapMirrors.setStreamActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        frame()
        assertTrue(MapMirrors.isReady(key, surface))
        assertEquals(1, applications)
    }

    @Test fun readinessListenerRunsOnMainAndCanBeRemoved() {
        val surface = surface()
        MapMirrors.set(key, surface)
        val events = mutableListOf<Boolean>()
        val listener: (String, Surface?, Boolean) -> Unit = { eventKey, eventSurface, ready ->
            if (eventKey == key) {
                assertSame(Looper.getMainLooper(), Looper.myLooper())
                assertSame(surface, eventSurface)
                events.add(ready)
            }
        }
        listeners.add(listener)
        MapMirrors.addReadinessListener(listener)
        val frame = MapMirrors.frameRenderedCallback(key, surface)
        Thread { frame() }.apply { start(); join() }
        assertTrue(events.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(true), events)
        MapMirrors.removeReadinessListener(listener)
        MapMirrors.set(key, surface)
        assertEquals(listOf(true), events)
    }
    @Test fun startupFallbackReadinessDoesNotProveActualPresentation() {
        val surface = surface()
        MapMirrors.set(key,surface)
        MapMirrors.frameRenderedCallback(key,surface)()
        assertTrue(MapMirrors.isReady(key,surface))
        assertFalse(MapMirrors.isPresented(key,surface))
        MapMirrors.framePresentedCallback(key,surface)()
        assertTrue(MapMirrors.isPresented(key,surface))
        MapMirrors.set(key,surface)
        assertFalse(MapMirrors.isPresented(key,surface))
    }

    @Test fun actualPresentationRejectsOldStreamAndOldSurface() {
        val surface = surface()
        MapMirrors.set(key,surface)
        MapMirrors.setStreamActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        val old = MapMirrors.framePresentedCallback(key,surface)
        MapMirrors.setStreamActive(false)
        shadowOf(Looper.getMainLooper()).idle()
        old()
        assertFalse(MapMirrors.isPresented(key,surface))
        val replacement = surface()
        MapMirrors.set(key,replacement)
        old()
        assertFalse(MapMirrors.isPresented(key,replacement))
        MapMirrors.framePresentedCallback(key,replacement)()
        assertTrue(MapMirrors.isPresented(key,replacement))
    }

}
