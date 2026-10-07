package com.shilapi.xcertplay

/** Three attempts per unresolved fault; a minute of healthy binding starts a new fault budget. */
internal class NavigationWheelServiceRecoveryPolicy {
    private var failures = 0
    private var repairs = 0
    private var nextRepair = 0L
    private var healthySince: Long? = null
    fun wake() { failures = 0; healthySince = null }
    fun observe(now: Long, allowed: Boolean, enabled: Boolean, connected: Boolean, keyFilterRequested: Boolean? = null): Boolean {
        if (!allowed) { failures = 0; healthySince = null; return false }
        if (enabled && connected && keyFilterRequested != false) {
            failures = 0
            val since = healthySince
            if (since == null || now < since) healthySince = now
            else if (now - since >= 60_000L) repairs = 0
            // Keep the last attempt's cooldown even when the fault budget is replenished.
            return false
        }
        healthySince = null
        failures = (failures + 1).coerceAtMost(3)
        if (failures < 3 || repairs >= 3 || now < nextRepair) return false
        repairs++
        failures = 0
        nextRepair = now + 60_000L
        return true
    }
}

internal object NavigationWheelServiceList {
    fun contains(value: String?, own: String): Boolean = entries(value).any { same(it, own) }
    fun update(value: String?, own: String, enable: Boolean): String {
        val kept = entries(value).filterNot { same(it, own) }.toMutableList()
        if (enable) kept.add(own)
        return kept.joinToString(":")
    }
    private fun entries(value: String?): List<String> =
        value?.trim()?.takeUnless { it == "null" }?.split(':')?.filter { it.isNotEmpty() } ?: emptyList()
    private fun same(a: String, b: String): Boolean = expand(a) == expand(b)
    private fun expand(value: String): String {
        val slash = value.indexOf('/')
        return if (slash > 0 && value.getOrNull(slash + 1) == '.')
            value.substring(0, slash + 1) + value.substring(0, slash) + value.substring(slash + 1)
        else value
    }
}

/** Shell values never become shell code. Refuse failed/malformed reads before any write. */
internal object NavigationWheelServiceCommand {
    fun build(own: String, short: String, enable: Boolean, rebind: Boolean): String? {
        val component = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")
        if (!own.matches(component) || !short.matches(component)) return null
        fun mutate(add: Boolean): String = """
            old=${'$'}(settings get secure enabled_accessibility_services) || exit 1
            case "${'$'}old" in null) old='';; *[!A-Za-z0-9_.:/]*) exit 1;; esac
            out=''; previousIFS=${'$'}IFS; IFS=:
            set -f
            for entry in ${'$'}old; do
              case "${'$'}entry" in '$own'|'$short'|'') continue;; esac
              if [ -n "${'$'}out" ]; then out="${'$'}out:${'$'}entry"; else out="${'$'}entry"; fi
            done
            IFS=${'$'}previousIFS
            ${if (add) "if [ -n \"\$out\" ]; then out=\"\$out:$own\"; else out='$own'; fi" else ""}
            settings put secure enabled_accessibility_services "${'$'}out" || exit 1
        """.trimIndent()
        return (if (rebind) mutate(false) + "\nsleep 0.2\n" else "") + mutate(enable) +
            "\nsettings get secure enabled_accessibility_services"
    }
}
