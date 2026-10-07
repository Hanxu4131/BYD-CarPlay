package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AmbientAlbumPaletteTest {
    @Test fun allOemChoicesHaveOpaquePreviewColorsAndChineseNames() {
        for (index in 1..31) {
            assertEquals(255, AmbientAlbumPalette.previewRgb(index) ushr 24)
            assertTrue(AmbientAlbumPalette.previewName(index).isNotBlank())
        }
        assertEquals(0xffa84cc1.toInt(), AmbientAlbumPalette.previewRgb(1))
        assertEquals("紫色", AmbientAlbumPalette.previewName(1))
        assertEquals(0xffb8b8b8.toInt(), AmbientAlbumPalette.previewRgb(29))
        assertEquals("白色", AmbientAlbumPalette.previewName(29))
        assertEquals("浅蓝", AmbientAlbumPalette.previewName(31))
    }
    @Test fun majorityColoredRegionWinsOverMinorAccentAndGreyBorder() {
        val pixels = IntArray(576) { when {
            it < 300 -> 0xffdd2222.toInt()
            it < 400 -> 0xff2222dd.toInt()
            else -> 0xff777777.toInt()
        } }
        assertEquals(0xffdd2222.toInt(), AmbientAlbumPalette.dominantRgb(pixels))
    }
    @Test fun transparentBlackWhiteAndGreysHaveNoValidHue() {
        assertNull(AmbientAlbumPalette.dominantRgb(intArrayOf(0xff000000.toInt(),
            0xffffffff.toInt(), 0xff777777.toInt(), 0x00ff0000)))
        assertNull(AmbientAlbumPalette.dominantRgb(intArrayOf()))
    }
    @Test fun albumPaletteUsesOemNearbyHuesAndInvalidCoverFallsBackToAllColors() {
        assertEquals(listOf(1, 2), ambientColorPalette(AmbientColorSource.ALBUM, listOf(8), 1))
        assertFalse(ambientColorPalette(AmbientColorSource.ALBUM, emptyList(), 28).contains(29))
        assertEquals(3, AmbientAlbumPalette.bydColor(0xff2237ac.toInt()))
        assertEquals(14, AmbientAlbumPalette.bydColor(0xff30a557.toInt()))
        assertEquals(26, AmbientAlbumPalette.bydColor(0xffb8224c.toInt()))
        assertEquals((1..31).toList(), ambientColorPalette(AmbientColorSource.ALBUM, listOf(8), null))
        assertEquals((1..31).toList(), ambientColorPalette(AmbientColorSource.ALBUM, listOf(8), 99))
        assertEquals(listOf(2, 8), ambientColorPalette(AmbientColorSource.SELECTED, listOf(8, 2), 1))
    }
    @Test fun fallbackRandomChangesRequireMusicalEventsAndNeverRepeatCurrentColor() {
        var draws = 0
        val detector = AmbientMusicColorDetector(1, (1..31).toList(), AmbientColorSpeed.STANDARD) {
            draws++; 0
        }
        assertEquals(1, detector.color(AmbientColorMode.BEAT, 0, 0.5))
        assertEquals(1, detector.color(AmbientColorMode.BEAT, 50, 0.01))
        assertEquals(2, detector.color(AmbientColorMode.BEAT, 500, 0.5))
        assertEquals(1, draws)
        repeat(20) { assertEquals(2, detector.color(AmbientColorMode.BEAT, 550L + it * 50, 0.01)) }
        assertEquals(1, draws)
    }
    @Test fun coverArrivalDoesNotResetKnownBpmOrBeatPhase() {
        val detector = AmbientMusicColorDetector(1, (1..31).toList())
        for (time in 0L..2000L step 50) {
            detector.color(AmbientColorMode.TEMPO, time, if (time % 500 == 0L) 0.5 else 0.01)
        }
        assertEquals(120.0, detector.estimatedBpm!!, 0.01)
        detector.updatePalette(14, ambientColorPalette(AmbientColorSource.ALBUM, emptyList(), 14))
        assertEquals(120.0, detector.estimatedBpm!!, 0.01)
        for (time in 2050L..3000L step 50) {
            detector.color(AmbientColorMode.TEMPO, time, if (time % 500 == 0L) 0.5 else 0.01)
        }
        assertEquals(120.0, detector.estimatedBpm!!, 0.01)
    }
    @Test fun changingPaletteKeepsBeatTimingAndBpmEstimation() {
        val selected = AmbientMusicColorDetector(1, listOf(1, 5, 10))
        val album = AmbientMusicColorDetector(15, ambientColorPalette(AmbientColorSource.ALBUM, emptyList(), 15))
        for (time in 0L..3000L step 50) {
            val rms = if (time % 500 == 0L) 0.5 else 0.01
            selected.color(AmbientColorMode.TEMPO, time, rms)
            album.color(AmbientColorMode.TEMPO, time, rms)
            assertEquals(selected.estimatedBpm, album.estimatedBpm)
        }
        assertEquals(120.0, album.estimatedBpm!!, 0.01)
    }
}
