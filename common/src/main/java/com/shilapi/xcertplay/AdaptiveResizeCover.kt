package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Static cover for a host resize while the newly sized video surface is becoming ready. */
internal class AdaptiveResizeCover(private val appContext: Context) : FrameLayout(appContext) {
    private val startup = ClusterStartupView(appContext, minimumShowMs = 0L, maximumWaitMs = 6_000L)
    private val lastFrame = ImageView(appContext).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
    }
    private var generation = 0L
    private var active = false

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        visibility = View.GONE
        addView(startup, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(lastFrame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        startup.onHidden = { finishReveal(generation) }
    }

    /**
     * Starts a resize cover. Ownership of [frame] is transferred to this view until it is
     * replaced, revealed, or disposed. The bitmap is only dereferenced here; it is never recycled
     * while a hardware or software draw may still be using it.
     */
    fun show(frame: Bitmap? = null) {
        generation++
        active = true
        animate().cancel()
        lastFrame.animate().cancel()
        alpha = 1f
        lastFrame.alpha = 1f
        visibility = View.VISIBLE

        startup.dispose()
        val id = generation
        startup.onHidden = { finishReveal(id) }
        if (frame != null && !frame.isRecycled) {
            setBackgroundColor(android.graphics.Color.BLACK)
            lastFrame.setImageBitmap(frame)
            lastFrame.visibility = View.VISIBLE
        } else {
            // The startup child paints its own black background and fades over live video.
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            // Drop the previous snapshot reference without recycling it during a possible draw.
            lastFrame.setImageDrawable(null)
            lastFrame.visibility = View.GONE
            startup.setUltra(StartupLogoPreferences.ultra(appContext))
            startup.waitForFrame()
        }
    }

    /** Fades the active still frame or startup animation away over the newly ready video. */
    fun reveal(reason: String = "first_frame") {
        if (!active) return
        if (lastFrame.visibility == View.VISIBLE) {
            val id = generation
            animate().cancel()
            animate()
                .alpha(0f)
                .setDuration(REVEAL_DURATION_MS)
                .withEndAction { finishReveal(id) }
                .start()
        } else {
            startup.revealMap(reason)
        }
    }

    /** Cancels animations and releases the view's references to its snapshot. */
    fun dispose() {
        generation++
        active = false
        animate().cancel()
        lastFrame.animate().cancel()
        startup.dispose()
        lastFrame.setImageDrawable(null)
        lastFrame.visibility = View.GONE
        startup.onHidden = null
        alpha = 1f
        lastFrame.alpha = 1f
        visibility = View.GONE
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    private fun finishReveal(id: Long) {
        if (!active || id != generation) return
        active = false
        lastFrame.animate().cancel()
        lastFrame.setImageDrawable(null)
        lastFrame.visibility = View.GONE
        lastFrame.alpha = 1f
        alpha = 1f
        visibility = View.GONE
    }

    private companion object {
        const val REVEAL_DURATION_MS = 220L
    }
}
