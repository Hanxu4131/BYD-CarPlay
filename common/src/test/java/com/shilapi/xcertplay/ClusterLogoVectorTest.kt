package com.shilapi.xcertplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClusterLogoVectorTest {
    @Test fun vectorRetainsTheOriginalMarkSilhouetteAndLetterHoles() {
        val context = RuntimeEnvironment.getApplication()
        val original = BitmapFactory.decodeResource(context.resources, R.drawable.cluster_carplay_ultra_logo)
        val rendered = Bitmap.createBitmap(584, 98, Bitmap.Config.ARGB_8888)
        val mark = requireNotNull(context.getDrawable(R.drawable.cluster_carplay_ultra_logo_vector))
        mark.setBounds(0, 0, 584, 98)
        mark.draw(Canvas(rendered))
        var error = 0L
        var filled = 0
        for (y in 0 until 98) for (x in 0 until 584) {
            val a = Color.alpha(original.getPixel(x, y))
            val b = Color.alpha(rendered.getPixel(x, y))
            error += kotlin.math.abs(a - b)
            if (b >= 128) filled++
        }
        assertTrue("Vector contour changed the mark: error=${error / (584.0 * 98)}", error / (584.0 * 98) < 4.0)
        assertTrue("Mark must contain both letters and transparent holes", filled in 10000..40000)
    }
    @Test fun ordinaryLogoRetainsTheFullCarPlayGlyphsWithoutTheUltraWord() {
        val context = RuntimeEnvironment.getApplication()
        val ultra = requireNotNull(context.getDrawable(R.drawable.cluster_carplay_ultra_logo_vector))
        val ordinary = requireNotNull(context.getDrawable(R.drawable.cluster_carplay_logo_vector))
        assertEquals(584f / 98f, ultra.intrinsicWidth.toFloat() / ultra.intrinsicHeight, .02f)
        assertEquals(400f / 98f, ordinary.intrinsicWidth.toFloat() / ordinary.intrinsicHeight, .02f)
        val full = Bitmap.createBitmap(584, 98, Bitmap.Config.ARGB_8888)
        ultra.setBounds(0, 0, 584, 98)
        ultra.draw(Canvas(full))
        val crop = Bitmap.createBitmap(400, 98, Bitmap.Config.ARGB_8888)
        ordinary.setBounds(0, 0, 400, 98)
        ordinary.draw(Canvas(crop))
        var error = 0L
        var rightEdge = 0
        for (y in 0 until 98) for (x in 0 until 400) {
            error += kotlin.math.abs(Color.alpha(full.getPixel(x, y)) - Color.alpha(crop.getPixel(x, y)))
            if (x >= 395) rightEdge += Color.alpha(crop.getPixel(x, y))
        }
        assertTrue("The ordinary logo must retain the original CarPlay contours", error / (400.0 * 98) < 1.0)
        assertEquals("No glyph should touch the crop edge", 0, rightEdge)
    }

}
