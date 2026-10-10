package com.shilapi.xcertplay.airplay

internal data class CarPlayAppearance(
    val night: Boolean,
    val manual: Boolean,
    val mapFollowUi: Boolean = true,
)

/** Appearance updates use the same display UUIDs advertised in /info. */
internal object AppearanceCommands {
    fun build(appearance: CarPlayAppearance, hasCluster: Boolean): List<Map<String, Any?>> {
        val commands = mutableListOf<Map<String, Any?>>(
            linkedMapOf("type" to "setNightMode", "params" to linkedMapOf("nightMode" to appearance.night)),
        )
        val displays = if (hasCluster) {
            listOf(AirPlayInfoPlist.MAIN_UUID, AirPlayInfoPlist.ALT_UUID)
        } else {
            listOf(AirPlayInfoPlist.MAIN_UUID)
        }
        for (uuid in displays) {
            for (type in listOf("uiAppearanceUpdate", "mapAppearanceUpdate")) {
                commands += linkedMapOf(
                    "type" to type,
                    "params" to linkedMapOf(
                        "uuid" to uuid,
                        "appearanceMode" to if (appearance.night) 1 else 0,
                        // Automatic maps retain the current mode as a seed; the phone decides subsequent changes.
                        "appearanceSetting" to if (appearance.manual &&
                            (type != "mapAppearanceUpdate" || appearance.mapFollowUi)) 2 else 0,
                    ),
                )
            }
        }
        return commands
    }
}

/** Updates/flush use the event write lock; close clears without waiting for a blocked socket write. */
internal class PendingAppearance {
    @Volatile private var latest: CarPlayAppearance? = null

    fun update(appearance: CarPlayAppearance) {
        latest = appearance
    }

    fun clear() {
        latest = null
    }

    fun flush(hasCluster: Boolean, send: (Map<String, Any?>) -> Boolean): Boolean {
        val appearance = latest ?: return true
        for (command in AppearanceCommands.build(appearance, hasCluster)) {
            if (!send(command)) return false
        }
        latest = null
        return true
    }
}
