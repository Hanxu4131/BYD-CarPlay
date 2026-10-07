package com.shilapi.xcertplay.airplay

/** Match the view-area transition arguments used by upstream on the Tang. */
internal object MainViewAreaCommand {
    fun build(index: Int, areaCount: Int): Map<String, Any?> {
        require(areaCount > 0 && index in 0 until areaCount)
        return linkedMapOf(
            "type" to "updateViewArea",
            "params" to linkedMapOf(
                "uuid" to AirPlayInfoPlist.MAIN_UUID,
                "viewAreaIndex" to index,
                "animationDurationMillis" to 300,
                "adjacentViewAreas" to (0 until areaCount).filter { it != index },
            ),
        )
    }
}
