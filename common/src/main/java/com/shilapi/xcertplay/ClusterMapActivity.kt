package com.shilapi.xcertplay

import android.app.Activity
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.shilapi.xcertplay.glance.CarPlayGlance

/** Independent window for stream 111; it never takes ownership of the main CarPlay session. */
class ClusterMapActivity : Activity() {
    private var actualDisplay = -1
    private var mapRoot: FrameLayout? = null
    private var mapView: SurfaceView? = null
    private var mapSurfaceAttached = false
    private var everUsableMapSurface = false
    private var keyReference: View? = null
    private var startupView: ClusterStartupView? = null
    private val mapReadiness: (String, android.view.Surface?, Boolean) -> Unit = { key, surface, ready ->
        if (key == LegacyClusterMap.MIRROR && surface === mapView?.holder?.surface &&
            !isFinishing && !isDestroyed && LegacyClusterMap.owns(this)) {
            if (ready) startupView?.revealMap() else {
                waitForMapFrame()
                LegacyClusterMap.mapSurfaceReady(this)
            }
        }
    }
    private val mapPresented: (String, android.view.Surface?, Boolean) -> Unit = { key, surface, presented ->
        if (key == LegacyClusterMap.MIRROR && presented && surface != null && hasUsableMapSurface(surface)) {
            LegacyClusterMap.mapActuallyPresented(this, actualDisplay, surface)
        }
    }
    private var guidanceView: LegacyClusterGuidanceView? = null
    private val guidanceHandler = Handler(Looper.getMainLooper())
    private var guidanceStarted = false
    private var lastGuidanceDiagnostic: String? = null
    private val guidanceTick = object : Runnable {
        override fun run() {
            if (!guidanceStarted || isFinishing || isDestroyed || !LegacyClusterMap.owns(this@ClusterMapActivity) ||
                mapRoot?.isAttachedToWindow != true) {
                stopGuidanceUpdates()
                return
            }
            val state = CarPlayGlance.snapshot()
            if (startupCoverVisible()) guidanceView?.visibility = View.GONE
            else guidanceView?.render(LegacyClusterMap.content(this@ClusterMapActivity), state)
            val diagnostic = "hasManeuver=${state.connected && state.maneuverType != null} " +
                "type=${state.maneuverType} distance=${state.distanceMeters} startupCover=${startupCoverVisible()} " +
                "guidanceVisible=${guidanceView?.visibility == View.VISIBLE}"
            if (diagnostic != lastGuidanceDiagnostic) {
                Log.i("DiPlay-LegacyGuidance", diagnostic)
                LegacyClusterMap.guidanceState(this@ClusterMapActivity, state.connected && state.maneuverType != null,
                    state.maneuverType, state.distanceMeters, guidanceView?.visibility == View.VISIBLE)
                lastGuidanceDiagnostic = diagnostic
            }
            guidanceHandler.postDelayed(this, 1_000L)
        }
    }
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actualDisplay = getSystemService(WindowManager::class.java).defaultDisplay.displayId
        val launchToken = intent.getStringExtra(LegacyClusterMap.TOKEN)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this)
        setContentView(root)
        // Context/display IDs can be stale after OEM task migration; AMS is authoritative.
        val verify = {
            LegacyClusterMap.verifySystemDisplay(this, launchToken, actualDisplay) { verified ->
                if (!isFinishing && !isDestroyed) {
                    if (verified != null) confirmAndAttach(root, launchToken, verified) else finish()
                }
            }
        }
        if (root.isAttachedToWindow) verify() else root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.removeOnAttachStateChangeListener(this); verify() }
            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }

    private fun confirmAndAttach(root: FrameLayout, launchToken: String?, display: Int) {
        if (isFinishing || isDestroyed || !LegacyClusterMap.accept(this, launchToken, display)) {
            finish()
            return
        }
        actualDisplay = display
        val map = SurfaceView(this)
        map.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                LegacyClusterMap.lifecycle(this@ClusterMapActivity, "surfaceCreated", actualDisplay)
                if (LegacyClusterMap.owns(this@ClusterMapActivity)) {
                    mapSurfaceAttached = true
                    everUsableMapSurface = holder.surface.isValid
                    waitForMapFrame()
                    MapMirrors.set(LegacyClusterMap.MIRROR, holder.surface)
                    LegacyClusterMap.mapSurfaceReady(this@ClusterMapActivity)
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                LegacyClusterMap.lifecycle(this@ClusterMapActivity, "surfaceChanged ${width}x$height", actualDisplay)
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                LegacyClusterMap.lifecycle(this@ClusterMapActivity, "surfaceDestroyed", actualDisplay)
                mapSurfaceAttached = false
                startupView?.dispose()
                LegacyClusterMap.mapSurfaceUnavailable(this@ClusterMapActivity)
                if (LegacyClusterMap.owns(this@ClusterMapActivity)) MapMirrors.set(LegacyClusterMap.MIRROR, null)
            }
        })
        mapRoot = root
        root.viewTreeObserver.addOnPreDrawListener {
            val realDisplay = root.display?.displayId ?: -1
            if (realDisplay > 0 && realDisplay != actualDisplay && !isFinishing) {
                Log.w("DiPlay-LegacyCluster", "map task migrated display=$realDisplay expected=$actualDisplay; release without reclaim")
                finish()
            }
            true
        }
        mapView = map
        val startup = ClusterStartupView(this).apply { setUltra(StartupLogoPreferences.ultra(this@ClusterMapActivity)) }
        startupView = startup
        startup.onHidden = {
            if (!isFinishing && !isDestroyed && LegacyClusterMap.owns(this)) {
                LegacyClusterMap.lifecycle(this, "startupLogoHidden", actualDisplay)
                LegacyClusterMap.mapStartupComplete(this)
                startGuidanceUpdates()
            }
        }
        MapMirrors.addReadinessListener(mapReadiness)
        MapMirrors.addPresentedListener(mapPresented)
        root.addView(map, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        guidanceView = LegacyClusterGuidanceView(this).also {
            root.addView(it, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        }
        root.addView(startup, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { startGuidanceUpdates() }
            override fun onViewDetachedFromWindow(view: View) { stopGuidanceUpdates() }
        })
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMapLayout() }
        // AMS verification may finish after the first layout; apply persisted coordinates now too.
        updateMapLayout()
        root.post { updateMapLayout(); startGuidanceUpdates() }
        if (display == 1) CameraInstrumentHost.borrowMap(this, root)
    }

    private fun waitForMapFrame() {
        guidanceView?.visibility = View.GONE
        keyReference?.visibility = View.GONE
        startupView?.apply {
            setUltra(StartupLogoPreferences.ultra(this@ClusterMapActivity))
            bringToFront()
            waitForFrame()
        }
    }

    fun hasUsableMapSurface(surface: android.view.Surface? = mapView?.holder?.surface): Boolean =
        !isFinishing && !isDestroyed && mapSurfaceAttached && surface != null && surface.isValid && surface === mapView?.holder?.surface
    fun hadUsableMapSurface(): Boolean = everUsableMapSurface

    fun startupCoverVisible(): Boolean = startupView?.visibility == View.VISIBLE
    fun canRestoreL1AfterStartup(): Boolean = mapSurfaceAttached && !startupCoverVisible()

    fun updateMapLayout() {
        if (isFinishing || isDestroyed || !LegacyClusterMap.owns(this)) return
        val root = mapRoot ?: return
        val map = mapView ?: return
        val plan = LegacyClusterLayout.plan(root.width, root.height, LegacyClusterMap.effectiveLayout(this)) ?: return
        val params = map.layoutParams as FrameLayout.LayoutParams
        if (params.width != plan.width || params.height != plan.height ||
            params.leftMargin != plan.left || params.topMargin != plan.top) {
            LegacyClusterMap.lifecycle(this, "layout canvas=${root.width}x${root.height} left=${plan.left} top=${plan.top} width=${plan.width} height=${plan.height}", actualDisplay)
            map.layoutParams = params.apply {
                width = plan.width; height = plan.height
                leftMargin = plan.left; topMargin = plan.top
                gravity = Gravity.TOP or Gravity.LEFT
            }
        }
        startupView?.let { cover ->
            val coverParams = cover.layoutParams as FrameLayout.LayoutParams
            if (coverParams.width != plan.width || coverParams.height != plan.height ||
                coverParams.leftMargin != plan.left || coverParams.topMargin != plan.top) {
                cover.layoutParams = FrameLayout.LayoutParams(plan.width, plan.height, Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = plan.left; topMargin = plan.top
                }
            }
        }
        val keyArea = LegacyClusterMap.keyAreaPreview().takeUnless { startupCoverVisible() }
        guidanceView?.place(LegacyClusterGuidance.placement(plan, LegacyClusterMap.effectiveTurnArea(this)))
        if (keyArea == null) clearEditorReference()
        else {
            val projected = LegacyClusterKeyArea.project(plan, keyArea)
            val reference = keyReference ?: KeyAreaReferenceView(this).also {
                keyReference = it
                root.addView(it, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
            }
            reference.visibility = View.VISIBLE
            val referenceParams = reference.layoutParams as FrameLayout.LayoutParams
            if (referenceParams.width != projected.width || referenceParams.height != projected.height ||
                referenceParams.leftMargin != projected.left || referenceParams.topMargin != projected.top) {
                reference.layoutParams = referenceParams.apply {
                    width = projected.width; height = projected.height
                    leftMargin = projected.left; topMargin = projected.top
                    gravity = Gravity.TOP or Gravity.LEFT
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        guidanceStarted = true
        startGuidanceUpdates()
        LegacyClusterMap.lifecycle(this, "onStart", actualDisplay)
    }
    override fun onResume() {
        super.onResume()
        LegacyClusterMap.lifecycle(this, "onResume", actualDisplay)
    }
    override fun onPause() {
        LegacyClusterMap.lifecycle(this, "onPause", actualDisplay)
        super.onPause()
    }
    fun clearEditorReference() { keyReference?.visibility = View.GONE }

    private fun startGuidanceUpdates() {
        guidanceHandler.removeCallbacks(guidanceTick)
        if (guidanceStarted && mapRoot?.isAttachedToWindow == true && LegacyClusterMap.owns(this)) {
            guidanceHandler.post(guidanceTick)
        }
    }

    private fun stopGuidanceUpdates() {
        guidanceHandler.removeCallbacks(guidanceTick)
        guidanceView?.visibility = View.GONE
    }

    override fun onStop() {
        guidanceStarted = false
        stopGuidanceUpdates()
        clearEditorReference()
        MapMirrors.removeReadinessListener(mapReadiness)
        MapMirrors.removePresentedListener(mapPresented)
        startupView?.dispose()
        LegacyClusterMap.lifecycle(this, "onStop", actualDisplay)
        super.onStop()
        // Hidden or replaced cluster windows must not keep a decoder surface attached.
        CameraInstrumentHost.releaseMap(this)
        LegacyClusterMap.released(this)
        finish()
    }
    override fun onDestroy() {
        guidanceStarted = false
        stopGuidanceUpdates()
        clearEditorReference()
        MapMirrors.removeReadinessListener(mapReadiness)
        MapMirrors.removePresentedListener(mapPresented)
        startupView?.dispose()
        LegacyClusterMap.lifecycle(this, "onDestroy", actualDisplay)
        CameraInstrumentHost.releaseMap(this)
        LegacyClusterMap.released(this)
        super.onDestroy()
    }
}

/** The outline belongs only to DiPlay's own window; it does not alter the streamed video. */
private class KeyAreaReferenceView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 225, 255)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = paint.strokeWidth / 2
        if (width > paint.strokeWidth && height > paint.strokeWidth)
            canvas.drawRect(inset, inset, width - inset, height - inset, paint)
    }
}
