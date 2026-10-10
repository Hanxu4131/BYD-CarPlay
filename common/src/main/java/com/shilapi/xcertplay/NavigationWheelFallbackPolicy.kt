package com.shilapi.xcertplay

internal class NavigationWheelFallbackGate {
    private var identity: String? = null
    private var since: Long? = null
    fun clear() { identity = null; since = null }
    fun observe(now: Long, selected: String, binding: Boolean, healthy: Boolean): Boolean {
        if (healthy || !binding) { clear(); return false }
        if (identity != selected || since == null || now < since!!) {
            identity = selected
            since = now
        }
        return now - since!! >= 10_000L
    }
}

/** Replace exactly our two components, including short forms, while preserving all other entries. */
internal object NavigationWheelIdentityCommand {
    fun build(own: List<Pair<String, String>>, target: String): String? {
        val safe = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")
        if (own.size != 2 || own.any { !safe.matches(it.first) || !safe.matches(it.second) }
            || target !in own.map { it.first }) return null
        val cases = own.flatMap { listOf(it.first, it.second) }.distinct().joinToString("|") { "'$it'" }
        return """
            old=${'$'}(settings get secure enabled_accessibility_services) || exit 1
            case "${'$'}old" in null) old='';; *[!A-Za-z0-9_.:/]*) exit 1;; esac
            out=''; previousIFS=${'$'}IFS; IFS=:
            set -f
            for entry in ${'$'}old; do
              case "${'$'}entry" in $cases|'') continue;; esac
              if [ -n "${'$'}out" ]; then out="${'$'}out:${'$'}entry"; else out="${'$'}entry"; fi
            done
            IFS=${'$'}previousIFS
            if [ -n "${'$'}out" ]; then out="${'$'}out:$target"; else out='$target'; fi
            settings put secure enabled_accessibility_services "${'$'}out" || exit 1
            settings get secure enabled_accessibility_services
        """.trimIndent()
    }
    fun verified(output: String?, own: List<String>, target: String): Boolean {
        if (!NavigationWheelMutationReadback.verified(output, target, true)) return false
        val actual = output!!.trimEnd().lines().dropLast(1).last()
        return own.filter { NavigationWheelServiceList.contains(actual, it) } == listOf(target)
    }
}

/** Pure durable-budget decision; commit must succeed before the caller may mutate settings. */
internal object NavigationWheelFallbackBudget {
    fun claim(boot: Int, usedBoot: Int, selected: String, target: String, allowed: List<String>,
              commit: (Int, String) -> Boolean): Boolean {
        if (boot < 0 || usedBoot == boot || selected !in allowed || target !in allowed || selected == target) return false
        return commit(boot, target)
    }
    fun restore(stored: String?, registered: List<String>, primary: String, allowed: List<String>): String {
        val saved = stored?.takeIf { it in allowed }
        return saved ?: registered.filter { it in allowed }.singleOrNull() ?: primary
    }
}

internal object NavigationWheelOwnerPolicy {
    fun canActivate(selected: String, filteredOwn: List<String>): Boolean = filteredOwn.all { it == selected }
    fun allows(candidate: String, selected: String, switching: Boolean, enabled: Boolean): Boolean =
        candidate == selected && !switching && enabled
}
