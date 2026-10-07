package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class BydLegacyBatteryProfileTest {
    private val symbols = mapOf(
        "BYDAutoConstants.BYDAUTO_DEVICE_STATISTIC" to 1014,
        "BYDAutoConstants.BYDAUTO_DEVICE_CHARGING" to 1009,
        "BYDAutoConstants.BYDAUTO_DEVICE_POWER" to 1005,
        "BYDAutoFeatureIds\$Statistic.STATISTIC_ELEC_PERCENTAGE" to 1246777400,
        "BYDAutoFeatureIds\$Statistic.STATISTIC_ELEC_DRIVING_RANGE" to 1246765118,
        "BYDAutoFeatureIds\$Charging.CHARGING_BATTERRY_DEVICE_STATE" to 876609560,
        "BYDAutoFeatureIds\$Power.POWER_BATTERY_REMAIN_ELECTRICITY" to 882901008,
    )
    private fun resolve(values: Map<String, Int>) = BydLegacyBatteryProfile.resolve { type, name -> values["$type.$name"] }
    private val replies = mapOf(
        "getprop ro.car.protocol" to "",
        "service call autoservice 7 i32 1014 i32 1246777400" to "Result: Parcel(00000000 42480000 '....')",
        "service call autoservice 5 i32 1014 i32 1246765118" to "Result: Parcel(00000000 0000003c '....')",
        "service call autoservice 5 i32 1009 i32 876609560" to "Result: Parcel(00000000 00000001 '....')",
        "service call autoservice 7 i32 1005 i32 882901008" to "Result: Parcel(00000000 41400000 '....')",
    )

    @Test fun firmwareSymbolsEnableReadOnlyBatteryCommandsWithoutProtocolProperty() {
        val profile = resolve(symbols)!!
        val commands = mutableListOf<String>()
        val reading = BydBattery.read({ commands.add(it); replies[it] }, profile)!!
        assertEquals(50.0, reading.percent, 0.001)
        assertEquals(60, reading.rangeKm)
        assertEquals(12.0, reading.remainingKwh!!, 0.001)
        assertTrue(reading.charging)
        assertNull(reading.protocol)
        assertEquals(replies.keys.toList(), commands)
    }

    @Test fun incompleteOrInvalidCatalogNeverGuessesAddresses() {
        for (key in symbols.keys.filter { !it.contains("POWER") && !it.contains("Power.") }) {
            assertNull(resolve(symbols - key))
        }
        assertNull(resolve(symbols + ("BYDAutoConstants.BYDAUTO_DEVICE_STATISTIC" to -1)))
        assertNull(resolve(symbols + ("BYDAutoFeatureIds\$Statistic.STATISTIC_ELEC_PERCENTAGE" to 0)))
    }

    @Test fun unavailableEnergyDoesNotInventCapacityOrDiscardValidBattery() {
        val profile = resolve(symbols)!!
        val reading = BydBattery.read({ replies[it].takeUnless { it?.contains("41400000") == true } }, profile)!!
        assertNull(reading.remainingKwh)
        val wire = BydBattery.snapshot(reading, 20, null)
        assertNull(wire.currentChargeWh)
        assertNull(wire.maxChargeWh)
        assertEquals(50.0, wire.batteryPercent, 0.001)
    }

    @Test fun failedSampleOrUnknownNonemptyProtocolCannotPublishGuessedData() {
        val profile = resolve(symbols)!!
        for (missing in replies.keys.drop(1).take(3)) {
            assertNull(BydBattery.read({ (replies - missing)[it] }, profile))
        }
        for (protocol in listOf(null, "SOMEIP", "error: closed")) {
            val commands = mutableListOf<String>()
            assertNull(BydBattery.read({ commands.add(it); if (it == "getprop ro.car.protocol") protocol else replies[it] }, profile))
            assertEquals(listOf("getprop ro.car.protocol"), commands)
        }
    }

    @Test fun zeroEnergyOnANonemptyBatteryIsOmittedWithoutSuppressingSocAndRange() {
        val profile = resolve(symbols)!!
        val zeroEnergy = replies + (profile.remainingCommand()!! to "Result: Parcel(00000000 00000000 '....')")
        val reading = BydBattery.read(zeroEnergy::get, profile)!!
        assertEquals(50.0, reading.percent, 0.001)
        assertEquals(60, reading.rangeKm)
        assertNull(reading.remainingKwh)
        assertNull(BydBattery.snapshot(reading, 20, null).maxChargeWh)
    }

    @Test fun correctedChargingAliasAndOptionalEnergyFieldAreSupported() {
        val original = "BYDAutoFeatureIds\$Charging.CHARGING_BATTERRY_DEVICE_STATE"
        val alias = "BYDAutoFeatureIds\$Charging.CHARGING_BATTERY_DEVICE_STATE"
        val profile = resolve((symbols - original - "BYDAutoFeatureIds\$Power.POWER_BATTERY_REMAIN_ELECTRICITY") + (alias to symbols.getValue(original)))!!
        assertNull(profile.remainingCommand())
        assertTrue(BydBattery.read(replies::get, profile)!!.charging)
    }
}
