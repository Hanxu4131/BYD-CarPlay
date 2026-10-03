package com.shilapi.xcertplay

/** A dialog lease prevents a late dismiss or reconnect from changing another editor's preview. */
internal class LegacyClusterPreviewState {
    enum class Kind { LAYOUT, KEY_AREA, TURN_AREA }
    private var generation = 0L
    private var kind: Kind? = null
    var layout: LegacyClusterLayout.Settings? = null
        private set
    var keyArea: LegacyClusterKeyArea.Settings? = null
        private set
    var turnArea: LegacyClusterTurnArea.Settings? = null
        private set

    fun beginLayout(value: LegacyClusterLayout.Settings): Long {
        clearAll()
        kind = Kind.LAYOUT
        layout = LegacyClusterLayout.sanitize(value)
        return generation
    }
    fun beginKeyArea(value: LegacyClusterKeyArea.Settings): Long {
        clearAll()
        kind = Kind.KEY_AREA
        keyArea = LegacyClusterKeyArea.sanitize(value)
        return generation
    }
    fun owns(id: Long, expected: Kind) = id == generation && kind == expected
    fun beginTurnArea(value: LegacyClusterTurnArea.Settings): Long {
        clearAll()
        kind = Kind.TURN_AREA
        turnArea = LegacyClusterTurnArea.sanitize(value)
        return generation
    }
    fun updateTurnArea(id: Long, value: LegacyClusterTurnArea.Settings): Boolean {
        if (!owns(id, Kind.TURN_AREA)) return false
        turnArea = LegacyClusterTurnArea.sanitize(value)
        return true
    }
    fun updateLayout(id: Long, value: LegacyClusterLayout.Settings): Boolean {
        if (!owns(id, Kind.LAYOUT)) return false
        layout = LegacyClusterLayout.sanitize(value)
        return true
    }
    fun updateKeyArea(id: Long, value: LegacyClusterKeyArea.Settings): Boolean {
        if (!owns(id, Kind.KEY_AREA)) return false
        keyArea = LegacyClusterKeyArea.sanitize(value)
        return true
    }
    fun end(id: Long): Boolean {
        if (id != generation || kind == null) return false
        clearAll()
        return true
    }
    fun clearAll() {
        generation++
        kind = null
        layout = null
        keyArea = null
        turnArea = null
    }
}
