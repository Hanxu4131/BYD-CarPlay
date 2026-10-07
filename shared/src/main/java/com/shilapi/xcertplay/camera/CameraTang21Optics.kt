package com.shilapi.xcertplay.camera

/** Initial projection for the verified 1280x960 OEM fisheye tiles. Fine adjustment stays per view. */
object CameraTang21Optics {
    fun bootstrap(values: CameraSettings.Values): CameraSettings.Values = values.copy(
        views = values.views.mapValues { (view, current) ->
            if (CameraIntegration.canProject(current, 1f)) current else current.copy(
                // Upgrade an unused zero-angle draft once. Existing custom angles and windows stay intact.
                yawDegrees = if (current.lens.sourceId == null && current.yawDegrees == 0f) when (view) {
                    CameraView.LEFT_REAR -> 40f
                    CameraView.RIGHT_REAR -> -40f
                    CameraView.LEFT_FRONT -> 30f
                    CameraView.RIGHT_FRONT -> -30f
                    CameraView.REAR -> 0f
                } else current.yawDegrees,
                pitchDegrees = if (current.lens.sourceId == null && current.pitchDegrees == 0f &&
                    view.display == CameraDisplay.CENTER) -40f else current.pitchDegrees,
                lens = CameraLensSettings(
                    projectionMode = CameraProjectionMode.FISHEYE_EQUIDISTANT,
                    lensCenterX = .5f, lensCenterY = .5f,
                    lensRadiusX = .5f, lensRadiusY = .5f,
                    fisheyeFovDegrees = 180f,
                    sourceCameraTag = when (view) {
                        CameraView.LEFT_FRONT, CameraView.LEFT_REAR -> "left"
                        CameraView.RIGHT_FRONT, CameraView.RIGHT_REAR -> "right"
                        CameraView.REAR -> "rear"
                    },
                    sourceId = "tang21-avm-tile",
                    calibrationConfirmed = true,
                ),
            )
        },
    )
}
