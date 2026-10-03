package com.shilapi.xcertplay

import android.content.Context
import android.widget.FrameLayout

/** Main-display resize cover; independent of dashboard and connection startup timing. */
internal class AdaptiveResizeCover(context: Context) : FrameLayout(context) {
    private val artwork = ClusterStartupView(context, minimumShowMs = 0L)
    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        addView(artwork, LayoutParams(-1, -1))
    }
    fun show() { artwork.setUltra(StartupLogoPreferences.ultra(context)); artwork.waitForFrame() }
    fun reveal(reason: String) { artwork.revealMap(reason) }
    fun dispose() { artwork.dispose() }
}
