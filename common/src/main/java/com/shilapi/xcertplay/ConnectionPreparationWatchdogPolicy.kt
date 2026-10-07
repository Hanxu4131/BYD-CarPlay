package com.shilapi.xcertplay

/** Each unconnected controller attempt gets its own deadline. */
internal class ConnectionPreparationWatchdogPolicy {
    class Attempt internal constructor(val controller: Any, val generation: Int, val deadlineMillis: Long)
    enum class Action { STOP, WAIT, RESTART }

    private var current: Attempt? = null

    fun arm(controller: Any, generation: Int, nowMillis: Long): Attempt =
        Attempt(controller, generation, nowMillis + TIMEOUT_MILLIS).also { current = it }

    fun cancel() { current = null }

    fun action(attempt: Attempt, controller: Any?, generation: Int, nowMillis: Long,
        owner: Boolean, connected: Boolean, blocked: Boolean, wireless: Boolean = true): Action {
        if (current !== attempt || controller !== attempt.controller || generation != attempt.generation) {
            return Action.STOP
        }
        if (!wireless || !owner || connected || blocked) {
            current = null
            return Action.STOP
        }
        if (nowMillis < attempt.deadlineMillis) return Action.WAIT
        current = null
        return Action.RESTART
    }

    companion object {
        const val TIMEOUT_MILLIS = 60_000L

        fun hasCurrentSession(expected: Any, currentController: Any?, controllerActive: Boolean,
            hostActive: Boolean, backgroundController: Any?, backgroundActive: Boolean): Boolean =
            currentController === expected && (controllerActive || hostActive ||
                (backgroundController === expected && backgroundActive))
    }
}
