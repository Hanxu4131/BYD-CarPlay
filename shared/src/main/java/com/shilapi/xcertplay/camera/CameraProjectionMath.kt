package com.shilapi.xcertplay.camera

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Lens coordinates are normalized against the complete source frame, not another camera's tile. */
data class CameraFisheyeLens(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val fisheyeFovRadians: Float,
    val sourceRegion: CameraCrop,
) {
    fun valid(): Boolean = sourceRegion.valid() &&
        listOf(centerX, centerY, radiusX, radiusY, fisheyeFovRadians).all { it.isFinite() } &&
        radiusX > 0f && radiusY > 0f && fisheyeFovRadians >= .001f && fisheyeFovRadians <= (Math.PI * 2).toFloat() &&
        centerX - radiusX >= sourceRegion.left && centerX + radiusX <= sourceRegion.right &&
        centerY - radiusY >= sourceRegion.top && centerY + radiusY <= sourceRegion.bottom
}

/** This is an original equidistant model, not a reconstruction of L1's undocumented calibration. */
sealed class CameraProjection {
    object None : CameraProjection()
    data class EquidistantFisheye(
        val lens: CameraFisheyeLens,
        val yawRadians: Float = 0f,
        val pitchRadians: Float = 0f,
        val rollRadians: Float = 0f,
        val horizontalFovRadians: Float,
        val outputAspect: Float,
    ) : CameraProjection()

    data class Rectilinear(
        val sourceRegion: CameraCrop,
        val cameraSourceHorizontalFovRadians: Float,
        val yawRadians: Float = 0f,
        val pitchRadians: Float = 0f,
        val rollRadians: Float = 0f,
        val horizontalFovRadians: Float,
        val outputAspect: Float,
    ) : CameraProjection()

    fun valid(): Boolean = when (this) {
        None -> true
        is Rectilinear -> sourceRegion.valid() &&
            listOf(yawRadians, pitchRadians, rollRadians, cameraSourceHorizontalFovRadians, horizontalFovRadians, outputAspect).all { it.isFinite() } &&
            cameraSourceHorizontalFovRadians >= .001f && cameraSourceHorizontalFovRadians < Math.PI.toFloat() &&
            tan(cameraSourceHorizontalFovRadians.toDouble() * .5).let { it.isFinite() && it <= 16_000 } &&
            horizontalFovRadians >= .001f && horizontalFovRadians < Math.PI.toFloat() && outputAspect > 0f &&
            tan(horizontalFovRadians.toDouble() * .5).let { it.isFinite() && it <= 16_000 && it / outputAspect <= 16_000 }
        is EquidistantFisheye -> lens.valid() &&
            listOf(yawRadians, pitchRadians, rollRadians, horizontalFovRadians, outputAspect).all { it.isFinite() } &&
            horizontalFovRadians >= .001f && horizontalFovRadians < Math.PI.toFloat() && outputAspect > 0f &&
            tan(horizontalFovRadians.toDouble() * .5).let { it.isFinite() && it <= 16_000 && it / outputAspect <= 16_000 }
    }
}

internal data class CameraProjectedUv(val u: Float, val v: Float)

internal object CameraProjectionMath {
    /** Column-major R_y(yaw) * R_x(-pitch) * R_z(roll), as uploaded to GLES's mat3. */
    fun rotation(projection: CameraProjection): FloatArray {
        require(projection.valid())
        val angles = when (projection) {
            CameraProjection.None -> floatArrayOf(0f, 0f, 0f)
            is CameraProjection.EquidistantFisheye -> floatArrayOf(projection.yawRadians, projection.pitchRadians, projection.rollRadians)
            is CameraProjection.Rectilinear -> floatArrayOf(projection.yawRadians, projection.pitchRadians, projection.rollRadians)
        }
        val cy = cos(angles[0].toDouble()); val sy = sin(angles[0].toDouble())
        val cp = cos(angles[1].toDouble()); val sp = sin(angles[1].toDouble())
        val cr = cos(angles[2].toDouble()); val sr = sin(angles[2].toDouble())
        return floatArrayOf(
            (cy * cr - sy * sp * sr).toFloat(), (cp * sr).toFloat(), (-sy * cr - cy * sp * sr).toFloat(),
            (-cy * sr - sy * sp * cr).toFloat(), (cp * cr).toFloat(), (sy * sr - cy * sp * cr).toFloat(),
            (sy * cp).toFloat(), sp.toFloat(), (cy * cp).toFloat(),
        )
    }

    fun outputAspect(projection: CameraProjection): Float? = when (projection) {
        CameraProjection.None -> null
        is CameraProjection.EquidistantFisheye -> projection.outputAspect
        is CameraProjection.Rectilinear -> projection.outputAspect
    }

    fun horizontalFov(projection: CameraProjection): Float? = when (projection) {
        CameraProjection.None -> null
        is CameraProjection.EquidistantFisheye -> projection.horizontalFovRadians
        is CameraProjection.Rectilinear -> projection.horizontalFovRadians
    }

    /** Vertical source FOV follows the explicitly calibrated ROI's pixel aspect. */
    fun sourceTangents(projection: CameraProjection.Rectilinear, width: Int, height: Int): FloatArray? {
        if (!projection.valid() || width <= 0 || height <= 0) return null
        val region = projection.sourceRegion
        val aspect = width.toDouble() * (region.right - region.left) / (height.toDouble() * (region.bottom - region.top))
        val horizontal = tan(projection.cameraSourceHorizontalFovRadians.toDouble() * .5)
        val vertical = horizontal / aspect
        if (!vertical.isFinite() || vertical <= 0 || vertical > 16_000) return null
        return floatArrayOf(horizontal.toFloat(), vertical.toFloat())
    }

    /** Positive yaw looks right; positive pitch looks up; positive roll rotates the visible image clockwise. */
    fun project(u: Float, v: Float, width: Int, height: Int, projection: CameraProjection): CameraProjectedUv? {
        if (!projection.valid() || width <= 0 || height <= 0 || !u.isFinite() || !v.isFinite() || u !in 0f..1f || v !in 0f..1f) return null
        if (projection === CameraProjection.None) return CameraProjectedUv(u, v)
        val tangent = tan(requireNotNull(horizontalFov(projection)).toDouble() * .5)
        val x = (2 * u - 1) * tangent
        val y = (1 - 2 * v) * tangent / requireNotNull(outputAspect(projection))
        val norm = sqrt(x * x + y * y + 1)
        val ray = doubleArrayOf(x / norm, y / norm, 1 / norm)
        val matrix = rotation(projection)
        val rx = matrix[0] * ray[0] + matrix[3] * ray[1] + matrix[6] * ray[2]
        val ry = matrix[1] * ray[0] + matrix[4] * ray[1] + matrix[7] * ray[2]
        val rz = matrix[2] * ray[0] + matrix[5] * ray[1] + matrix[8] * ray[2]
        if (projection is CameraProjection.Rectilinear) {
            if (rz <= 0) return null
            val source = sourceTangents(projection, width, height) ?: return null
            val localU = .5 + .5 * rx / rz / source[0]
            val localV = .5 - .5 * ry / rz / source[1]
            val region = projection.sourceRegion
            return bounded(region.left + localU * (region.right - region.left),
                region.top + localV * (region.bottom - region.top), region, width, height)
        }
        projection as CameraProjection.EquidistantFisheye
        val radial = sqrt(rx * rx + ry * ry)
        val theta = atan2(radial, rz)
        val halfFov = projection.lens.fisheyeFovRadians * .5
        if (theta > halfFov) return null
        // The optical axis has no azimuth. A rear-facing axis belongs to no uniquely defined pixel.
        if (radial < 1e-5 && rz < 0) return null
        val distance = theta / halfFov
        val mappedU = projection.lens.centerX + if (radial < 1e-5) 0.0 else rx / radial * projection.lens.radiusX * distance
        val mappedV = projection.lens.centerY - if (radial < 1e-5) 0.0 else ry / radial * projection.lens.radiusY * distance
        return bounded(mappedU, mappedV, projection.lens.sourceRegion, width, height)
    }

    private fun bounded(mappedU: Double, mappedV: Double, region: CameraCrop, width: Int, height: Int): CameraProjectedUv? {
        // Half a chroma texel is one source pixel: protect both filtered Y and UV planes.
        if (!mappedU.isFinite() || !mappedV.isFinite() ||
            mappedU < region.left + 1.0 / width || mappedU > region.right - 1.0 / width ||
            mappedV < region.top + 1.0 / height || mappedV > region.bottom - 1.0 / height) return null
        return CameraProjectedUv(mappedU.toFloat(), mappedV.toFloat())
    }
}
