package com.shilapi.xcertplay.diagnostics

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.iap2.message.Iap2AvailabilityState
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayAvailability
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2ClusterAsset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayNegotiationSummaryTest {
    @Test fun infoKeepsOnlyWhitelistedDisplayFacts() {
        val summary = CarPlayNegotiationSummary.info(mapOf(
            "extendedFeatures" to listOf("enhancedRequestCarUI"),
            "displays" to listOf(mapOf(
                "uuid" to "private-display-uuid",
                "type" to 111,
                "widthPixels" to 1920,
                "heightPixels" to 720,
                "initialURL" to "private://custom-map-url",
            )),
        ))

        assertTrue(summary.contains("enhancedRequestCarUI=true"))
        assertTrue(summary.contains("type=111 size=1920x720 url=other"))
        assertFalse(summary.contains("private-display-uuid"))
        assertFalse(summary.contains("private://custom-map-url"))
    }

    @Test fun unknownCommandAndParameterNamesAreNeverPrinted() {
        val summary = CarPlayNegotiationSummary.command(
            "private-command-type",
            mapOf("private-token-name" to "secret-value", "url" to "private://url"),
        )

        assertTrue(summary.contains("type=other"))
        assertTrue(summary.contains("keys=[url] unknownKeys=1"))
        assertFalse(summary.contains("private-command-type"))
        assertFalse(summary.contains("private-token-name"))
        assertFalse(summary.contains("secret-value"))
        assertFalse(summary.contains("private://url"))
        assertTrue(CarPlayNegotiationSummary.command("requestCarUI", emptyMap<String, Any?>()).contains("type=requestCarUI"))
        assertTrue(CarPlayNegotiationSummary.command("enhancedRequestCarUI", emptyMap<String, Any?>()).contains("type=enhancedRequestCarUI"))
    }

    @Test fun availabilityAndClusterAssetSummariesExposePresenceButNotIdentifiers() {
        val availability = CarPlayNegotiationSummary.availability(Iap2CarPlayAvailability(
            wired = Iap2AvailabilityState(true, "private-usb-identifier"),
            wireless = Iap2AvailabilityState(false, "private-bt-identifier"),
            themeAssets = Iap2AvailabilityState(true, null),
        ))
        val start = Iap2CarPlayMessages.startSession(
            airPlayPort = 7000,
            publicKey = "private-public-key",
            sourceVersion = "test",
            clusterAsset = Iap2ClusterAsset("private-asset-id", 456),
        )
        val assetSummary = CarPlayNegotiationSummary.startSession(start)
        val noAsset = Iap2CarPlayMessages.startSession(
            airPlayPort = 7000,
            publicKey = "private-public-key",
            sourceVersion = "test",
        )

        assertTrue(availability.contains("themeAssetsAvailable=true"))
        assertTrue(assetSummary.contains("clusterAsset=present assetVersion=present"))
        assertTrue(CarPlayNegotiationSummary.startSession(noAsset).contains("clusterAsset=absent assetVersion=absent"))
        listOf("private-usb-identifier", "private-bt-identifier", "private-asset-id", "private-public-key", "456")
            .forEach { assertFalse(availability.contains(it) || assetSummary.contains(it)) }
    }

    @Test fun resourceCommandsPrintOnlyWhitelistedNames() {
        listOf("changeModes", "modesChanged", "updateFeedback", "setModes").forEach { command ->
            val summary = CarPlayNegotiationSummary.command(command, mapOf(
                "appStates" to listOf("private-app"), "resources" to listOf("private-resource"),
                "modes" to "private-mode", "reason" to "private-reason", "entityID" to "private-id",
                "private-key" to "private-value",
            ))
            assertTrue(summary.contains("type=$command"))
            assertTrue(summary.contains("keys=[appStates,entityID,modes,reason,resources] unknownKeys=1"))
            assertFalse(summary.contains("private"))
        }
    }

    @Test fun eventBplistSummaryIsSafeAndDecodeFailureIsNonFatal() {
        val summary = CarPlayNegotiationSummary.eventCommand(BplistCodec.encode(mapOf(
            "type" to "changeModes", "params" to mapOf("resources" to listOf("private-resource")),
        )))
        assertTrue(summary.contains("type=changeModes keys=[resources] unknownKeys=0"))
        assertFalse(summary.contains("private-resource"))
        assertTrue(CarPlayNegotiationSummary.eventCommand(byteArrayOf(1, 2, 3)) == "decode=failed")
        assertTrue(CarPlayNegotiationSummary.eventCommand(BplistCodec.encode(listOf("private"))) == "decode=failed")
    }

    @Test fun malformedAvailabilityDoesNotInterruptDiagnosticCaller() {
        assertTrue(CarPlayNegotiationSummary.availability(byteArrayOf(1, 2, 3)).contains("decode=failed"))
    }
}
