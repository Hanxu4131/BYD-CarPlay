package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class MainViewAreaCommandTest {
    @Test fun fullToSplitCarriesTheMainUuidAnimationAndOtherArea() {
        val command = MainViewAreaCommand.build(index = 1, areaCount = 2)
        assertEquals("updateViewArea", command["type"])
        assertEquals(mapOf(
            "uuid" to AirPlayInfoPlist.MAIN_UUID,
            "viewAreaIndex" to 1,
            "animationDurationMillis" to 300,
            "adjacentViewAreas" to listOf(0),
        ), command["params"])
    }

    @Test fun everyTargetIncludesAllAndOnlyTheOtherDeclaredAreas() {
        for (count in 1..4) for (index in 0 until count) {
            val params = MainViewAreaCommand.build(index, count)["params"] as Map<*, *>
            val adjacent = params["adjacentViewAreas"] as List<*>
            assertEquals(index, params["viewAreaIndex"])
            assertEquals(count - 1, adjacent.size)
            assertFalse(adjacent.contains(index))
            assertEquals((0 until count).toSet() - index, adjacent.toSet())
        }
    }

    @Test fun splitToFullUsesTheSplitAreaAsAdjacent() {
        val params = MainViewAreaCommand.build(index = 0, areaCount = 2)["params"] as Map<*, *>
        assertEquals(listOf(1), params["adjacentViewAreas"])
        assertEquals(300, params["animationDurationMillis"])
    }

    @Test fun invalidTargetsCannotBuildAnUndeclaredAreaCommand() {
        for ((index, count) in listOf(0 to 0, -1 to 2, 2 to 2)) {
            try {
                MainViewAreaCommand.build(index, count)
                fail("Undeclared target must fail")
            } catch (_: IllegalArgumentException) {
                // The session rejects these before calling the builder.
            }
        }
    }
}
