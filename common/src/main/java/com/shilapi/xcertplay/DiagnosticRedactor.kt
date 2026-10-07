package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    private val secret = Regex("(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|body=|payload=|hex=)")
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val namedDevice = Regex("(?i)(phone|device|peer|host)?name[=:]")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    private val safeNumber = "-?[0-9]{1,20}(?:\\.[0-9]{1,17})?(?:[Ee][+-]?[0-9]{1,3})?"
    private val safeResponseTrace = Regex(
        "(?:[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}  )?TRACE (?:" +
            "airplay appearance response type=(?:setNightMode|uiAppearanceUpdate|mapAppearanceUpdate) " +
            "cseq=-?[0-9]{1,10} status=[0-9]{3}(?: body\\.status=$safeNumber)?(?: body\\.error=$safeNumber)?|" +
            "airplay event response unmatched count=[0-9]{1,10} missingCseq=[0-9]{1,10} " +
            "status=[0-9]{3} cseq=(?:-?[0-9]{1,10}|none)|" +
            "airplay appearance tx type=(?:setNightMode|uiAppearanceUpdate|mapAppearanceUpdate) " +
            "cseq=-?[0-9]{1,10} (?:night=(?:true|false|unknown)|mode=(?:-?[0-9]{1,10}|unknown) setting=(?:-?[0-9]{1,10}|unknown))|" +
            "airplay event read reads=[0-9]{1,10} count=[0-9]{1,10} decrypted=[0-9]{1,10} parsed=[0-9]{1,10} " +
            "encryptedRestBytes=[0-9]{1,10} plaintextRestBytes=[0-9]{1,10})",
    )
    fun redact(line: String): String? {
        if ((line.contains("TRACE ") && !safeResponseTrace.matches(line)) || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')) return null
        if (secret.containsMatchIn(line) || namedDevice.containsMatchIn(line)) return null
        return line.replace(mac, "[address]").replace(identifier, "[identifier]")
            .replace(address, "[ip]").replace(ipv6, "[ip]").take(700)
    }
}
