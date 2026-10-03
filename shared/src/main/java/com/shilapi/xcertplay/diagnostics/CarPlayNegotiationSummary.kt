package com.shilapi.xcertplay.diagnostics

import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayAvailability
import com.shilapi.xcertplay.iap2.message.Iap2CarPlayMessages
import com.shilapi.xcertplay.iap2.message.Iap2AvailabilityState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Redacted protocol summaries for checking extended CarPlay negotiation behavior. */
object CarPlayNegotiationSummary {
    private val knownCommands = setOf(
        "enhancedRequestCarUI", "changeModes", "modesChanged", "updateFeedback", "setModes",
        "forceKeyFrame", "hidSendReport", "iAPSendMessage", "requestCarUI", "requestSiri",
        "requestUI", "setNightMode", "setVideoPlaybackAllowed", "showUI", "stopUI",
    )
    private val knownCommandKeys = setOf(
        "appStates", "resources", "modes", "reason", "entityID", "resourceID", "appStateID",
        "transferType", "transferPriority", "takeConstraint", "borrowConstraint", "feedback",
        "data", "nightMode", "siriAction", "url", "uuid", "videoPlaybackAllowed",
    )

    fun info(info: Map<String, Any?>): String {
        val extended = info["extendedFeatures"] as? List<*>
        val enhanced = extended?.contains("enhancedRequestCarUI") == true
        val entries = (info["displays"] as? List<*>).orEmpty()
        var omitted = 0
        val summaries = entries.mapNotNull { value ->
            val display = value as? Map<*, *> ?: run { omitted++; return@mapNotNull null }
            val type = (display["type"] as? Number)?.toInt()
            if (type != 110 && type != 111) { omitted++; return@mapNotNull null }
            val width = safeDimension(display["widthPixels"])
            val height = safeDimension(display["heightPixels"])
            val url = when (display["initialURL"] as? String) {
                null -> "none"
                "maps:/car/instrumentcluster/map" -> "map"
                "maps:/car/instrumentcluster/instructioncard" -> "instructioncard"
                "maps:/car/instrumentcluster" -> "mapwithinstructions"
                else -> "other"
            }
            "type=$type size=${width}x$height url=$url"
        }
        return "extended.enhancedRequestCarUI=$enhanced displays=[${summaries.joinToString(";")}] omitted=$omitted"
    }

    fun command(type: String?, params: Map<*, *>?): String {
        val classified = type?.takeIf { it in knownCommands } ?: if (type == null) "missing" else "other"
        val keys = params?.keys.orEmpty()
        val known = keys.mapNotNull { it as? String }.filter { it in knownCommandKeys }.distinct().sorted()
        val unknown = keys.count { it !is String || it !in knownCommandKeys }
        return "type=$classified keys=[${known.joinToString(",")}] unknownKeys=$unknown"
    }

    /** Event-channel commands are observed only; malformed bodies never affect their response. */
    fun eventCommand(body: ByteArray): String = try {
        val decoded = BplistCodec.decode(body) as? Map<*, *>
        if (decoded == null) "decode=failed" else command(decoded["type"] as? String, decoded["params"] as? Map<*, *>)
    } catch (_: Exception) {
        "decode=failed"
    }

    fun availability(payload: ByteArray): String = try {
        availability(Iap2CarPlayMessages.availability(payload))
    } catch (_: Exception) {
        "decode=failed"
    }

    fun availability(value: Iap2CarPlayAvailability): String =
        "wired=${state(value.wired)} wireless=${state(value.wireless)} " +
            "themeAssetsGroup=${if (value.themeAssets == null) "absent" else "present"} " +
            "themeAssetsAvailable=${field(value.themeAssets)}"

    /** Reports presence only; asset ID and version values are deliberately withheld. */
    fun startSession(frame: Iap2Frame): String = try {
        val body = Iap2BodyReader.of(frame)
        val asset = body.optionalGroup(7)
        "clusterAsset=${if (asset == null) "absent" else "present"} " +
            "assetVersion=${if (asset?.has(1) == true) "present" else "absent"}"
    } catch (_: Exception) {
        "clusterAsset=unknown assetVersion=unknown"
    }

    private fun state(value: Iap2AvailabilityState?): String = when {
        value == null -> "absent"
        value.available == null -> "available=absent"
        else -> "available=${value.available} identifierPresent=${value.identifier != null}"
    }

    private fun field(value: Iap2AvailabilityState?): String = when {
        value == null -> "absent"
        value.available == null -> "absent"
        else -> value.available.toString()
    }

    private fun safeDimension(value: Any?): String = (value as? Number)?.toInt()
        ?.takeIf { it in 1..10_000 }?.toString() ?: "unknown"
}
