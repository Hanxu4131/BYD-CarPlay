package com.shilapi.xcertplay.camera

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

enum class CameraView(val key: String, val display: CameraDisplay) {
    LEFT_REAR("left_rear", CameraDisplay.INSTRUMENT),
    RIGHT_REAR("right_rear", CameraDisplay.INSTRUMENT),
    REAR("rear", CameraDisplay.INSTRUMENT),
    LEFT_FRONT("left_front", CameraDisplay.CENTER),
    RIGHT_FRONT("right_front", CameraDisplay.CENTER),
}

enum class CameraDisplay { INSTRUMENT, CENTER }

enum class CameraProjectionMode { CORRECTED, RECTILINEAR, FISHEYE_EQUIDISTANT }

/** Crop coordinates refer to an already corrected per-view frame, not raw fisheye sensor calibration. */
data class CameraSourceCrop(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f,
) {
    fun sanitized(): CameraSourceCrop {
        val l = left.finiteOr(0f).coerceIn(0f, 0.98f)
        val t = top.finiteOr(0f).coerceIn(0f, 0.98f)
        val r = right.finiteOr(1f).coerceIn(l + MIN_CROP_SPAN, 1f)
        val b = bottom.finiteOr(1f).coerceIn(t + MIN_CROP_SPAN, 1f)
        return CameraSourceCrop(l, t, r, b)
    }

    companion object { private const val MIN_CROP_SPAN = 0.02f }
}

/** Placement is fractional within the selected display, so it survives display density/resolution changes. */
data class CameraViewport(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    fun sanitized(): CameraViewport {
        val w = width.finiteOr(0.3f).coerceIn(MIN_VIEW_SIZE, 1f)
        val h = height.finiteOr(0.3f).coerceIn(MIN_VIEW_SIZE, 1f)
        val xPos = x.finiteOr(0f).coerceIn(0f, 1f - w)
        val yPos = y.finiteOr(0f).coerceIn(0f, 1f - h)
        return CameraViewport(xPos, yPos, w, h)
    }

    /** Maps normalized placement to a clamped host-display pixel rectangle. */
    fun toPixelRect(displayWidthPx: Int, displayHeightPx: Int): CameraPixelRect {
        require(displayWidthPx > 0 && displayHeightPx > 0) { "Display dimensions must be positive" }
        val safe = sanitized()
        val left = (safe.x * displayWidthPx).roundToInt().coerceIn(0, displayWidthPx)
        val top = (safe.y * displayHeightPx).roundToInt().coerceIn(0, displayHeightPx)
        val width = (safe.width * displayWidthPx).roundToInt().coerceIn(0, displayWidthPx - left)
        val height = (safe.height * displayHeightPx).roundToInt().coerceIn(0, displayHeightPx - top)
        return CameraPixelRect(left, top, width, height)
    }

    companion object { private const val MIN_VIEW_SIZE = 0.02f }
}

data class CameraPixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** Per-view source calibration. Values are normalized against the selected source frame. */
data class CameraLensSettings(
    val projectionMode: CameraProjectionMode = CameraProjectionMode.CORRECTED,
    val lensCenterX: Float? = null,
    val lensCenterY: Float? = null,
    val lensRadiusX: Float? = null,
    val lensRadiusY: Float? = null,
    val sourceRect: CameraSourceCrop = CameraSourceCrop(),
    val sourceCameraTag: String? = null,
    val sourceId: String? = null,
    val calibrationConfirmed: Boolean = false,
    val fisheyeFovDegrees: Float? = null,
    val sourceHorizontalFovDegrees: Float? = null,
) {
    fun sanitized(): CameraLensSettings = copy(
        lensCenterX = lensCenterX?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        lensCenterY = lensCenterY?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        lensRadiusX = lensRadiusX?.takeIf { it.isFinite() }?.coerceIn(MIN_LENS_RADIUS, 1f),
        lensRadiusY = lensRadiusY?.takeIf { it.isFinite() }?.coerceIn(MIN_LENS_RADIUS, 1f),
        sourceRect = sourceRect.sanitized(),
        fisheyeFovDegrees = fisheyeFovDegrees?.takeIf { it.isFinite() && it in 10f..360f },
        sourceHorizontalFovDegrees = sourceHorizontalFovDegrees?.takeIf { it.isFinite() && it in 10f..170f },
        sourceCameraTag = sourceCameraTag?.trim()?.take(MAX_SOURCE_LABEL_LENGTH)?.ifEmpty { null },
        sourceId = sourceId?.trim()?.take(MAX_SOURCE_LABEL_LENGTH)?.ifEmpty { null },
    )

    companion object {
        private const val MIN_LENS_RADIUS = 0.01f
        private const val MAX_SOURCE_LABEL_LENGTH = 128
    }
}

data class CameraViewSettings(
    val crop: CameraSourceCrop,
    val panX: Float,
    val panY: Float,
    val zoom: Float,
    val mirrored: Boolean,
    val viewport: CameraViewport,
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val rollDegrees: Float = 0f,
    val fovDegrees: Float = DEFAULT_FOV_DEGREES,
    val lens: CameraLensSettings = CameraLensSettings(),
) {
    /** FOV is usable only after its per-view calibration has been explicitly confirmed. */
    val effectiveFovDegrees: Float?
        get() = fovDegrees.finiteOr(DEFAULT_FOV_DEGREES)
            .coerceIn(MIN_FOV_DEGREES, MAX_FOV_DEGREES)
            .takeIf { lens.calibrationConfirmed }

    fun sanitized(): CameraViewSettings = copy(
        crop = crop.sanitized(),
        panX = panX.finiteOr(0f).coerceIn(-1f, 1f),
        panY = panY.finiteOr(0f).coerceIn(-1f, 1f),
        zoom = zoom.finiteOr(1f).coerceIn(MIN_ZOOM, MAX_ZOOM),
        viewport = viewport.sanitized(),
        yawDegrees = yawDegrees.finiteOr(0f).coerceIn(MIN_YAW_DEGREES, MAX_YAW_DEGREES),
        pitchDegrees = pitchDegrees.finiteOr(0f).coerceIn(MIN_PITCH_DEGREES, MAX_PITCH_DEGREES),
        rollDegrees = rollDegrees.finiteOr(0f).coerceIn(MIN_ROLL_DEGREES, MAX_ROLL_DEGREES),
        fovDegrees = fovDegrees.finiteOr(DEFAULT_FOV_DEGREES).coerceIn(MIN_FOV_DEGREES, MAX_FOV_DEGREES),
        lens = lens.sanitized(),
    )

    companion object {
        const val DEFAULT_FOV_DEGREES = 90f
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 8f
        private const val MIN_YAW_DEGREES = -180f
        private const val MAX_YAW_DEGREES = 180f
        private const val MIN_PITCH_DEGREES = -90f
        private const val MAX_PITCH_DEGREES = 90f
        private const val MIN_ROLL_DEGREES = -180f
        private const val MAX_ROLL_DEGREES = 180f
        private const val MIN_FOV_DEGREES = 10f
        private const val MAX_FOV_DEGREES = 170f
    }
}

/** User configuration only. Save/Cancel draft ownership stays in the UI layer. */
object CameraSettings {
    private const val PREFS = "diplay_camera_views"
    private const val KEY_ENABLED = "enabled"
    const val DEFAULT_BRAKE_DEPTH_THRESHOLD = 40f
    const val DEFAULT_STEERING_THRESHOLD_DEGREES = 15f
    private const val FRONT_DEFAULT_X_FRACTION = 22f / 1920f
    private const val FRONT_DEFAULT_Y_FRACTION = 748f / 1080f
    private const val FRONT_DEFAULT_WIDTH_FRACTION = 592f / 1920f
    private const val FRONT_DEFAULT_HEIGHT_FRACTION = 242f / 1080f
    private const val FRONT_CARD_BOTTOM_FRACTION = 990f / 1080f

    /** Dudu card overlay, with each normalized axis scaled to its own display dimension. */
    fun defaultFrontViewport(displayWidthPx: Int, displayHeightPx: Int): CameraViewport {
        require(displayWidthPx > 0 && displayHeightPx > 0) { "Display dimensions must be positive" }
        val displayWidth = displayWidthPx.toFloat()
        val displayHeight = displayHeightPx.toFloat()
        val xPx = (displayWidth * FRONT_DEFAULT_X_FRACTION).coerceIn(0f, displayWidth)
        val bottomPx = (displayHeight * FRONT_CARD_BOTTOM_FRACTION).coerceIn(0f, displayHeight)
        val widthPx = minOf(displayWidth * FRONT_DEFAULT_WIDTH_FRACTION, displayWidth - xPx)
            .coerceAtLeast(0f)
        val heightPx = minOf(displayHeight * FRONT_DEFAULT_HEIGHT_FRACTION, bottomPx)
            .coerceAtLeast(0f)
        val yPx = (bottomPx - heightPx).coerceAtLeast(0f)
        return CameraViewport(
            x = xPx / displayWidth,
            y = yPx / displayHeight,
            width = widthPx / displayWidth,
            height = heightPx / displayHeight,
        ).sanitized()
    }

    data class Values(
        val enabled: Boolean = false,
        val brakeDepthThreshold: Float = DEFAULT_BRAKE_DEPTH_THRESHOLD,
        val steeringThresholdDegrees: Float = DEFAULT_STEERING_THRESHOLD_DEGREES,
        val views: Map<CameraView, CameraViewSettings> = defaults(),
    ) {
        fun sanitized(): Values = copy(
            brakeDepthThreshold = brakeDepthThreshold.finiteOr(DEFAULT_BRAKE_DEPTH_THRESHOLD).coerceIn(0f, 100f),
            steeringThresholdDegrees = steeringThresholdDegrees.finiteOr(DEFAULT_STEERING_THRESHOLD_DEGREES).coerceIn(0f, 360f),
            views = CameraView.entries.associateWith { view ->
                (views[view] ?: defaultViewSettings(view)).sanitized()
            },
        )
    }

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun load(context: Context): Values {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val base = Values(enabled = p.getBoolean(KEY_ENABLED, false))
        val loadedViews = CameraView.entries.associateWith { view ->
            val d = defaultViewSettings(view)
            val prefix = "${view.key}."
            CameraViewSettings(
                crop = CameraSourceCrop(
                    p.getFloat(prefix + "crop_left", d.crop.left),
                    p.getFloat(prefix + "crop_top", d.crop.top),
                    p.getFloat(prefix + "crop_right", d.crop.right),
                    p.getFloat(prefix + "crop_bottom", d.crop.bottom),
                ),
                panX = p.getFloat(prefix + "pan_x", d.panX),
                panY = p.getFloat(prefix + "pan_y", d.panY),
                zoom = p.getFloat(prefix + "zoom", d.zoom),
                mirrored = p.getBoolean(prefix + "mirrored", d.mirrored),
                viewport = CameraViewport(
                    p.getFloat(prefix + "viewport_x", d.viewport.x),
                    p.getFloat(prefix + "viewport_y", d.viewport.y),
                    p.getFloat(prefix + "viewport_width", d.viewport.width),
                    p.getFloat(prefix + "viewport_height", d.viewport.height),
                ),
                yawDegrees = p.getFloat(prefix + "yaw_degrees", d.yawDegrees),
                pitchDegrees = p.getFloat(prefix + "pitch_degrees", d.pitchDegrees),
                rollDegrees = p.getFloat(prefix + "roll_degrees", d.rollDegrees),
                fovDegrees = p.getFloat(prefix + "fov_degrees", d.fovDegrees),
                lens = CameraLensSettings(
                    fisheyeFovDegrees = p.getFloatOrNull(prefix + "lens_fisheye_fov"),
                    sourceHorizontalFovDegrees = p.getFloatOrNull(prefix + "lens_source_horizontal_fov"),
                    projectionMode = p.getString(prefix + "projection_mode", d.lens.projectionMode.name)
                        ?.let { runCatching { CameraProjectionMode.valueOf(it) }.getOrNull() }
                        ?: d.lens.projectionMode,
                    lensCenterX = p.getFloatOrNull(prefix + "lens_center_x"),
                    lensCenterY = p.getFloatOrNull(prefix + "lens_center_y"),
                    lensRadiusX = p.getFloatOrNull(prefix + "lens_radius_x"),
                    lensRadiusY = p.getFloatOrNull(prefix + "lens_radius_y"),
                    sourceRect = CameraSourceCrop(
                        p.getFloat(prefix + "source_rect_left", d.lens.sourceRect.left),
                        p.getFloat(prefix + "source_rect_top", d.lens.sourceRect.top),
                        p.getFloat(prefix + "source_rect_right", d.lens.sourceRect.right),
                        p.getFloat(prefix + "source_rect_bottom", d.lens.sourceRect.bottom),
                    ),
                    sourceCameraTag = p.getString(prefix + "source_camera_tag", d.lens.sourceCameraTag),
                    sourceId = p.getString(prefix + "source_id", d.lens.sourceId),
                    calibrationConfirmed = p.getBoolean(
                        prefix + "calibration_confirmed", d.lens.calibrationConfirmed,
                    ),
                ),
            ).sanitized()
        }
        return base.copy(
            brakeDepthThreshold = p.getFloat("brake_depth_threshold", DEFAULT_BRAKE_DEPTH_THRESHOLD),
            steeringThresholdDegrees = p.getFloat("steering_threshold_degrees", DEFAULT_STEERING_THRESHOLD_DEGREES),
            views = loadedViews,
        ).sanitized().let { values ->
            if (context.packageName.endsWith(".tang21test")) CameraTang21Optics.bootstrap(values) else values
        }
    }

    fun save(context: Context, values: Values) {
        val safe = values.sanitized()
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, safe.enabled)
            .putFloat("brake_depth_threshold", safe.brakeDepthThreshold)
            .putFloat("steering_threshold_degrees", safe.steeringThresholdDegrees)
        safe.views.forEach { (view, settings) ->
            val prefix = "${view.key}."
            editor.putFloat(prefix + "crop_left", settings.crop.left)
                .putFloat(prefix + "crop_top", settings.crop.top)
                .putFloat(prefix + "crop_right", settings.crop.right)
                .putFloat(prefix + "crop_bottom", settings.crop.bottom)
                .putFloat(prefix + "pan_x", settings.panX)
                .putFloat(prefix + "pan_y", settings.panY)
                .putFloat(prefix + "zoom", settings.zoom)
                .putBoolean(prefix + "mirrored", settings.mirrored)
                .putFloat(prefix + "viewport_x", settings.viewport.x)
                .putFloat(prefix + "viewport_y", settings.viewport.y)
                .putFloat(prefix + "viewport_width", settings.viewport.width)
                .putFloat(prefix + "viewport_height", settings.viewport.height)
                .putFloat(prefix + "yaw_degrees", settings.yawDegrees)
                .putFloat(prefix + "pitch_degrees", settings.pitchDegrees)
                .putFloat(prefix + "roll_degrees", settings.rollDegrees)
                .putFloat(prefix + "fov_degrees", settings.fovDegrees)
                .putString(prefix + "projection_mode", settings.lens.projectionMode.name)
                .putFloatOrRemove(prefix + "lens_center_x", settings.lens.lensCenterX)
                .putFloatOrRemove(prefix + "lens_center_y", settings.lens.lensCenterY)
                .putFloatOrRemove(prefix + "lens_radius_x", settings.lens.lensRadiusX)
                .putFloatOrRemove(prefix + "lens_radius_y", settings.lens.lensRadiusY)
                .putFloat(prefix + "source_rect_left", settings.lens.sourceRect.left)
                .putFloat(prefix + "source_rect_top", settings.lens.sourceRect.top)
                .putFloat(prefix + "source_rect_right", settings.lens.sourceRect.right)
                .putFloat(prefix + "source_rect_bottom", settings.lens.sourceRect.bottom)
                .putString(prefix + "source_camera_tag", settings.lens.sourceCameraTag)
                .putString(prefix + "source_id", settings.lens.sourceId)
                .putBoolean(prefix + "calibration_confirmed", settings.lens.calibrationConfirmed)
                .putFloatOrRemove(prefix + "lens_fisheye_fov", settings.lens.fisheyeFovDegrees)
                .putFloatOrRemove(prefix + "lens_source_horizontal_fov", settings.lens.sourceHorizontalFovDegrees)
        }
        editor.apply()
    }

    fun defaults(): Map<CameraView, CameraViewSettings> = CameraView.entries.associateWith(::defaultViewSettings)

    private fun defaultViewSettings(view: CameraView): CameraViewSettings {
        val viewport = when (view) {
            CameraView.LEFT_REAR -> CameraViewport(0.02f, 0.18f, 0.25f, 0.56f)
            CameraView.RIGHT_REAR -> CameraViewport(0.73f, 0.18f, 0.25f, 0.56f)
            // Approx. 845x282 (3:1) on the known 1920x720 instrument surface.
            CameraView.REAR -> CameraViewport(0.28f, 0.20f, 0.44f, (0.44f * 1920f / 3f) / 720f)
            // Same overlapping lower-left card rectangle on the center display.
            CameraView.LEFT_FRONT, CameraView.RIGHT_FRONT -> defaultFrontViewport(1920, 1080)
        }
        return CameraViewSettings(
            crop = CameraSourceCrop(), panX = 0f, panY = 0f,
            zoom = 1f, mirrored = false, viewport = viewport,
        )
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

private fun SharedPreferences.getFloatOrNull(key: String): Float? =
    if (contains(key)) getFloat(key, Float.NaN).takeIf { it.isFinite() } else null

private fun SharedPreferences.Editor.putFloatOrRemove(key: String, value: Float?): SharedPreferences.Editor =
    if (value == null) remove(key) else putFloat(key, value)
