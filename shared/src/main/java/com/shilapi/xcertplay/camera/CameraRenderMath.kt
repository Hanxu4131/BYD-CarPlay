package com.shilapi.xcertplay.camera

import java.nio.ByteBuffer

/** Byte order is supplied by the capture path; the renderer never infers it from image content. */
enum class CameraPixelFormat { NV12, NV21 }
enum class CameraYuvRange { LIMITED, FULL }

data class CameraFrameLayout(
    val width: Int,
    val height: Int,
    val yRowStride: Int,
    val uvRowStride: Int,
    val format: CameraPixelFormat,
    val range: CameraYuvRange = CameraYuvRange.LIMITED,
) {
    fun payloadBytes(): Int? {
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0 ||
            yRowStride < width || uvRowStride < width) return null
        val bytes = yRowStride.toLong() * height + uvRowStride.toLong() * (height / 2)
        return bytes.takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }
    fun accepts(payloadBytes: Int): Boolean = payloadBytes() == payloadBytes
    fun packedBytes(): Int? = payloadBytes()?.let { (width.toLong() * height * 3 / 2).toInt() }
    internal fun packInto(payload: ByteArray, target: ByteBuffer) {
        require(accepts(payload.size))
        val packed = requireNotNull(packedBytes())
        require(target.capacity() >= packed)
        target.clear(); target.limit(packed)
        if (yRowStride == width && uvRowStride == width) target.put(payload)
        else {
            for (row in 0 until height) target.put(payload, row * yRowStride, width)
            val uvOffset = yRowStride * height
            for (row in 0 until height / 2) target.put(payload, uvOffset + row * uvRowStride, width)
        }
        target.flip()
    }

}

data class CameraCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    fun valid(): Boolean = listOf(left, top, right, bottom).all { it.isFinite() } &&
        left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom
}

data class CameraViewTransform(
    val crop: CameraCrop = CameraCrop(),
    val panX: Float = 0f,
    val panY: Float = 0f,
    val scale: Float = 1f,
    val mirrorX: Boolean = false,
) {
    fun valid(): Boolean = crop.valid() && panX.isFinite() && panY.isFinite() && scale.isFinite() &&
        panX in -1f..1f && panY in -1f..1f && scale in .05f..32f
}

internal data class CameraRenderCoordinates(val vertices: FloatArray, val texture: FloatArray)

internal object CameraRenderMath {
    fun coordinates(imageWidth: Int, imageHeight: Int, viewWidth: Int, viewHeight: Int,
        transform: CameraViewTransform, projectedAspect: Float? = null): CameraRenderCoordinates? {
        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0 || !transform.valid()) return null
        val zoom = maxOf(1f, transform.scale)
        val crop = transform.crop
        val uWidth = (crop.right - crop.left) / zoom
        val vHeight = (crop.bottom - crop.top) / zoom
        if (uWidth <= 0f || vHeight <= 0f) return null
        fun center(start: Float, end: Float, span: Float, pan: Float): Float {
            val base = (start + end) * .5f
            val low = span * .5f
            val high = 1f - low
            return if (pan >= 0f) base + (high - base) * pan else base + (base - low) * pan
        }
        val cx = center(crop.left, crop.right, uWidth, transform.panX)
        val cy = center(crop.top, crop.bottom, vHeight, transform.panY)
        val left = cx - uWidth * .5f
        val right = cx + uWidth * .5f
        val top = cy - vHeight * .5f
        val bottom = cy + vHeight * .5f
        val imageAspect = (projectedAspect?.toDouble() ?: (imageWidth.toDouble() / imageHeight)) * uWidth / vHeight
        val viewAspect = viewWidth.toDouble() / viewHeight
        val shrink = minOf(1f, transform.scale)
        val x = (if (imageAspect >= viewAspect) 1.0 else imageAspect / viewAspect).toFloat() * shrink
        val y = (if (imageAspect >= viewAspect) viewAspect / imageAspect else 1.0).toFloat() * shrink
        val uLeft = if (transform.mirrorX) right else left
        val uRight = if (transform.mirrorX) left else right
        return CameraRenderCoordinates(
            floatArrayOf(-x, -y, x, -y, -x, y, x, y),
            // Camera row zero is at the top; GL's first vertex below is at the bottom.
            floatArrayOf(uLeft, bottom, uRight, bottom, uLeft, top, uRight, top),
        )
    }

    fun swapUv(format: CameraPixelFormat): Float = if (format == CameraPixelFormat.NV21) 1f else 0f

    /** Scalar reference for checking the shader's YUV range and explicitly selected UV order. */
    fun referenceRgb(y: Int, firstChroma: Int, secondChroma: Int, format: CameraPixelFormat,
        range: CameraYuvRange): FloatArray {
        require(listOf(y, firstChroma, secondChroma).all { it in 0..255 })
        val u = (if (format == CameraPixelFormat.NV12) firstChroma else secondChroma) / 255f - 128f / 255f
        val v = (if (format == CameraPixelFormat.NV12) secondChroma else firstChroma) / 255f - 128f / 255f
        val limited = range == CameraYuvRange.LIMITED
        val luma = (y / 255f - if (limited) 16f / 255f else 0f) * if (limited) 1.164383f else 1f
        return floatArrayOf(
            luma + (if (limited) 1.596027f else 1.402f) * v,
            luma - (if (limited) .391762f else .344136f) * u - (if (limited) .812968f else .714136f) * v,
            luma + (if (limited) 2.017232f else 1.772f) * u,
        ).map { it.coerceIn(0f, 1f) }.toFloatArray()
    }
}
