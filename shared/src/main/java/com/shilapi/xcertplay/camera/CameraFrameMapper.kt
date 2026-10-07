package com.shilapi.xcertplay.camera

/** Pixel ROI supplied by a verified owner, never inferred from ChannelOrder or an unknown calibration file. */
data class CameraFrameRegion(val left: Int, val top: Int, val width: Int, val height: Int) {
    fun valid(source: CameraFrameLayout): Boolean = source.payloadBytes() != null && left >= 0 && top >= 0 &&
        listOf(left, top, width, height).all { it % 2 == 0 } && width > 0 && height > 0 &&
        left.toLong() + width <= source.width && top.toLong() + height <= source.height
    fun layout(source: CameraFrameLayout): CameraFrameLayout {
        require(valid(source))
        return CameraFrameLayout(width, height, width, width, source.format, source.range)
    }
}

data class CameraVerifiedCapturePlan(
    val source: BmmCameraCapture.Source,
    val policy: BmmCameraCapture.Policy,
    val regions: Map<CameraView, CameraFrameRegion>,
    val kind: CameraFrameKind = CameraFrameKind.RAW,
) {
    val layout: CameraFrameLayout get() = requireNotNull(source.layoutExplicit).let {
        CameraFrameLayout(it.width, it.height, it.yStride, it.uvStride,
            if (it.format == BmmCameraCapture.PixelFormat.NV12) CameraPixelFormat.NV12 else CameraPixelFormat.NV21)
    }
    fun validate() {
        require(source.userVerifiedSource && source.layoutExplicit != null && regions.isNotEmpty()) { "capture-source-unverified" }
        require(layout.payloadBytes() in 1..CameraCaptureProtocol.MAX_FRAME_BYTES)
        require(kind != CameraFrameKind.UNVERIFIED && regions.size <= CameraView.entries.size)
        require(regions.values.all { it.valid(layout) }) { "capture-roi-unverified" }
    }
}

/** Crop Y and interleaved UV only; no Bitmap/JPEG/RGB conversion. Buffers are fixed and reused off-main. */
internal class CameraFrameMapper(private val source: CameraFrameLayout, regions: Map<CameraView, CameraFrameRegion>) {
    private val groups = regions.entries.groupBy({ it.value }, { it.key })
    private val outputs = groups.keys.associateWith { region -> ByteArray(requireNotNull(region.layout(source).payloadBytes())) }
    init { require(groups.isNotEmpty() && outputs.values.sumOf { it.size.toLong() } <= 16L * 1024 * 1024) }
    /** Receiver must copy synchronously if it retains data. Each physical region is copied only once. */
    fun dispatch(bytes: ByteArray, receiver: (CameraView, ByteArray, CameraFrameLayout) -> Unit) {
        require(source.accepts(bytes.size))
        groups.forEach { (region, views) ->
            val out = outputs.getValue(region)
            crop(bytes, source, region, out)
            val layout = region.layout(source)
            views.forEach { receiver(it, out, layout) }
        }
    }
    companion object {
        internal fun crop(bytes: ByteArray, source: CameraFrameLayout, region: CameraFrameRegion, output: ByteArray) {
            require(source.accepts(bytes.size) && region.valid(source) && region.layout(source).accepts(output.size))
            var dst = 0
            repeat(region.height) { row ->
                System.arraycopy(bytes, (region.top + row) * source.yRowStride + region.left, output, dst, region.width)
                dst += region.width
            }
            val uvStart = source.yRowStride * source.height
            repeat(region.height / 2) { row ->
                System.arraycopy(bytes, uvStart + (region.top / 2 + row) * source.uvRowStride + region.left, output, dst, region.width)
                dst += region.width
            }
        }
    }
}
