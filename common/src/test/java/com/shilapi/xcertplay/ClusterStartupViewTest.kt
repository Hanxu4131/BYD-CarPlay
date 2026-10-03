package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.robolectric.shadows.ShadowChoreographer
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterStartupViewTest {
    @Before fun controlFrameClock() {
        // Let idleFor drive frames; automatic vsync advancement can overshoot the timeout boundary.
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    private fun advanceFrames(millis: Long) {
        var remaining = millis
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step))
            remaining -= step
        }
    }

    @Test fun earlyFrameKeepsMinimumShowTimeButDoesNotWaitForTimeout() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cover = ClusterStartupView(controller.get())
        controller.get().setContentView(cover)
        var hidden = 0
        cover.onHidden = { hidden++; assertEquals(View.GONE, cover.visibility) }
        cover.waitForFrame()
        assertEquals(org.robolectric.shadows.ShadowLog.getLogsForTag("DiPlay-ClusterStartup").toString(), View.VISIBLE, cover.visibility)
        cover.revealMap()
        advanceFrames(1_400)
        assertEquals(View.VISIBLE, cover.visibility)
        assertEquals(0, hidden)
        advanceFrames(450)
        assertEquals(View.GONE, cover.visibility)
        assertEquals(1, hidden)
        controller.pause().stop().destroy()
    }

    @Test fun missingFrameCallbackCannotCoverTheMapIndefinitely() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cover = ClusterStartupView(controller.get())
        controller.get().setContentView(cover)
        cover.waitForFrame()
        advanceFrames(4_900)
        assertEquals(org.robolectric.shadows.ShadowLog.getLogsForTag("DiPlay-ClusterStartup").toString(), View.VISIBLE, cover.visibility)
        advanceFrames(450)
        assertEquals(View.GONE, cover.visibility)
        controller.pause().stop().destroy()
    }

    @Test fun newSurfaceDuringFadeKeepsItsOwnCoverAndDisposalCancelsIt() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cover = ClusterStartupView(controller.get())
        controller.get().setContentView(cover)
        var hidden = 0
        cover.onHidden = { hidden++ }
        cover.waitForFrame()
        advanceFrames(1_510)
        cover.revealMap()
        cover.waitForFrame()
        advanceFrames(350)
        assertEquals(org.robolectric.shadows.ShadowLog.getLogsForTag("DiPlay-ClusterStartup").toString(), View.VISIBLE, cover.visibility)
        assertEquals(1f, cover.alpha)
        assertEquals(0, hidden)
        cover.dispose()
        assertEquals(View.GONE, cover.visibility)
        advanceFrames(350)
        assertEquals(0, hidden)
        controller.pause().stop().destroy()
    }
    @Test fun disposalBeforeMinimumShowTimeCancelsDeferredReveal() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cover = ClusterStartupView(controller.get())
        controller.get().setContentView(cover)
        var hidden = 0
        cover.onHidden = { hidden++ }
        cover.waitForFrame()
        cover.revealMap()
        cover.dispose()
        advanceFrames(5_350)
        assertEquals(View.GONE, cover.visibility)
        assertEquals(0, hidden)
        controller.pause().stop().destroy()
    }

    @Test fun centreFrameRevealsWithoutInstrumentMinimumAndModeCanChangeWhileWaiting() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val cover = ClusterStartupView(controller.get(), minimumShowMs = 0L, fixedAspectRatio = 16f / 9f)
        controller.get().setContentView(cover)
        assertFalse(cover.isUltra())
        cover.waitForFrame()
        cover.setUltra(true)
        assertTrue(cover.isUltra())
        assertEquals(View.VISIBLE, cover.visibility)
        cover.revealMap()
        advanceFrames(350)
        assertEquals(View.GONE, cover.visibility)
        cover.setUltra(false)
        assertFalse(cover.isUltra())
        controller.pause().stop().destroy()
    }

    @Test fun centreArtworkKeepsItsRatioAndIsCentredOnWideAndTallWindows() {
        val wide = StartupArtworkBounds.fit(2400, 900, 16f / 9f)
        assertEquals(400f, wide.left, .01f)
        assertEquals(0f, wide.top, .01f)
        assertEquals(1600f, wide.width, .01f)
        assertEquals(900f, wide.height, .01f)
        val tall = StartupArtworkBounds.fit(800, 900, 16f / 9f)
        assertEquals(0f, tall.left, .01f)
        assertEquals(225f, tall.top, .01f)
        assertEquals(800f, tall.width, .01f)
        assertEquals(450f, tall.height, .01f)
        assertEquals(StartupArtworkBounds(0f, 0f, 2400f, 900f), StartupArtworkBounds.fit(2400, 900, null))
    }

}
