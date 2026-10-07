package com.shilapi.xcertplay.airplay

/** Correlates only appearance replies; never includes response bodies or display identifiers. */
internal class AppearanceResponseDiagnostics {
    private val pending = LinkedHashMap<Int, String>()
    private var unmatchedCount = 0
    private var missingCseqCount = 0

    @Synchronized fun record(cseq: Int, type: String) {
        if (type !in TYPES) return
        pending[cseq] = type
        while (pending.size > MAX_PENDING) pending.remove(pending.keys.first())
    }

    fun transmission(cseq: Int, command: Map<String, Any?>): String? {
        val type = command["type"] as? String ?: return null
        if (type !in TYPES) return null
        record(cseq, type)
        val params = command["params"] as? Map<*, *>
        val fields = if (type == "setNightMode") {
            "night=${params?.get("nightMode") as? Boolean ?: "unknown"}"
        } else {
            val mode = (params?.get("appearanceMode") as? Number)?.toInt()
            val setting = (params?.get("appearanceSetting") as? Number)?.toInt()
            "mode=${mode ?: "unknown"} setting=${setting ?: "unknown"}"
        }
        return "airplay appearance tx type=$type cseq=$cseq $fields"
    }

    @Synchronized fun forget(cseq: Int) { pending.remove(cseq) }
    @Synchronized fun clear() {
        pending.clear()
        unmatchedCount = 0
        missingCseqCount = 0
    }

    /** Global event replies, not attributed to appearance or HID; at most seven summaries/session. */
    @Synchronized fun unmatchedResponse(message: RtspMessage.Request): String? {
        if (!message.method.startsWith("RTSP/") && !message.method.startsWith("HTTP/")) return null
        val status = message.path.toIntOrNull() ?: return null
        val cseq = message.headers["cseq"]?.toIntOrNull()
        if (unmatchedCount < Int.MAX_VALUE) unmatchedCount++
        if (cseq == null && missingCseqCount < Int.MAX_VALUE) missingCseqCount++
        if (unmatchedCount > 64 || (unmatchedCount and (unmatchedCount - 1)) != 0) return null
        return "airplay event response unmatched count=$unmatchedCount missingCseq=$missingCseqCount status=$status cseq=${cseq ?: "none"}"
    }

    fun response(message: RtspMessage.Request): String? {
        if (!message.method.startsWith("RTSP/") && !message.method.startsWith("HTTP/")) return null
        val status = message.path.toIntOrNull() ?: return null
        val cseq = message.headers["cseq"]?.toIntOrNull() ?: return null
        val type = synchronized(this) { pending.remove(cseq) } ?: return null
        val fields = if (message.body.isNotEmpty() && message.body.size <= MAX_BODY_BYTES) {
            runCatching { BplistCodec.decode(message.body) as? Map<*, *> }.getOrNull()
        } else null
        return buildString {
            append("airplay appearance response type=").append(type)
            append(" cseq=").append(cseq).append(" status=").append(status)
            for (key in listOf("status", "error")) {
                numeric(fields?.get(key))?.let { append(" body.").append(key).append('=').append(it) }
            }
        }
    }

    private fun numeric(value: Any?): String? = when (value) {
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> value.takeIf { it.isFinite() }?.toString()
        is Double -> value.takeIf { it.isFinite() }?.toString()
        else -> null
    }

    private companion object {
        const val MAX_PENDING = 32
        const val MAX_BODY_BYTES = 16 * 1024
        val TYPES = setOf("setNightMode", "uiAppearanceUpdate", "mapAppearanceUpdate")
    }
}
