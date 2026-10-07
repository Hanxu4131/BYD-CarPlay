package com.shilapi.xcertplay.hud

import java.lang.reflect.Modifier

/** Read addresses from the installed firmware, without relying on ro.car.protocol. */
internal data class BydLegacyBatteryProfile(
    val statisticDevice: Int,
    val percentId: Int,
    val rangeId: Int,
    val chargingDevice: Int,
    val chargingId: Int,
    val powerDevice: Int?,
    val remainingId: Int?,
) {
    fun percentCommand() = "service call autoservice 7 i32 $statisticDevice i32 $percentId"
    fun rangeCommand() = "service call autoservice 5 i32 $statisticDevice i32 $rangeId"
    fun chargingCommand() = "service call autoservice 5 i32 $chargingDevice i32 $chargingId"
    fun remainingCommand(): String? = if (powerDevice != null && remainingId != null)
        "service call autoservice 7 i32 $powerDevice i32 $remainingId" else null

    companion object {
        private const val ROOT = "android.hardware.bydauto."

        fun load(loader: ClassLoader): BydLegacyBatteryProfile? = resolve { type, name ->
            runCatching {
                val field = Class.forName(ROOT + type, false, loader).getField(name)
                if (!Modifier.isStatic(field.modifiers)) return@runCatching null
                if (field.type != Int::class.javaPrimitiveType && field.type != Long::class.javaPrimitiveType)
                    return@runCatching null
                val value = field.get(null) as? Number ?: return@runCatching null
                value.toLong().takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            }.getOrNull()
        }

        internal fun resolve(symbol: (String, String) -> Int?): BydLegacyBatteryProfile? {
            fun device(name: String) = symbol("BYDAutoConstants", "BYDAUTO_DEVICE_$name")
                ?.takeIf { it in 1..65535 }
            fun feature(type: String, name: String) = symbol("BYDAutoFeatureIds\$$type", name)
                ?.takeIf { it != 0 && it != -1 }
            return BydLegacyBatteryProfile(
                statisticDevice = device("STATISTIC") ?: return null,
                percentId = feature("Statistic", "STATISTIC_ELEC_PERCENTAGE") ?: return null,
                rangeId = feature("Statistic", "STATISTIC_ELEC_DRIVING_RANGE") ?: return null,
                chargingDevice = device("CHARGING") ?: return null,
                chargingId = feature("Charging", "CHARGING_BATTERRY_DEVICE_STATE")
                    ?: feature("Charging", "CHARGING_BATTERY_DEVICE_STATE") ?: return null,
                powerDevice = device("POWER"),
                remainingId = feature("Power", "POWER_BATTERY_REMAIN_ELECTRICITY"),
            )
        }
    }
}
