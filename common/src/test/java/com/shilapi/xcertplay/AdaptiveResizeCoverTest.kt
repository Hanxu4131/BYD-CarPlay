package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.view.View
import android.os.Looper
import java.time.Duration
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode
import android.widget.ImageView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class AdaptiveResizeCoverTest {
    @Test fun theResizeLogoDoesNotExpireAtTheNormalFiveSecondStartupDeadline() {
        val cover = AdaptiveResizeCover(RuntimeEnvironment.getApplication())
        cover.show()
        val startup = cover.getChildAt(0) as ClusterStartupView
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_900))
        assertTrue(startup.waitingForFrame)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertFalse(startup.waitingForFrame)
        cover.dispose()
    }

    @Test fun aSnapshotSkipsTheLogoAndDisposeDropsItWithoutRecyclingRenderStorage() {
        val cover = AdaptiveResizeCover(RuntimeEnvironment.getApplication())
        val frame = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
        cover.show(frame)
        val image = cover.getChildAt(1) as ImageView
        assertEquals(View.VISIBLE, cover.visibility)
        assertEquals(View.VISIBLE, image.visibility)
        assertEquals(View.GONE, cover.getChildAt(0).visibility)
        assertEquals(ImageView.ScaleType.FIT_CENTER, image.scaleType)
        assertNotNull(image.drawable)
        cover.dispose()
        assertEquals(View.GONE, cover.visibility)
        assertNull(image.drawable)
        assertFalse(frame.isRecycled)
    }

    @Test fun aPreviousAnimationCannotHideTheReplacementSnapshot() {
        val cover = AdaptiveResizeCover(RuntimeEnvironment.getApplication())
        cover.show()
        val oldFinish = (cover.getChildAt(0) as ClusterStartupView).onHidden!!
        val next = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888)
        cover.show(next)
        oldFinish()
        assertEquals(View.VISIBLE, cover.visibility)
        assertNotNull((cover.getChildAt(1) as ImageView).drawable)
        cover.dispose()
    }

    @Test fun aMissingFrameUsesTheExistingLogoAndItsCompletionClearsTheParent() {
        val cover = AdaptiveResizeCover(RuntimeEnvironment.getApplication())
        cover.show()
        val startup = cover.getChildAt(0) as ClusterStartupView
        assertEquals(View.VISIBLE, startup.visibility)
        assertEquals(View.GONE, cover.getChildAt(1).visibility)
        startup.onHidden!!.invoke()
        assertEquals(View.GONE, cover.visibility)
        cover.dispose()
    }
}
