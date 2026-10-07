package com.shilapi.xcertplay.camera

import kotlin.math.roundToInt

/** Discrete slider steps; values and labels share the same mapping to avoid hidden rounding on Save. */
class CameraSliderMapping(val minimum: Float, val maximum: Float, val step: Float) {
    init { require(minimum.isFinite() && maximum.isFinite() && step.isFinite() && maximum > minimum && step > 0f) }
    val steps = ((maximum - minimum) / step).roundToInt().coerceAtLeast(1)
    fun value(progress: Int): Float = if (progress >= steps) maximum else
        (minimum.toDouble() + progress.coerceAtLeast(0) * step.toDouble()).toFloat().coerceIn(minimum, maximum)
    fun progress(value: Float): Int = if (!value.isFinite()) 0 else
        ((value.coerceIn(minimum, maximum) - minimum) / step).roundToInt().coerceIn(0, steps)

    companion object {
        val YAW_ROLL = CameraSliderMapping(-180f, 180f, .5f)
        val PITCH = CameraSliderMapping(-90f, 90f, .5f)
        val FOV = CameraSliderMapping(10f, 170f, .5f)
        val PAN = CameraSliderMapping(-1f, 1f, .01f)
        val ZOOM = CameraSliderMapping(1f, 8f, .05f)
        val POSITION = CameraSliderMapping(0f, 1f, .005f)
        val SIZE = CameraSliderMapping(.02f, 1f, .005f)
    }
}
