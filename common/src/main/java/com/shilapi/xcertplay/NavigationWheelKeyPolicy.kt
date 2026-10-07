package com.shilapi.xcertplay

/** Keep a consumed press paired even if guidance ends between DOWN and UP. */
internal class NavigationWheelKeyPolicy {
    private data class Press(val device: Int, val key: Int, val downTime: Long)
    private val consumed = mutableSetOf<Press>()

    fun onKey(action: Int, key: Int, device: Int, downTime: Long, repeat: Int,
        eligible: Boolean, adjust: (Int) -> Boolean): Boolean {
        val delta = when (key) {
            24, 291, 307 -> 1
            25, 292, 308 -> -1
            else -> return false
        }
        val press = Press(device, key, downTime)
        if (action == 1) return consumed.remove(press)
        if (action != 0) return false
        if (press in consumed) {
            if (eligible) adjust(delta)
            return true
        }
        // Never take over a press whose initial DOWN went to the system.
        if (repeat != 0 || !eligible || !adjust(delta)) return false
        consumed.add(press)
        return true
    }
}

/** The session owner supplies eligibility across UI pauses; each key checks live audio state. */
internal object NavigationWheelRoutingState {
    private var owner: Any? = null
    private var eligible: (() -> Boolean)? = null
    fun attach(owner: Any, eligible: () -> Boolean) {
        this.owner = owner
        this.eligible = eligible
    }
    fun detach(owner: Any) {
        if (this.owner === owner) { this.owner = null; eligible = null }
    }
    fun hostDestroyed(owner: Any, retainOwnedSession: Boolean) {
        if (!retainOwnedSession) detach(owner)
    }
    fun canRouteNow(): Boolean = runCatching { eligible?.invoke() == true }.getOrDefault(false)
}

/** Stream 14 has a verified zero minimum on this BYD platform. */
internal fun navigationWheelTarget(current: Int, maximum: Int, delta: Int): Int? {
    if (maximum < 0 || current !in 0..maximum || delta !in listOf(-1, 1)) return null
    return (current.toLong() + delta).coerceIn(0L, maximum.toLong()).toInt()
}

/** Unlike Activity volume selection, interception follows an owned live session in the background. */
internal object NavigationWheelSessionPolicy {
    fun canRoute(enabled: Boolean, owner: Boolean, connected: Boolean, navigationActive: Boolean,
        legacyStream: Int?, hasSink: Boolean, priorityVoice: Boolean, normalMode: Boolean): Boolean =
        enabled && owner && connected && navigationActive && legacyStream == 14 && hasSink &&
            !priorityVoice && normalMode
}

/** Registration survives UI destruction/reconnection gaps; key eligibility still requires actual guidance. */
internal object NavigationWheelRegistrationPolicy {
    fun retain(owner: Boolean, sameController: Boolean, sameSink: Boolean, controllerOpen: Boolean): Boolean =
        owner && sameController && sameSink && controllerOpen
}
