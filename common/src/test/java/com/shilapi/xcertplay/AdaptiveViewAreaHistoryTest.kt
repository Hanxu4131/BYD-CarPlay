package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.MainAreaSelection
import com.shilapi.xcertplay.airplay.MainAreaViewport
import com.shilapi.xcertplay.airplay.MainViewArea
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test

class AdaptiveViewAreaHistoryTest {
    private val full = AdaptiveViewAreaHistory.Size(1920, 1080)
    private val split = AdaptiveViewAreaHistory.Size(1284, 990)

    @Test fun splitConnectionThenFullscreenReconnectCanReturnToTheSameSplit() {
        val remembered = AdaptiveViewAreaHistory.remember(emptyList(), split, full)
        val first = AdaptiveViewAreaHistory.areas(full, full, split, remembered)
        assertEquals(listOf(MainViewArea(1920, 1080), MainViewArea(1284, 990)), first)
        val reconnected = AdaptiveViewAreaHistory.areas(full, full, full, remembered)
        assertEquals(first, reconnected)
        val selection = MainAreaSelection(full.width, full.height, reconnected, 0)
        selection.receive(VideoCodec.H264, MainAreaViewport(1920, 1080, 0, 0, 1920, 1080))
        assertEquals(1, selection.select(split.width, split.height)?.first)
        assertTrue(selection.receive(VideoCodec.H264, MainAreaViewport(1920, 1080, 0, 0, 1284, 990)))
    }

    @Test fun sourceSizesAreScaledForTheCurrentResolution() {
        assertEquals(listOf(MainViewArea(1536, 864), MainViewArea(1027, 792)),
            AdaptiveViewAreaHistory.areas(full, AdaptiveViewAreaHistory.Size(1536, 864), full, listOf(split)))
    }

    @Test fun fullHeightChangesTinyWindowsAndDuplicatesDoNotFillTheHistory() {
        var history = AdaptiveViewAreaHistory.remember(emptyList(), split, full)
        for (candidate in listOf(AdaptiveViewAreaHistory.Size(1920, 990),
            AdaptiveViewAreaHistory.Size(1285, 991), AdaptiveViewAreaHistory.Size(100, 990),
            AdaptiveViewAreaHistory.Size(1284, 100), AdaptiveViewAreaHistory.Size(2000, 990))) {
            history = AdaptiveViewAreaHistory.remember(history, candidate, full)
        }
        assertEquals(listOf(split), history)
    }

    @Test fun newestSplitSizesStayWithinTheFourAreaProtocolLimit() {
        var history = emptyList<AdaptiveViewAreaHistory.Size>()
        for (width in listOf(600, 800, 1000, 1200)) {
            history = AdaptiveViewAreaHistory.remember(history, AdaptiveViewAreaHistory.Size(width, 990), full)
        }
        assertEquals(listOf(1200, 1000, 800), history.map { it.width })
        val current = AdaptiveViewAreaHistory.Size(1400, 990)
        val areas = AdaptiveViewAreaHistory.areas(full, full, current, history)
        assertEquals(4, areas.size)
        assertEquals(MainViewArea(1400, 990), areas[1])
        assertEquals(areas.size, areas.distinct().size)
    }
}
