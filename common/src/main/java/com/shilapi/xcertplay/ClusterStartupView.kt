package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.host.R

internal data class StartupArtworkBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    companion object {
        fun fit(width: Int, height: Int, aspect: Float?): StartupArtworkBounds {
            if (aspect == null || aspect <= 0f) return StartupArtworkBounds(0f, 0f, width.toFloat(), height.toFloat())
            val fittedWidth = minOf(width.toFloat(), height * aspect)
            val fittedHeight = fittedWidth / aspect
            return StartupArtworkBounds((width - fittedWidth) * .5f, (height - fittedHeight) * .5f, fittedWidth, fittedHeight)
        }
    }
}

/** Local cover while a display waits for its first video frame. */
internal class ClusterStartupView(
    context: Context,
    private val minimumShowMs: Long = 1_500L,
    private val fixedAspectRatio: Float? = null,
) : View(context) {
    private val ultraLogo = requireNotNull(context.getDrawable(R.drawable.cluster_carplay_ultra_logo_vector))
    private val standardLogo = requireNotNull(context.getDrawable(R.drawable.cluster_carplay_logo_vector))
    private var ultra = false
    private var artwork = StartupArtworkBounds(0f, 0f, 0f, 0f)
    fun setUltra(enabled: Boolean) {
        if (ultra == enabled) return
        ultra = enabled
        invalidate()
    }
    fun isUltra(): Boolean = ultra
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val logoBounds = RectF()
    private var phase = 0f
    private var generation = 0
    var onHidden: (() -> Unit)? = null
    private val shaderMatrix = Matrix()
    private val accentMask = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private var cyanGlow: RadialGradient? = null
    private var violetGlow: RadialGradient? = null
    private var sheen: LinearGradient? = null
    private var glass: LinearGradient? = null
    private val panelBounds = RectF()
    private var waiting = false
    private var waitStartedAt = 0L
    private var firstFrameWaitMs: Long? = null
    private var pulse: ValueAnimator? = null
    private var glow: RadialGradient? = null
    // A missing device render callback must never leave a cover over a working map.
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { revealMap("timeout") }
    private val readyReveal = Runnable { revealMap() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        visibility = GONE
    }

    fun waitForFrame() {
        animate().cancel()
        main.removeCallbacks(timeout)
        main.removeCallbacks(readyReveal)
        waiting = true
        waitStartedAt = SystemClock.elapsedRealtime()
        firstFrameWaitMs = null
        generation++
        alpha = 1f
        visibility = VISIBLE
        startPulse()
        main.postDelayed(timeout, 5_000L)
    }

    fun revealMap(reason: String = "first_frame") {
        if (!waiting) return
        val elapsed = SystemClock.elapsedRealtime() - waitStartedAt
        if (reason == "first_frame") {
            if (firstFrameWaitMs == null) firstFrameWaitMs = elapsed
            if (elapsed < minimumShowMs) {
                main.removeCallbacks(readyReveal)
                main.postDelayed(readyReveal, minimumShowMs - elapsed)
                return
            }
        }
        waiting = false
        Log.w("DiPlay-ClusterStartup", "reveal reason=$reason waitMs=$elapsed firstFrameMs=$firstFrameWaitMs")
        main.removeCallbacks(timeout)
        main.removeCallbacks(readyReveal)
        val id = generation
        animate().alpha(0f).setDuration(220L).withEndAction {
            if (!waiting && id == generation) {
                stopPulse()
                visibility = GONE
                onHidden?.invoke()
            }
        }.start()
    }

    fun dispose() {
        generation++
        waiting = false
        main.removeCallbacks(timeout)
        main.removeCallbacks(readyReveal)
        animate().cancel()
        stopPulse()
        visibility = GONE
    }

    private fun startPulse() {
        stopPulse()
        if (!isAttachedToWindow || !waiting) return
        pulse = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_700L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopPulse() { pulse?.cancel(); pulse = null }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (waiting) startPulse()
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        artwork = StartupArtworkBounds.fit(w, h, fixedAspectRatio)
        val w = artwork.width
        val h = artwork.height
        val radius = maxOf(w, h) * .58f
        glow = RadialGradient(w * .5f, h * .5f, radius,
            Color.rgb(17, 24, 43), Color.rgb(3, 6, 14), Shader.TileMode.CLAMP)
        cyanGlow = RadialGradient(w * .25f, h * .55f, radius * .65f,
            Color.argb(145, 20, 166, 225), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        violetGlow = RadialGradient(w * .76f, h * .40f, radius * .65f,
            Color.argb(145, 130, 66, 215), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        glass = LinearGradient(0f, h * .28f, 0f, h * .76f,
            Color.argb(18, 210, 226, 255), Color.argb(3, 210, 226, 255), Shader.TileMode.CLAMP)
        sheen = LinearGradient(0f, 0f, w * .42f, 0f,
            intArrayOf(Color.TRANSPARENT, Color.argb(245, 60, 205, 255),
                Color.WHITE, Color.argb(245, 190, 95, 255), Color.TRANSPARENT),
            floatArrayOf(0f, .25f, .5f, .75f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val content = canvas.save()
        canvas.translate(artwork.left, artwork.top)
        canvas.clipRect(0f, 0f, artwork.width, artwork.height)
        val width = artwork.width
        val height = artwork.height
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.shader = glow
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val breath = ((1 - kotlin.math.cos(phase * Math.PI * 2)) * .5).toFloat()
        shaderMatrix.setTranslate(width * .09f * breath, 0f)
        cyanGlow?.setLocalMatrix(shaderMatrix)
        paint.shader = cyanGlow
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        shaderMatrix.setTranslate(-width * .09f * breath, 0f)
        violetGlow?.setLocalMatrix(shaderMatrix)
        paint.shader = violetGlow
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        panelBounds.set(width * .10f, height * .24f, width * .90f, height * .76f)
        val radius = height * .08f
        paint.shader = glass
        canvas.drawRoundRect(panelBounds, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, height * .002f)
        paint.color = Color.argb(22, 206, 221, 255)
        canvas.drawRoundRect(panelBounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
        // Preserve the previous logo size; render its outline at the actual window resolution.
        val logoWidth = minOf(width * .61f, height * 2.4f).toInt().toFloat()
        val logo = if (ultra) ultraLogo else standardLogo
        val logoHeight = (logoWidth * 98f / if (ultra) 584f else 400f).toInt().toFloat()
        val x = ((width - logoWidth) * .5f).toInt().toFloat()
        val y = ((height - logoHeight) * .5f).toInt().toFloat()
        logoBounds.set(x, y, x + logoWidth, y + logoHeight)
        val layer = canvas.saveLayer(logoBounds, null)
        paint.alpha = 255
        logo.setBounds(x.toInt(), y.toInt(), (x + logoWidth).toInt(), (y + logoHeight).toInt())
        logo.draw(canvas)
        paint.shader = null
        paint.color = Color.WHITE
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.textSize = logoHeight * .48f
        canvas.drawText("BYD CarPlay", x + logoHeight * 1.14f, y + logoHeight * .66f, paint)
        paint.alpha = 255
        shaderMatrix.setTranslate(x - width * .21f + phase * (logoWidth + width * .21f), 0f)
        sheen?.setLocalMatrix(shaderMatrix)
        paint.shader = sheen
        paint.xfermode = accentMask
        canvas.drawRect(logoBounds, paint)
        paint.xfermode = null
        paint.shader = null
        canvas.restoreToCount(layer)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(2f, height * .007f)
        paint.color = Color.argb(150, 91, 211, 255)
        canvas.drawArc(panelBounds, 180f + phase * 360f, 75f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.restoreToCount(content)
    }
}
