package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.CornerPathEffect
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

/** A centred stack sized by both window axes, with a square icon and the wordmark aspect preserved. */
internal data class StandardStartupLayout(
    val iconLeft: Float, val iconTop: Float, val iconSize: Float,
    val logoLeft: Float, val logoTop: Float, val logoWidth: Float, val logoHeight: Float,
) {
    companion object {
        fun fit(width: Float, height: Float): StandardStartupLayout {
            val side = minOf(width * .30f, height * .40f)
            val logoWidth = side * 1.48f
            val logoHeight = logoWidth * 98f / 400f
            val gap = side * .38f
            val top = (height - side - gap - logoHeight) * .5f
            return StandardStartupLayout((width - side) * .5f, top, side,
                (width - logoWidth) * .5f, top + side + gap, logoWidth, logoHeight)
        }
    }
}

/** Local cover while a display waits for its first video frame. */
internal class ClusterStartupView(
    context: Context,
    private val minimumShowMs: Long = 1_500L,
    private val fixedAspectRatio: Float? = null,
    private val maximumWaitMs: Long = 5_000L,
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
    private var standardBackground: LinearGradient? = null
    private var standardWarmth: RadialGradient? = null
    private var standardGlass: LinearGradient? = null
    private var standardRim: LinearGradient? = null
    private var standardLayout = StandardStartupLayout.fit(0f, 0f)
    private val standardIconBounds = RectF()
    private val standardRingBounds = RectF()
    private val standardTriangle = Path()
    private var standardTriangleCorners: CornerPathEffect? = null
    private var waiting = false
    val waitingForFrame: Boolean get() = waiting
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
        main.postDelayed(timeout, maximumWaitMs)
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
        configureStandardArtwork(w, h)
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
        if (!ultra) {
            drawStandardArtwork(canvas, width, height)
            canvas.restoreToCount(content)
            return
        }
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

    private fun configureStandardArtwork(width: Float, height: Float) {
        standardLayout = StandardStartupLayout.fit(width, height)
        val layout = standardLayout
        val side = layout.iconSize
        val x = layout.iconLeft
        val y = layout.iconTop
        standardIconBounds.set(x, y, x + side, y + side)
        standardRingBounds.set(x + side * .16f, y + side * .15f,
            x + side * .84f, y + side * .85f)
        standardBackground = LinearGradient(0f, 0f, width, height,
            intArrayOf(Color.rgb(51, 39, 29), Color.rgb(48, 43, 39), Color.rgb(9, 10, 18)),
            floatArrayOf(0f, .48f, 1f), Shader.TileMode.CLAMP)
        standardWarmth = RadialGradient(width * .94f, height * .54f, maxOf(width, height) * .85f,
            Color.argb(70, 145, 137, 123), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        standardGlass = LinearGradient(x, y, x + side, y + side,
            intArrayOf(Color.argb(80, 100, 91, 79), Color.argb(100, 37, 31, 27), Color.argb(90, 87, 79, 68)),
            floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        standardRim = LinearGradient(x, y, x + side, y + side,
            intArrayOf(Color.argb(225, 241, 225, 204), Color.argb(55, 166, 150, 133),
                Color.argb(30, 117, 109, 100), Color.argb(215, 241, 225, 204)),
            floatArrayOf(0f, .25f, .70f, 1f), Shader.TileMode.CLAMP)
        standardTriangle.reset()
        standardTriangle.moveTo(x + side * .43f, y + side * .36f)
        standardTriangle.lineTo(x + side * .69f, y + side * .50f)
        standardTriangle.lineTo(x + side * .43f, y + side * .64f)
        standardTriangle.close()
        standardTriangleCorners = CornerPathEffect(side * .025f)
    }

    private fun drawStandardArtwork(canvas: Canvas, width: Float, height: Float) {
        if (standardLayout.iconSize <= 0f) return
        paint.alpha = 255
        paint.style = Paint.Style.FILL
        paint.shader = standardBackground
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = standardWarmth
        val breath = ((1 - kotlin.math.cos(phase * Math.PI * 2)) * .5).toFloat()
        paint.alpha = (215f + 40f * breath).toInt()
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.alpha = 255
        val layout = standardLayout
        val side = layout.iconSize
        val radius = side * .24f
        paint.shader = standardGlass
        canvas.drawRoundRect(standardIconBounds, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, side * .007f)
        paint.shader = standardRim
        canvas.drawRoundRect(standardIconBounds, radius, radius, paint)
        paint.shader = null
        paint.color = Color.WHITE
        paint.strokeWidth = side * .076f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawArc(standardRingBounds, 40f, 280f, false, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        paint.pathEffect = standardTriangleCorners
        canvas.drawPath(standardTriangle, paint)
        paint.pathEffect = null
        standardLogo.setBounds(layout.logoLeft.toInt(), layout.logoTop.toInt(),
            (layout.logoLeft + layout.logoWidth).toInt(), (layout.logoTop + layout.logoHeight).toInt())
        standardLogo.draw(canvas)
    }

}
