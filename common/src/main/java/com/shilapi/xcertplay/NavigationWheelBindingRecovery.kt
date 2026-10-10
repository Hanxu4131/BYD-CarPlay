package com.shilapi.xcertplay

/** A start request is not a connection. Retry it only after this fault has ended. */
internal class NavigationWheelOwnStart {
    private var attempted = false
    fun observe(requested: Boolean, connected: Boolean) {
        if (!requested || connected) attempted = false
    }
    fun claim(): Boolean {
        if (attempted) return false
        attempted = true
        return true
    }
}

internal object NavigationWheelMutationReadback {
    const val MARKER = "DIPLAY_WHEEL_MUTATION_OK"
    fun checked(script: String) = "printf 'DIPLAY_WHEEL_MUTATION_BEGIN\\n'\n" + script +
        "\n[ ${'$'}? -eq 0 ] || exit 1\nprintf '$MARKER\\n'"
    fun verified(output: String?, own: String, add: Boolean): Boolean {
        val lines = output?.trimEnd()?.lines() ?: return false
        if (lines.size < 2 || lines.last() != MARKER) return false
        return NavigationWheelServiceList.contains(lines[lines.lastIndex - 1], own) == add
    }
}

/** Android 9 may print labels only for Bound services. Such a dump cannot prove absence. */
internal object NavigationWheelBindingDump {
    data class Snapshot(val verifiable: Boolean, val ownPresent: Boolean)
    private val component = Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+")
    fun parse(dump: String?, own: String, boundComponents: List<String>? = null): Snapshot {
        if (dump == null || !component.matches(own)) return Snapshot(false, false)
        val users = dump.split(Regex("(?m)^\\s*User state\\["))
            .drop(1).filter { Regex("\\bcurrentUser=true\\b").containsMatchIn(it.substringBefore('\n')) }
        if (users.size != 1) return Snapshot(false, false)
        val user = users.single()
        val bound = if (boundComponents != null) {
            if (boundComponents.any { !component.matches(it) }) return Snapshot(false, false)
            boundComponents.joinToString(",")
        } else collection(user, "Bound services:") ?: return Snapshot(false, false)
        val binding = collection(user, "Binding services:") ?: return Snapshot(false, false)
        val entries = listOf(bound, binding)
        val present = entries.any { text -> component.findAll(text).any {
            NavigationWheelServiceList.contains(it.value, own)
        } }
        // Accept component collections only, never infer identity from a display label.
        val known = entries.all { text -> component.replace(text, "").all {
            it.isWhitespace() || it in "{},"
        } }
        return Snapshot(known, present)
    }
    private fun collection(text: String, label: String): String? {
        val labelAt = text.indexOf(label)
        if (labelAt < 0 || text.indexOf(label, labelAt + label.length) >= 0) return null
        val start = text.indexOfFirstFrom(labelAt + label.length) { !it.isWhitespace() }
        if (start < 0 || text[start] != '{') return null
        var depth = 0
        for (index in start until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(start + 1, index)
            }
        }
        return null
    }
    private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (index in start until length) if (predicate(this[index])) return index
        return -1
    }
}

/** Adapter calls are individually bounded; no app restart or other-service mutation. */
internal class NavigationWheelBindingRecovery(
    private val own: String,
    private val requested: () -> Boolean,
    private val snapshot: () -> NavigationWheelBindingDump.Snapshot,
    private val mutateOwn: (Boolean) -> Boolean,
    private val now: () -> Long,
    private val pause: (Long) -> Unit,
    private val phase: (String) -> Unit,
) {
    enum class Result { DISABLED, REGISTERED, ADB_UNAVAILABLE, NO_SYSTEM_PROOF, DETACH_TIMEOUT, RESTORE_FAILED, WAIT_BINDING, SWITCH_SUBMITTED, SWITCH_BUDGET_USED, TARGET_BUSY, COMMIT_FAILED, FILTER_PREP_FAILED }
    fun run(registered: Boolean): Result {
        if (!requested()) return Result.DISABLED
        if (!registered) {
            phase("register-missing")
            return if (mutateOwn(true)) Result.REGISTERED else Result.RESTORE_FAILED
        }
        phase("inspect-system-binding")
        if (!snapshot().verifiable) return Result.NO_SYSTEM_PROOF
        if (!requested()) return Result.DISABLED
        var result = Result.DETACH_TIMEOUT
        var restored = false
        try {
            // Even a failed remove can have reached settings. Always restore in finally.
            phase("remove-own-entry")
            if (mutateOwn(false)) {
                phase("await-system-detach")
                val deadline = now() + 2_500L
                for (attempt in 0 until 6) {
                    if (!requested()) { result = Result.DISABLED; break }
                    if (now() >= deadline) break
                    val state = snapshot()
                    if (!state.verifiable) { result = Result.NO_SYSTEM_PROOF; break }
                    if (!state.ownPresent) { result = Result.REGISTERED; break }
                    if (attempt < 5 && now() < deadline) pause(250)
                }
            }
        } finally {
            // Restore the preexisting registration even if the routing switch was turned off.
            phase("restore-own-entry")
            restored = mutateOwn(true)
        }
        return if (restored) result else Result.RESTORE_FAILED
    }
}
