package com.shilapi.xcertplay

/** Unknown diagnostics never authorize a restart. One restart is allowed per system wake. */
internal class LegacyL1WakeRecoveryPolicy {
    enum class Decision { WAIT, HEALTHY, SKIP, RECOVER }
    data class Snapshot(val wake: Long?, val awake: Boolean, val dashboard: Boolean,
        val running: Boolean, val listening: Boolean?)
    private var wake: Long? = null
    private var stableSince: Long? = null
    private var missingSince: Long? = null
    private var attemptedWake: Long? = null

    fun cancel() { stableSince = null; missingSince = null }

    fun observe(now: Long, sample: Snapshot): Decision {
        if (sample.wake == null || !sample.awake) { cancel(); return Decision.WAIT }
        if (wake != sample.wake) { wake = sample.wake; cancel() }
        if (attemptedWake == wake) return Decision.SKIP
        val stable = stableSince ?: now.also { stableSince = it }
        if (now - stable < 5_000) return Decision.WAIT
        if (sample.listening == true) { missingSince = null; return Decision.HEALTHY }
        if (!sample.dashboard || !sample.running || sample.listening == null) {
            missingSince = null
            return Decision.WAIT
        }
        val missing = missingSince ?: now.also { missingSince = it }
        if (now - missing < 30_000) return Decision.WAIT
        return Decision.RECOVER
    }

    fun claim(wake: Long): Boolean {
        if (this.wake != wake || attemptedWake == wake) return false
        attemptedWake = wake
        return true
    }

    companion object {
        fun snapshot(power: String, tasks: String, pid: String, tcp: String, tcp6: String): Snapshot {
            val wake = Regex("(?m)^\\s*mLastWakeTime=(\\d+)").find(power)?.groupValues?.get(1)?.toLongOrNull()
            val awake = Regex("(?m)^\\s*mWakefulness=Awake\\s*$").containsMatchIn(power) &&
                Regex("(?m)^\\s*mWakefulnessChanging=false\\s*$").containsMatchIn(power) &&
                Regex("(?m)^\\s*Display Power: state=ON\\s*$").containsMatchIn(power)
            val ipv4 = listening(tcp)
            val ipv6 = listening(tcp6)
            return Snapshot(wake, awake, LegacyL1MiniOrder.hasExistingDashboard(tasks),
                pid.trim().matches(Regex("\\d+(\\s+\\d+)*")),
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
