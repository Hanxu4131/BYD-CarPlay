package com.shilapi.xcertplay

import android.graphics.Bitmap
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
    @Test fun publicLoadingMarksRemainReadableAtDifferentRenderSizes() {
        val context = RuntimeEnvironment.getApplication()
        for (id in listOf(R.drawable.cluster_carplay_logo_vector, R.drawable.cluster_carplay_ultra_logo_vector)) {
            for (scale in listOf(1, 3)) {
                val mark = requireNotNull(context.getDrawable(id))
                val width = (mark.intrinsicWidth * scale).coerceAtLeast(1)
                val height = (mark.intrinsicHeight * scale).coerceAtLeast(1)
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                mark.setBounds(0, 0, width, height)
                mark.draw(Canvas(image))
                assertEquals("Outer corners should remain transparent", 0, Color.alpha(image.getPixel(0, 0)))
                val pixels = IntArray(width * height)
                image.getPixels(pixels, 0, width, 0, 0, width, height)
                val visible = pixels.count { Color.alpha(it) >= 128 }
                assertTrue("Loading glyph must be visible", visible > height * height / 10)
                assertTrue("The surrounding text area remains transparent", visible < width * height / 2)
            }
        }
    }
}
