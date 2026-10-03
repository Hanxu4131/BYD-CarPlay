package com.shilapi.xcertplay

/** One cover per requested area; geometry and presentation are separate confirmations. */
internal class AdaptiveResizeTransition {
    private var generation = 0L
    var targetIndex: Int? = null; private set
    private var requestEpoch: Long? = null
    private var geometrySerial: Long? = null
    private var geometrySurface: Any? = null
    val active: Boolean get() = targetIndex != null
    fun begin(index: Int): Long {
        if (targetIndex == index) return generation
        generation++; targetIndex = index; requestEpoch = null; geometrySerial = null; geometrySurface = null
        return generation
    }
    fun arm(index: Int, epoch: Long) { if (targetIndex == index) requestEpoch = epoch }
    fun confirm(index: Int, epoch: Long, serial: Long, surface: Any?) {
        if (targetIndex != index || requestEpoch != epoch || geometrySerial != null || surface == null) return
        geometrySerial = serial; geometrySurface = surface
    }
    fun presented(serial: Long, surface: Any?): Boolean {
        val ready = geometrySerial ?: return false
        if (surface !== geometrySurface || serial <= ready) return false
        cancel(); return true
    }
    fun expire(token: Long): Boolean {
        if (!active || token != generation) return false
        cancel(); return true
    }
    fun cancel() { generation++; targetIndex = null; requestEpoch = null; geometrySerial = null; geometrySurface = null }
}
