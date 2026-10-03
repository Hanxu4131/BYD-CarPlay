package com.shilapi.xcertplay

/** Retrying launch is distinct from proving a rendered map. No top-window polling. */
internal class LegacyClusterRetryPolicy {
    enum class Action { STOP, WAIT, LAUNCH }
    var epoch = 0L; private set
    var wanted = false; private set
    var completed = false; private set
    fun start() { epoch++; wanted = true; completed = false }
    fun cancel() { epoch++; wanted = false; completed = false }
    fun pause() { epoch++; wanted = false }
    fun action(token: Long, enabled: Boolean, streamActive: Boolean, pending: Boolean,
        window: Boolean, validSurface: Boolean): Action {
        if (token != epoch || !wanted || completed || !enabled || !streamActive) return Action.STOP
        return if (pending || (window && validSurface)) Action.WAIT else Action.LAUNCH
    }
    fun presented(token: Long, targetMatches: Boolean, validSurface: Boolean, actualPresented: Boolean): Boolean {
        if (token != epoch || !wanted || !targetMatches || !validSurface || !actualPresented) return false
        completed = true; return true
    }
}
