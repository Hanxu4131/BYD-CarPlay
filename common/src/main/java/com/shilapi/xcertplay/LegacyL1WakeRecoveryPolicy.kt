package com.shilapi.xcertplay

/** Unknown diagnostics never authorize recovery. One recovery request is allowed per system wake. */
internal class LegacyL1WakeRecoveryPolicy {
    enum class Decision { WAIT, HEALTHY, SKIP, START_ONLY, RECOVER }
    data class Snapshot(val wake: Long?, val awake: Boolean, val dashboard: Boolean,
        val running: Boolean?, val listening: Boolean?, val bootService: Boolean? = null)
    private var wake: Long? = null
    private var stableSince: Long? = null
    private var missingSince: Long? = null
    private var missingDecision: Decision? = null
    private var attemptedWake: Long? = null

    fun cancel() { stableSince = null; missingSince = null; missingDecision = null }

    fun observe(now: Long, sample: Snapshot, postMapStartup: Boolean = false): Decision {
        if (sample.wake == null || !sample.awake) { cancel(); return Decision.WAIT }
        if (wake != sample.wake) { wake = sample.wake; cancel() }
        if (attemptedWake == wake) return Decision.SKIP
        val stable = stableSince ?: now.also { stableSince = it }
        if (now - stable < 5_000) return Decision.WAIT
        if (sample.listening == true) { missingSince = null; missingDecision = null; return Decision.HEALTHY }
        // The completed map startup gets one service wake-up, without stopping a live L1 process.
        if (postMapStartup && sample.listening == false && sample.running != null &&
            (sample.running == false || sample.bootService == false)) return Decision.START_ONLY
        val decision = when {
            sample.listening == null || sample.running == null -> null
            postMapStartup -> if (sample.bootService == true) Decision.START_ONLY else null
            sample.running == false -> Decision.START_ONLY
            sample.dashboard -> Decision.RECOVER
            else -> null // A live process without Dashboard may still be starting.
        }
        if (decision == null) {
            missingSince = null
            missingDecision = null
            return Decision.WAIT
        }
        // A process appearing/disappearing starts its own grace period.
        if (missingDecision != decision) { missingSince = null; missingDecision = decision }
        val missing = missingSince ?: now.also { missingSince = it }
        if (now - missing < 30_000) return Decision.WAIT
        return decision
    }

    fun claim(wake: Long): Boolean {
        if (this.wake != wake || attemptedWake == wake) return false
        attemptedWake = wake
        return true
    }

    companion object {
        /** A component-filtered dumpsys response: absence must be explicit, not an empty/error reply. */
        fun bootServicePresent(services: String?): Boolean? {
            val text = services?.trim() ?: return null
            if (!text.startsWith("ACTIVITY MANAGER SERVICES (dumpsys activity services)") ||
                Regex("(?i)permission denial|permission denied|exception|error:").containsMatchIn(text)) return null
            if (Regex("(?m)^\\s*\\*?\\s*ServiceRecord\\{[^}]*\\s+l1tech\\.com\\.l1mini/(?:\\.L1BootService|l1tech\\.com\\.l1mini\\.L1BootService)\\}")
                    .containsMatchIn(text)) return true
            return if (Regex("(?m)^\\s*\\(nothing\\)\\s*$").containsMatchIn(text) &&
                !text.contains("ServiceRecord{")) false else null
        }

        /** PackageManager supplies this name; reject any value that could alter the shell command. */
        fun safeProcessName(name: String?): String? = name?.takeIf {
            it.matches(Regex("[A-Za-z_][A-Za-z0-9_.]*(?::[A-Za-z0-9_][A-Za-z0-9_.]*)?"))
        }

        fun snapshot(power: String, tasks: String, pid: String?, tcp: String, tcp6: String): Snapshot {
            val wake = Regex("(?m)^\\s*mLastWakeTime=(\\d+)").find(power)?.groupValues?.get(1)?.toLongOrNull()
            val awake = Regex("(?m)^\\s*mWakefulness=Awake\\s*$").containsMatchIn(power) &&
                Regex("(?m)^\\s*mWakefulnessChanging=false\\s*$").containsMatchIn(power) &&
                Regex("(?m)^\\s*Display Power: state=ON\\s*$").containsMatchIn(power)
            val ipv4 = listening(tcp)
            val ipv6 = listening(tcp6)
            return Snapshot(wake, awake, LegacyL1MiniOrder.hasExistingDashboard(tasks),
                when {
                    pid == null -> null
                    pid.isBlank() -> false
                    pid.trim().matches(Regex("\\d+(\\s+\\d+)*")) -> true
                    else -> null
                },
                if (ipv4 == true || ipv6 == true) true else if (ipv4 == null || ipv6 == null) null else false)
        }

        /** /proc/net uses hexadecimal ports and state 0A for LISTEN (43760 = AAF0). */
        fun listening(table: String): Boolean? {
            val lines = table.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val header = lines.firstOrNull()?.split(Regex("\\s+")) ?: return null
            if (!header.containsAll(listOf("sl", "local_address", "st")) ||
                !(header.contains("rem_address") || header.contains("remote_address"))) return null
            val address = Regex("(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{32}):[0-9a-fA-F]{4}")
            var found = false
            for (line in lines.drop(1)) {
                val fields = line.split(Regex("\\s+"))
                // A permission/error line or truncated table is UNKNOWN, never proof of no listener.
                if (fields.size < 4 || !fields[0].matches(Regex("\\d+:")) ||
                    !address.matches(fields[1]) || !address.matches(fields[2]) ||
                    !fields[3].matches(Regex("[0-9a-fA-F]{2}"))) return null
                if (fields[1].substringAfterLast(':').equals("AAF0", true) &&
                    fields[3].equals("0A", true)) found = true
            }
            return found
        }
    }
}
