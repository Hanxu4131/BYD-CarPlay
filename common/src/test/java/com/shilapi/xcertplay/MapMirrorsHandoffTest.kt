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
class MapMirrorsHandoffTest {
    private val key = "handoff-test"
    private val textures = mutableListOf<SurfaceTexture>()
    private val surfaces = mutableListOf<Surface>()
    private val readiness = mutableListOf<Boolean>()
    private val presentation = mutableListOf<Boolean>()
    private val readyListener: (String, Surface?, Boolean) -> Unit = { eventKey, _, ready ->
        if (eventKey == key) readiness.add(ready)
    }
    private val presentedListener: (String, Surface?, Boolean) -> Unit = { eventKey, _, ready ->
        if (eventKey == key) presentation.add(ready)
    }

    private fun attach(): Surface {
        val surface = Surface(SurfaceTexture(0).also { textures.add(it) }).also { surfaces.add(it) }
        MapMirrors.set(key, surface)
        MapMirrors.addReadinessListener(readyListener)
        MapMirrors.addPresentedListener(presentedListener)
        return surface
    }

    @After fun cleanUp() {
        MapMirrors.removeReadinessListener(readyListener)
        MapMirrors.removePresentedListener(presentedListener)
        MapMirrors.sink = null
        MapMirrors.set(key, null)
        MapMirrors.setStreamActive(false)
        shadowOf(Looper.getMainLooper()).idle()
        surfaces.forEach { if (it.isValid) it.release() }
        textures.forEach(SurfaceTexture::release)
    }

    @Test fun adoptingTheSameRendererKeepsBothSignalsWithoutRecreatingItsDecoder() {
        val surface = attach()
        MapMirrors.frameRenderedCallback(key, surface)()
        MapMirrors.framePresentedCallback(key, surface)()
        val applied = mutableListOf<Surface?>()
        MapMirrors.sink = { eventKey, output -> if (eventKey == key) applied.add(output) }
        MapMirrors.reapply(preserveReadiness = true)
        assertTrue(MapMirrors.isReady(key, surface))
        assertTrue(MapMirrors.isPresented(key, surface))
        assertEquals(listOf(true), readiness)
        assertEquals(listOf(true), presentation)
        assertTrue(applied.isEmpty())
    }

    @Test fun retainedFallbackReadinessDoesNotInventPresentationAndKeepsItsLiveCallback() {
        val surface = attach()
        MapMirrors.frameRenderedCallback(key, surface)()
        val pendingPresentation = MapMirrors.framePresentedCallback(key, surface)
        Thread { pendingPresentation() }.apply { start(); join() }
        MapMirrors.sink = { _, _ -> }
        MapMirrors.reapply(preserveReadiness = true)
        assertTrue(MapMirrors.isReady(key, surface))
        assertFalse(MapMirrors.isPresented(key, surface))
        assertEquals(listOf(true), readiness)
        assertTrue(presentation.isEmpty())
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(MapMirrors.isPresented(key, surface))
        assertEquals(listOf(true), presentation)
    }

    @Test fun anActuallyPresentedSurfaceIsRetainedWithoutInventingFallbackReadiness() {
        val surface = attach()
        MapMirrors.framePresentedCallback(key, surface)()
        MapMirrors.sink = { eventKey, _ -> if (eventKey == key) fail("Retained decoder must stay attached") }
        MapMirrors.reapply(preserveReadiness = true)
        assertFalse(MapMirrors.isReady(key, surface))
        assertTrue(MapMirrors.isPresented(key, surface))
        assertTrue(readiness.isEmpty())
        assertEquals(listOf(true), presentation)
    }

    @Test fun aNewSessionResetsBothSignalsAndRejectsItsPredecessorsCallbacks() {
        val surface = attach()
        MapMirrors.frameRenderedCallback(key, surface)()
        MapMirrors.framePresentedCallback(key, surface)()
        val oldReady = MapMirrors.frameRenderedCallback(key, surface)
        val oldPresented = MapMirrors.framePresentedCallback(key, surface)
        val applied = mutableListOf<Surface?>()
        MapMirrors.sink = { eventKey, output -> if (eventKey == key) applied.add(output) }
        MapMirrors.reapply()
        oldReady(); oldPresented()
        assertFalse(MapMirrors.isReady(key, surface))
        assertFalse(MapMirrors.isPresented(key, surface))
        assertEquals(listOf(true, false), readiness)
        assertEquals(listOf(true, false), presentation)
        assertEquals(listOf(surface), applied)
        MapMirrors.frameRenderedCallback(key, surface)()
        MapMirrors.framePresentedCallback(key, surface)()
        assertTrue(MapMirrors.isReady(key, surface))
        assertTrue(MapMirrors.isPresented(key, surface))
    }

    @Test fun anUnreadySurfaceStillInvalidatesCallbacksQueuedBeforeHandoff() {
        val surface = attach()
        val ready = MapMirrors.frameRenderedCallback(key, surface)
        val presented = MapMirrors.framePresentedCallback(key, surface)
        Thread { ready(); presented() }.apply { start(); join() }
        val applied = mutableListOf<Surface?>()
        MapMirrors.sink = { eventKey, output -> if (eventKey == key) applied.add(output) }
        MapMirrors.reapply(preserveReadiness = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(MapMirrors.isReady(key, surface))
        assertFalse(MapMirrors.isPresented(key, surface))
        assertEquals(listOf(false), readiness)
        assertEquals(listOf(false), presentation)
        assertEquals(listOf(surface), applied)
    }

    @Test fun preservingAReadyReplacementCannotReactivateTheOldSurface() {
        val old = attach()
        val oldReady = MapMirrors.frameRenderedCallback(key, old)
        val oldPresented = MapMirrors.framePresentedCallback(key, old)
        val replacement = Surface(SurfaceTexture(0).also { textures.add(it) }).also { surfaces.add(it) }
        MapMirrors.set(key, replacement)
        MapMirrors.frameRenderedCallback(key, replacement)()
        MapMirrors.framePresentedCallback(key, replacement)()
        MapMirrors.sink = { _, _ -> }
        MapMirrors.reapply(preserveReadiness = true)
        oldReady(); oldPresented()
        assertTrue(MapMirrors.isReady(key, replacement))
        assertTrue(MapMirrors.isPresented(key, replacement))
        assertFalse(MapMirrors.isReady(key, old))
        assertFalse(MapMirrors.isPresented(key, old))
        assertEquals(listOf(false, true), readiness)
        assertEquals(listOf(false, true), presentation)
    }

    @Test fun anInvalidSurfaceCannotKeepItsOldReadiness() {
        val surface = attach()
        MapMirrors.frameRenderedCallback(key, surface)()
        MapMirrors.framePresentedCallback(key, surface)()
        surface.release()
        MapMirrors.sink = { _, _ -> }
        MapMirrors.reapply(preserveReadiness = true)
        assertFalse(MapMirrors.isReady(key, surface))
        assertFalse(MapMirrors.isPresented(key, surface))
        assertEquals(listOf(true, false), readiness)
        assertEquals(listOf(true, false), presentation)
    }
}
