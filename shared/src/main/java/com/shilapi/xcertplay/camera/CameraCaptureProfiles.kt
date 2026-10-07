package com.shilapi.xcertplay.camera

/** Owner-device callback and physical quadrants verified while parked; no ChannelOrder inference at runtime. */
object CameraCaptureProfiles {
    fun confirmedBydAvm(): CameraVerifiedCapturePlan {
        val source = BmmCameraCapture.Source(2,5,
            BmmCameraCapture.FrameLayout(2560,1920,2560,2560,BmmCameraCapture.PixelFormat.NV21),
            BmmCameraCapture.SourceProof.USER_CONFIGURATION,
            "Owner-device metadata and one-frame physical quadrant verification",true,
            BmmCameraCapture.VerifiedCallbackContract(2560,1920,21,7372800,5,7))
        val back = CameraFrameRegion(0,0,1280,960)
        val left = CameraFrameRegion(0,960,1280,960)
        val right = CameraFrameRegion(1280,0,1280,960)
        return CameraVerifiedCapturePlan(source,BmmCameraCapture.Policy.LEGACY_CONSTRUCTOR,mapOf(
            CameraView.LEFT_REAR to left, CameraView.LEFT_FRONT to left,
            CameraView.RIGHT_REAR to right, CameraView.RIGHT_FRONT to right, CameraView.REAR to back))
    }
}
