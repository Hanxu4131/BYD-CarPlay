package com.shilapi.xcertplay.airplay

/** Counts and durations only. Keep visibility after startup without logging event contents. */
internal class EventReadDiagnostics(private val nowNs: () -> Long = System::nanoTime) {
    private val openedNs = nowNs()
    private var reads = 0L
    private var receivedBytes = 0L
    private var messages = 0L
    private var lastReadNs: Long? = null
    private var lastReportNs = openedNs
    private var encryptedRest = 0
    private var plaintextRest = 0
    private var transmissions = 0L
    private var transmissionErrors = 0L
    private var lastTransmissionNs: Long? = null
    private var lastWriteMs = 0L
    private var maxWriteMs = 0L

    @Synchronized
    fun received(count: Int, decrypted: Int, parsed: Int, encryptedRestBytes: Int, plaintextRestBytes: Int): String? {
        val now = nowNs()
        reads++
        receivedBytes += count
        messages += parsed
        lastReadNs = now
        encryptedRest = encryptedRestBytes
        plaintextRest = plaintextRestBytes
        val startupReport = reads <= 64 && (reads and (reads - 1)) == 0L
        if (!startupReport && now - lastReportNs < REPORT_INTERVAL_NS) return null
        lastReportNs = now
        return "airplay event read reads=$reads count=$count decrypted=$decrypted parsed=$parsed " +
            "encryptedRestBytes=$encryptedRestBytes plaintextRestBytes=$plaintextRestBytes"
    }

    @Synchronized
    fun transmitted(durationNs: Long, successful: Boolean) {
        transmissions++
        if (!successful) transmissionErrors++
        lastTransmissionNs = nowNs()
        lastWriteMs = durationNs.coerceAtLeast(0L) / 1_000_000L
        maxWriteMs = maxOf(maxWriteMs, lastWriteMs)
    }

    @Synchronized
    fun summary(): String {
        val now = nowNs()
        fun age(last: Long?): Long = last?.let { ((now - it) / 1_000_000L).coerceAtLeast(0L) } ?: -1L
        return "airplay event summary ageMs=${age(openedNs)} reads=$reads receivedBytes=$receivedBytes " +
            "messages=$messages lastReadAgeMs=${age(lastReadNs)} encryptedRestBytes=$encryptedRest " +
            "plaintextRestBytes=$plaintextRest transmissions=$transmissions transmissionErrors=$transmissionErrors " +
            "lastTransmissionAgeMs=${age(lastTransmissionNs)} lastWriteMs=$lastWriteMs maxWriteMs=$maxWriteMs"
    }

    private companion object { const val REPORT_INTERVAL_NS = 60_000_000_000L }
}
