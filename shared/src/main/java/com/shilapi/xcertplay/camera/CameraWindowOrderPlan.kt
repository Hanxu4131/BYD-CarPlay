package com.shilapi.xcertplay.camera

/** Bottom first. Removing an upper window must not detach the lower survivor's EGL surface. */
data class CameraWindowOrderPlan(val remove: List<CameraView>, val add: List<CameraView>)

fun cameraWindowOrderPlan(attachedBottomFirst: List<CameraView>, desiredBottomFirst: List<CameraView>): CameraWindowOrderPlan {
    require(attachedBottomFirst.distinct().size == attachedBottomFirst.size)
    require(desiredBottomFirst.distinct().size == desiredBottomFirst.size)
    val survivors = attachedBottomFirst.filter { it in desiredBottomFirst }
    val prefix = survivors.zip(desiredBottomFirst).takeWhile { (old, desired) -> old == desired }.size
    val untouched = survivors.take(prefix).toSet()
    return CameraWindowOrderPlan(attachedBottomFirst.filter { it !in untouched }, desiredBottomFirst.drop(prefix))
}

/** Attached alpha-zero windows still own a warm Surface. Only visible windows participate in stacking. */
fun cameraWarmWindowOrderPlan(attachedBottomFirst: List<CameraView>, visibleBottomFirst: List<CameraView>): CameraWindowOrderPlan {
    require(attachedBottomFirst.distinct().size == attachedBottomFirst.size)
    require(visibleBottomFirst.distinct().size == visibleBottomFirst.size)
    var cursor = 0
    var prefix = 0
    for (view in visibleBottomFirst) {
        val index = attachedBottomFirst.indexOf(view)
        if (index < cursor) break
        cursor = index + 1
        prefix++
    }
    val moveToTop = visibleBottomFirst.drop(prefix)
    return CameraWindowOrderPlan(moveToTop.filter { it in attachedBottomFirst }, moveToTop)
}

data class CameraWindowPresentation(val attached: Boolean, val visible: Boolean)

fun cameraWindowPresentation(attached: Boolean, hasReceivedFrame: Boolean, frameFresh: Boolean,
    requestedVisible: Boolean): CameraWindowPresentation {
    val warm = attached || hasReceivedFrame
    return CameraWindowPresentation(warm, warm && frameFresh && requestedVisible)
}
