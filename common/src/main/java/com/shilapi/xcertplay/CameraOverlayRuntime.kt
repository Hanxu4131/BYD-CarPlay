package com.shilapi.xcertplay

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.shilapi.xcertplay.camera.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** An overlay owner only. Attaching a source requires independent capture/framing verification. */
class CameraOverlayRuntime(context: Context) : CameraIntegration.Host, AutoCloseable {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val nextLease = AtomicLong()
    private val nextSettingsVersion = AtomicLong()
    private val reconcilePosted = AtomicBoolean(false)
    private val sourceLock = Any()
    @Volatile private var sources: Map<CameraView, SourceLease> = emptyMap()
    @Volatile private var configuration = RuntimeSettings(CameraSettings.Values(), 0L)
    private val settings: CameraSettings.Values get() = configuration.values
    @Volatile private var vehicle = VehicleEvidence(CameraVehicleSignals(null, null, null, null, null, null, null, null), 0L)
    @Volatile private var split: Boolean? = null
    @Volatile private var renderers: Map<CameraView, RenderNode> = emptyMap()
    @Volatile private var failure: String? = null
    @Volatile private var visibleCount = 0
    private val policy = CameraVisibilityPolicy()
    private val windows = mutableMapOf<CameraView, RenderNode>() // main thread only
    private var frontOrder: List<CameraView> = emptyList()
    private var lastDiagnosticMs = 0L
    private val watchdog = object : Runnable {
        override fun run() {
            if (closed.get()) return
            reconcile()
            scheduleWatchdog()
        }
    }

    override fun apply(context: Context, values: CameraSettings.Values) {
        if (closed.get()) return
        configuration = RuntimeSettings(values.sanitized(), nextSettingsVersion.incrementAndGet())
        failure = null
        requestReconcile()
    }

    override fun status(): String {
        if (closed.get()) return "摄像头悬浮窗已关闭"
        failure?.let { return it }
        if (sources.isEmpty()) return "摄像头采集尚未验证"
        if (!settings.enabled) return "摄像头总开关已关闭"
        if (sources.any { (view, lease) -> lease.kind == CameraFrameKind.RAW &&
                !CameraIntegration.canProject(settings.views.getValue(view), 1f) })
            return "原始摄像头缺少有效的已确认校准；窗口未显示"
        return if (visibleCount > 0) "摄像头实时窗口显示中（$visibleCount）" else "已接入验证来源；窗口等待触发条件和实时画面"
    }

    // Immutable volatile snapshot: Integration can call this while holding its own lock.
    override fun frameKind(view: CameraView): CameraFrameKind =
        sources[view]?.takeUnless { it.ended.get() }?.kind ?: CameraFrameKind.UNVERIFIED

    /** Internal adapter entry point, not a UI action that promotes an imported candidate to verified. */
    internal fun attachVerifiedSource(view: CameraView, layout: CameraFrameLayout, kind: CameraFrameKind): SourceLease {
        check(!closed.get()) { "Camera overlay owner is closed" }
        require(layout.payloadBytes() != null) { "Verified source layout is invalid" }
        require(kind != CameraFrameKind.UNVERIFIED) { "Source must be independently verified" }
        val lease = SourceLease(view, layout, kind, nextLease.incrementAndGet())
        synchronized(sourceLock) {
            check(!closed.get())
            sources[view]?.ended?.set(true)
            sources = sources + (view to lease)
        }
        requestReconcile()
        return lease
    }

    internal inner class SourceLease internal constructor(
        val view: CameraView,
        val layout: CameraFrameLayout,
        val kind: CameraFrameKind,
        internal val id: Long,
    ) : AutoCloseable {
        internal val ended = AtomicBoolean(false)
        fun submitFrame(bytes: ByteArray): Boolean = synchronized(sourceLock) {
            if (ended.get() || closed.get() || sources[view] !== this || !layout.accepts(bytes.size)) return@synchronized false
            // No Integration entry point ever takes sourceLock; frameKind uses the volatile snapshot.
            CameraIntegration.publish(view, bytes, layout)
        }
        override fun close() {
            if (!ended.compareAndSet(false, true)) return
            synchronized(sourceLock) { if (sources[view] === this) sources = sources - view }
            requestReconcile()
        }
    }

    /** Adapter-normalized evidence only; this class never reads vehicle SDK or infers OEM units. */
    fun updateVehicleSignals(signals: CameraVehicleSignals) {
        val brakeDepth = signals.brakeDepthPercent
        require(brakeDepth == null || (brakeDepth.isFinite() && brakeDepth in 0f..100f))
        require(listOf(signals.steeringLeftDegrees, signals.steeringRightDegrees).all { it == null || (it.isFinite() && it >= 0f) })
        vehicle = VehicleEvidence(signals, SystemClock.elapsedRealtime())
        requestReconcile()
    }

    /** Actual host geometry/lifecycle evidence. false (including host pause) closes front windows. */
    fun updateSplit(isSplit: Boolean?) {
        split = isSplit
        requestReconcile()
    }

    private fun requestReconcile() {
        if (closed.get()) return
        if (reconcilePosted.compareAndSet(false, true)) main.post {
            reconcilePosted.set(false)
            if (!closed.get()) {
                reconcile()
                scheduleWatchdog()
            }
        }
    }

    private fun scheduleWatchdog() {
        main.removeCallbacks(watchdog)
        if (closed.get()) return
        val now = SystemClock.elapsedRealtime()
        val deadlines = windows.values.filter { it.visible }.map { it.lastFrameMs + FRAME_TIMEOUT_MS }.toMutableList()
        if (windows.values.any { it.visible && it.view.display == CameraDisplay.CENTER }) deadlines += vehicle.timestampMs + SIGNAL_TIMEOUT_MS
        val deadline = deadlines.minOrNull() ?: return
        main.postDelayed(watchdog, (deadline - now).coerceAtLeast(1L))
    }

    private fun reconcile() {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (closed.get()) return
        val snapshot = configuration
        val safe = snapshot.values
        val version = snapshot.version
        val active = sources
        val now = SystemClock.elapsedRealtime()
        val input = vehicle
        val freshSignals = input.timestampMs > 0 && now - input.timestampMs < SIGNAL_TIMEOUT_MS
        val evidence = input.signals.copy(carPlaySplit = split)
        val visibility = policy.update(safe, if (freshSignals) evidence else
            CameraVehicleSignals(null, null, split, null, null, null, null, null))
        windows.keys.toList().forEach { view ->
            val node = windows.getValue(view)
            val lease = active[view]
            if (!safe.enabled || lease?.id != node.leaseId || !sourceUsable(view, lease, safe) ||
                (node.instrumentHost && CameraInstrumentHost.contextOrNull() !== node.gpu.context)) dispose(view)
            else if (node.version != version && !updateConfiguration(node, safe.views.getValue(view), version)) dispose(view)
        }
        if (safe.enabled) active.forEach { (view, lease) ->
            if (!lease.ended.get() && !windows.containsKey(view) && sourceUsable(view, lease, safe)) create(view, lease, safe, version)
        }
        val ready = windows.filter { (view, node) ->
            view in visibility.visibleViews && node.lastFrameMs > 0 && now - node.lastFrameMs < FRAME_TIMEOUT_MS &&
                (view != CameraView.REAR || (freshSignals && (evidence.gearReverse == true ||
                    evidence.brakeDepthPercent?.let { it >= safe.brakeDepthThreshold } == true))) &&
                (view.display != CameraDisplay.CENTER || (freshSignals && split == true && evidence.gearDrive == true))
        }.keys
        windows.forEach { (view, node) ->
            val presentation = cameraWindowPresentation(node.attached, node.lastFrameMs > 0,
                node.lastFrameMs > 0 && now - node.lastFrameMs < FRAME_TIMEOUT_MS, view in ready)
            if (presentation.attached && !node.attached) attachWarm(node)
            if (!presentation.visible) hide(node)
        }
        // Physical order includes alpha-zero warm windows; one visible window never needs reordering.
        val desiredFront = visibility.frontZOrder.filter { it in ready && windows[it]?.attached == true }.asReversed()
        val physicalFront = frontOrder.filter { windows[it]?.attached == true }
        val plan = cameraWarmWindowOrderPlan(physicalFront, desiredFront)
        plan.remove.forEach { windows[it]?.let { node -> detachForReorder(node) } }
        plan.add.forEach { windows[it]?.let { node -> attachWarm(node) } }
        ready.forEach { windows[it]?.let { node -> show(node) } }
        visibleCount = windows.values.count { it.visible }
        if (active.isNotEmpty() && active.keys.all { windows[it]?.attached == true }) failure = null
        if (windows.isNotEmpty() && now - lastDiagnosticMs >= 10000) {
            lastDiagnosticMs = now
            val summary = windows.values.joinToString("; ") { node ->
                "${node.view.key}:attached=${node.attached},visible=${node.visible},draws=${node.gpu.renderedFrameCount()}"
            }
            android.util.Log.i("DiPlay-Camera", "overlay $summary")
        }
    }

    private fun sourceUsable(view: CameraView, lease: SourceLease, safe: CameraSettings.Values): Boolean {
        val config = safe.views.getValue(view)
        return when (lease.kind) {
            CameraFrameKind.UNVERIFIED -> false
            CameraFrameKind.CORRECTED -> config.lens.projectionMode == CameraProjectionMode.CORRECTED
            CameraFrameKind.RAW -> CameraIntegration.canProject(config, 1f)
        }
    }

    private fun create(view: CameraView, lease: SourceLease, safe: CameraSettings.Values, version: Long) {
        val displayId = if (view.display == CameraDisplay.INSTRUMENT) 1 else 0
        try {
            val instrumentHost = displayId == 1
            val display = if (instrumentHost) null else
                (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(displayId)
                    ?: throw IllegalStateException("Display unavailable")
            var displayContext: Context = if (instrumentHost) CameraInstrumentHost.contextOrNull() ?: return
                else app.createDisplayContext(requireNotNull(display))
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            if (!instrumentHost && Build.VERSION.SDK_INT >= 30) displayContext = displayContext.createWindowContext(type, null)
            val manager = if (instrumentHost) null else displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val size = if (instrumentHost) CameraInstrumentHost.viewportSize() ?: return else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") requireNotNull(display).getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
            val config = safe.views.getValue(view)
            val rectangle = config.viewport.toPixelRect(size.first, size.second)
            require(rectangle.width > 0 && rectangle.height > 0)
            if (lease.kind == CameraFrameKind.RAW &&
                !CameraIntegration.canProject(config, rectangle.width.toFloat() / rectangle.height)) {
                failure = "镜头校准与窗口比例不匹配；窗口未显示"
                return
            }
            val gpu = CameraNv12View(displayContext)
            val params = WindowManager.LayoutParams(rectangle.width, rectangle.height, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.START; x = rectangle.left; y = rectangle.top; alpha = 0f
                setTitle("DiPlay camera ${view.key}")
            }
            // Establish actual target dimensions before configuring a raw projection or accepting frames.
            gpu.measure(View.MeasureSpec.makeMeasureSpec(rectangle.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rectangle.height, View.MeasureSpec.EXACTLY))
            gpu.layout(0, 0, rectangle.width, rectangle.height)
            CameraIntegration.configurePreview(gpu, config)
            val node = RenderNode(view, lease.id, version, gpu, manager, params, display, config, instrumentHost)
            windows[view] = node
            renderers = windows.toMap()
            node.subscription = CameraIntegration.subscribe(view) { bytes, layout ->
                if (closed.get() || sources[view]?.id != node.leaseId || node.version != configuration.version ||
                    renderers[view] !== node || !settings.enabled || layout != lease.layout) return@subscribe
                if (gpu.submitFrame(bytes, layout, renderImmediately = node.visible)) {
                    val now = SystemClock.elapsedRealtime()
                    val resumed = node.lastFrameMs == 0L || now - node.lastFrameMs >= FRAME_TIMEOUT_MS
                    node.lastFrameMs = now
                    if (!node.attached || resumed) requestReconcile()
                }
            }
        } catch (error: Exception) {
            android.util.Log.w("DiPlay-Camera", "overlay creation failed display=$displayId type=${error.javaClass.simpleName}")
            failure = if (displayId == 1) "仪表摄像头窗口无法创建（显示屏或悬浮窗权限不可用）"
                else "中控摄像头窗口无法创建（显示屏或悬浮窗权限不可用）"
        }
    }

    private fun updateConfiguration(node: RenderNode, config: CameraViewSettings, version: Long): Boolean {
        if (node.config == config) { node.version = version; return true }
        return try {
            val size = if (node.instrumentHost) CameraInstrumentHost.viewportSize() ?: return false else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") requireNotNull(node.display).getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
            val rectangle = config.viewport.toPixelRect(size.first, size.second)
            require(rectangle.width > 0 && rectangle.height > 0)
            if (sources[node.view]?.kind == CameraFrameKind.RAW &&
                !CameraIntegration.canProject(config, rectangle.width.toFloat() / rectangle.height)) return false
            node.params.width = rectangle.width; node.params.height = rectangle.height
            node.params.x = rectangle.left; node.params.y = rectangle.top
            node.gpu.measure(View.MeasureSpec.makeMeasureSpec(rectangle.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(rectangle.height, View.MeasureSpec.EXACTLY))
            node.gpu.layout(0, 0, rectangle.width, rectangle.height)
            CameraIntegration.configurePreview(node.gpu, config)
            if (node.attached) {
                if (node.instrumentHost) check(CameraInstrumentHost.update(node.gpu, rectangle))
                else requireNotNull(node.manager).updateViewLayout(node.gpu, node.params)
            }
            node.config = config
            node.version = version // Publish after the new projection/viewport is ready.
            true
        } catch (_: Exception) { failure = "摄像头窗口参数更新失败"; false }
    }

    private fun attachWarm(node: RenderNode) {
        if (node.attached || node.lastFrameMs == 0L) return
        try {
            node.params.alpha = 0f
            if (node.instrumentHost) {
                node.gpu.visibility = View.INVISIBLE
                check(CameraInstrumentHost.attach(node.gpu, node.rectangle()))
            } else requireNotNull(node.manager).addView(node.gpu, node.params)
            node.attached = true; node.visible = false
            if (node.view.display == CameraDisplay.CENTER) frontOrder = frontOrder.filter { it != node.view } + node.view
        } catch (_: Exception) { permissionFailure(node) }
    }
    private fun show(node: RenderNode) {
        if (!node.attached || node.visible) return
        try {
            node.params.alpha = 1f
            if (node.instrumentHost) node.gpu.visibility = View.VISIBLE
            else requireNotNull(node.manager).updateViewLayout(node.gpu, node.params)
            node.visible = true
            node.gpu.requestRender() // Consume the latest hidden frame rather than drawing a frozen texture.
        } catch (_: Exception) { permissionFailure(node); hide(node) }
    }
    private fun hide(node: RenderNode) {
        if (!node.attached || !node.visible) return
        try {
            node.params.alpha = 0f
            if (node.instrumentHost) node.gpu.visibility = View.INVISIBLE
            else requireNotNull(node.manager).updateViewLayout(node.gpu, node.params)
            node.visible = false
        } catch (_: Exception) {
            permissionFailure(node)
            detachForReorder(node) // Safety fallback: a failed alpha change must not leave a stale visible window.
        }
    }
    private fun permissionFailure(node: RenderNode) {
        failure = if (node.view.display == CameraDisplay.INSTRUMENT) "仪表摄像头悬浮窗权限不可用" else "中控摄像头悬浮窗权限不可用"
    }
    private fun detachForReorder(node: RenderNode) {
        if (!node.attached) return
        try { if (node.instrumentHost) CameraInstrumentHost.remove(node.gpu) else requireNotNull(node.manager).removeViewImmediate(node.gpu) } catch (_: Exception) { permissionFailure(node) }
        node.attached = false; node.visible = false
        if (node.view.display == CameraDisplay.CENTER) frontOrder = frontOrder.filter { it != node.view }
        // GLSurfaceView detach invalidates pending pixels. Require a new actual frame before showing again.
        node.lastFrameMs = 0L
    }
    private fun dispose(view: CameraView) {
        val node = windows.remove(view) ?: return
        renderers = windows.toMap()
        node.subscription?.close()
        detachForReorder(node)
        node.gpu.release()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(sourceLock) { sources.values.forEach { it.ended.set(true) }; sources = emptyMap() }
        main.post {
            main.removeCallbacks(watchdog)
            windows.keys.toList().forEach { dispose(it) }
            policy.reset(); frontOrder = emptyList(); visibleCount = 0
        }
    }
    private data class VehicleEvidence(val signals: CameraVehicleSignals, val timestampMs: Long)
    private data class RuntimeSettings(val values: CameraSettings.Values, val version: Long)
    private class RenderNode(val view: CameraView, val leaseId: Long, @Volatile var version: Long,
        val gpu: CameraNv12View, val manager: WindowManager?, val params: WindowManager.LayoutParams,
        val display: android.view.Display?, var config: CameraViewSettings, val instrumentHost: Boolean) {
        @Volatile var attached = false
        @Volatile var visible = false
        @Volatile var lastFrameMs = 0L
        var subscription: AutoCloseable? = null
        fun rectangle() = CameraPixelRect(params.x, params.y, params.width, params.height)
    }
    companion object {
        private const val FRAME_TIMEOUT_MS = 1000L
        private const val SIGNAL_TIMEOUT_MS = 1000L
    }
}
