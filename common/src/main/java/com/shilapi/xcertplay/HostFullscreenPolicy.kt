package com.shilapi.xcertplay

internal object HostFullscreenPolicy {
    fun overrideBars(displayId: Int, width: Int, maximumWidth: Int, rootWidth: Int,
        rootHeight: Int, maximumHeight: Int, multiWindow: Boolean, windowingMode: String?): Boolean {
        if (displayId != 0 || multiWindow || maximumWidth <= 0 || width < maximumWidth - 2) return false
        if (windowingMode == "fullscreen") return true
        if (windowingMode != null && windowingMode != "undefined") return false
        return rootWidth >= maximumWidth - 2 && maximumHeight > 0 && rootHeight >= maximumHeight * 0.85
    }
}
