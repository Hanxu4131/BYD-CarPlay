package com.shilapi.xcertplay.airplay

/** Size/count diagnostics only; at most seven reports for one event connection. */
internal class EventReadDiagnostics {
    private var reads = 0

    fun received(count: Int, decrypted: Int, parsed: Int, encryptedRestBytes: Int, plaintextRestBytes: Int): String? {
        if (reads < Int.MAX_VALUE) reads++
        if (reads > 64 || (reads and (reads - 1)) != 0) return null
        return "airplay event read reads=$reads count=$count decrypted=$decrypted parsed=$parsed " +
            "encryptedRestBytes=$encryptedRestBytes plaintextRestBytes=$plaintextRestBytes"
    }
}
