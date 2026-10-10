package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayInfoPlistTest {
    @Test
    fun transitionControlIsOfferedOnlyForMultipleMainViewAreas() {
        val main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720)
        val config = AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02", sourceVersion = "1.0", main = main)
        for (areas in listOf(emptyList(), listOf(MainViewArea(1280, 720)),
            listOf(MainViewArea(1280, 720), MainViewArea(640, 720)))) {
            val display = main.copy(adaptiveViewAreas = areas)
            val info = BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(
                config.copy(main = display, cluster = display)))) as Map<*, *>
            val displays = (info["displays"] as List<*>).map { it as Map<*, *> }
            if (areas.size > 1) assertEquals(true, displays[0]["viewAreaTransitionControl"])
            else assertFalse(displays[0].containsKey("viewAreaTransitionControl"))
            assertFalse(displays[1].containsKey("viewAreaTransitionControl"))
        }
    }


    @Test
    fun bothDisplaysDeclareAutomaticUiAndMapAppearanceOnTheWire() {
        val config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            cluster = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
        )
        val info = BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(config))) as Map<*, *>
        val displays = info["displays"] as List<*>
        assertEquals(2, displays.size)
        for (display in displays.map { it as Map<*, *> }) {
            for (key in listOf("uiAppearanceMode", "uiAppearanceSetting", "mapAppearanceMode", "mapAppearanceSetting")) {
                assertEquals(0, (display[key] as Number).toInt())
            }
        }
    }

    @Test
    fun initialCarAppearanceRetainsBooleanTypeAndOmitsUnknownState() {
        val config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )
        assertFalse(AirPlayInfoPlist.build(config).containsKey("nightMode"))
        for (night in listOf(false, true)) {
            val info = BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(config.copy(nightMode = night)))) as Map<*, *>
            assertEquals(night, info["nightMode"])
        }
    }

    @Test
    fun defaultDisplayIncludesFullViewAndSafeAreas() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(0, display["initialViewArea"])
        assertEquals(1280, view["widthPixels"])
        assertEquals(720, view["heightPixels"])
        assertEquals(0, view["originXPixels"])
        assertEquals(0, view["originYPixels"])
        assertEquals(1, display["primaryInputDevice"])
        assertNotNull(safe)
        assertEquals(1280, safe["widthPixels"])
        assertEquals(720, safe["heightPixels"])
        assertEquals(true, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun hevcCapabilityIsAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        assertFalse(AirPlayInfoPlist.build(base).containsKey("hevcInfo"))
        assertTrue(AirPlayInfoPlist.build(base.copy(hevc = true)).containsKey("hevcInfo"))
    }

    @Test
    fun drivingSideAndDisplayValuesAreAdvertised() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 1280,
                    heightPixels = 720,
                    fps = 37,
                    widthPhysicalMm = 125,
                ),
                rightHandDrive = true,
                manufacturer = "Example",
                model = "HeadUnit",
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["rightHandDrive"])
        assertEquals("Example", info["manufacturer"])
        assertEquals("HeadUnit", info["model"])
        assertEquals(35, display["maxFPS"])
        assertEquals(125, display["widthPhysical"])
        assertEquals(70, display["heightPhysical"])
    }

    @Test
    fun squareOemIconIsAdvertisedWithItsOriginalBytes() {
        val iconBytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
                icons = listOf(AirPlayIcon(1, 1, iconBytes)),
                oemLabel = "xcertplay",
            ),
        )

        val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertEquals(true, info["oemIconVisible"])
        assertEquals("xcertplay", info["oemIconLabel"])
        assertEquals(1, icon["widthPixels"])
        assertEquals(1, icon["heightPixels"])
        assertTrue((icon["imageData"] as ByteArray).contentEquals(iconBytes))
    }

    @Test
    fun safeAreaInsetsReachTheAirPlayViewArea() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(
                    widthPixels = 960,
                    heightPixels = 540,
                    safeArea = AirPlayInsets(top = 10, bottom = 15, left = 20, right = 25),
                    safeAreaDrawOutside = false,
                ),
            ),
        )

        val display = (info["displays"] as List<*>).single() as Map<*, *>
        val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
        val safe = view["safeArea"] as Map<*, *>
        assertEquals(915, safe["widthPixels"])
        assertEquals(515, safe["heightPixels"])
        assertEquals(20, safe["originXPixels"])
        assertEquals(10, safe["originYPixels"])
        assertEquals(false, safe["drawUIOutsideSafeArea"])
    }

    @Test
    fun reportedSafeAreaUsesActivityMappingAndEvenAlignment() {
        val fullHdInsets = AirPlaySafeArea.toInsets(
            mapping = SafeAreaRect(left = 0, top = 0, right = 1920, bottom = 975),
            activityWidthPixels = 1920,
            activityHeightPixels = 1080,
            displayWidthPixels = 1920,
            displayHeightPixels = 1080,
        )
        val compactInsets = AirPlaySafeArea.toInsets(
            mapping = SafeAreaRect(left = 34, top = 75, right = 734, bottom = 725),
            activityWidthPixels = 768,
            activityHeightPixels = 800,
            displayWidthPixels = 768,
            displayHeightPixels = 800,
        )

        fun safeArea(
            widthPixels: Int,
            heightPixels: Int,
            insets: AirPlayInsets,
        ): Map<*, *> {
            val info = AirPlayInfoPlist.build(
                AirPlayConfig(
                    deviceName = "test",
                    deviceId = "02:00:00:00:00:02",
                    btMac = "02:00:00:00:00:02",
                    sourceVersion = "366.0",
                    main = AirPlayDisplayConfig(
                        widthPixels = widthPixels,
                        heightPixels = heightPixels,
                        safeArea = insets,
                    ),
                ),
            )
            val display = (info["displays"] as List<*>).single() as Map<*, *>
            val view = (display["viewAreas"] as List<*>).single() as Map<*, *>
            return view["safeArea"] as Map<*, *>
        }

        val fullHd = safeArea(1920, 1080, fullHdInsets)
        val compact = safeArea(768, 800, compactInsets)

        assertEquals(1920, fullHd["widthPixels"])
        assertEquals(976, fullHd["heightPixels"])
        assertEquals(700, compact["widthPixels"])
        assertEquals(650, compact["heightPixels"])
        assertEquals(34, compact["originXPixels"])
        assertEquals(75, compact["originYPixels"])
    }

    @Test
    fun microphoneInputsAreAdvertisedOnlyWhenEnabled() {
        val base = AirPlayConfig(
            deviceName = "test",
            deviceId = "02:00:00:00:00:02",
            btMac = "02:00:00:00:00:02",
            sourceVersion = "366.0",
            main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
        )

        fun telephony(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["audioType"] == "telephony" }

        fun defaultAudio(info: Map<String, Any?>): Map<*, *> =
            (info["audioFormats"] as List<*>)
                .map { it as Map<*, *> }
                .single { it["type"] == 100 && it["audioType"] == "default" }

        assertFalse(telephony(AirPlayInfoPlist.build(base)).containsKey("audioInputFormats"))
        assertTrue(
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))
                .containsKey("audioInputFormats"),
        )
        assertEquals(
            0x70004154,
            defaultAudio(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioInputFormats"],
        )
        assertEquals(
            0x70004154,
            telephony(AirPlayInfoPlist.build(base.copy(microphone = true)))["audioInputFormats"],
        )
    }

    @Test
    fun mainAltAndHighAudioStreamsAreDeclared() {
        val info = AirPlayInfoPlist.build(
            AirPlayConfig(
                deviceName = "test",
                deviceId = "02:00:00:00:00:02",
                btMac = "02:00:00:00:00:02",
                sourceVersion = "366.0",
                main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 720),
            ),
        )

        val types = (info["audioFormats"] as List<*>)
            .map { (it as Map<*, *>)["type"] }
            .toSet()
        assertEquals(setOf(100, 101, 102), types)
    }
}
