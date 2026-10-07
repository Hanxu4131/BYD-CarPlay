package com.shilapi.xcertplay.camera

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** RAW means explicitly identified sensor pixels; CORRECTED means an identified per-view finished image. */
enum class CameraFrameKind { UNVERIFIED, CORRECTED, RAW }
data class CameraImportResult(val values: CameraSettings.Values, val message: String)

object CameraIntegration {
    interface Host {
        fun apply(context: Context, values: CameraSettings.Values)
        fun status(): String
        fun frameKind(view: CameraView): CameraFrameKind = CameraFrameKind.UNVERIFIED
    }
    private val lock = Any()
    private val runtime = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-config").apply { isDaemon = true } }
    private val frames = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-frames").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private var host: Host? = null
    private var context: Context? = null
    private var values = CameraSettings.Values()
    private var epoch = 0L
    private var activeToken: Long? = null
    private var source: CameraSourceReference? = null
    private var draftReference: ParsedCameraReference? = null
    private val bindings = WeakHashMap<CameraNv12View, CameraViewSettings>()
    private val nodes = mutableMapOf<CameraView, Node>()
    private val framePool = CameraHubBufferPool(16 * 1024 * 1024)
    private const val MAX_FRAME_BYTES = 16L * 1024 * 1024
    private const val REFERENCE_PREFS = "diplay_camera_reference"

    fun status(): String = synchronized(lock) {
        val owner = host ?: return@synchronized "摄像头采集尚未验证"
        val missing = values.views.values.any { it.lens.projectionMode != CameraProjectionMode.CORRECTED && projection(it, 1f) == CameraProjection.None }
        owner.status() + if (missing) "；部分镜头缺少已确认的校准参数" else ""
    }

    fun bindHost(owner: Host?) {
        synchronized(lock) { host = owner; epoch++; context?.let { scheduleApply(it, values, epoch) } }
    }

    /** Runtime configuration only. Settings persistence belongs to the caller's Save action. */
    fun configure(context: Context, values: CameraSettings.Values) = synchronized(lock) {
        val app = context.applicationContext
        this.context = app
        if (source == null) source = loadReference(app)
        draftReference?.let { source = it.source; saveReference(app, it.source) }
        draftReference = null; activeToken = null
        this.values = values.sanitized(); epoch++
        clearPending(); scheduleApply(app, this.values, epoch)
    }

    fun beginPreview(context: Context, values: CameraSettings.Values): Long = synchronized(lock) {
        val app = context.applicationContext; this.context = app
        if (source == null) source = loadReference(app)
        epoch++; activeToken = epoch; draftReference = null
        this.values = values.sanitized(); clearPending(); scheduleApply(app, this.values, epoch)
        epoch
    }

    fun preview(token: Long, values: CameraSettings.Values) = synchronized(lock) {
        if (activeToken != token) return@synchronized
        this.values = values.sanitized(); epoch++; clearPending()
        context?.let { scheduleApply(it, this.values, epoch) }
    }

    fun endPreview(token: Long) = synchronized(lock) {
        if (activeToken != token) return@synchronized
        activeToken = null; draftReference = null; epoch++
        context?.let { values = CameraSettings.load(it); clearPending(); scheduleApply(it, values, epoch) }
    }

    fun sourceReference(): CameraSourceReference? = synchronized(lock) { draftReference?.source ?: source }

    private fun scheduleApply(app: Context, safe: CameraSettings.Values, version: Long) {
        runtime.execute {
            synchronized(lock) {
                if (epoch == version) try { host?.apply(app, safe) } catch (_: Exception) { /* Owner exposes capture failures through status. */ }
            }
        }
    }

    fun importReference(context: Context, uri: Uri, current: CameraSettings.Values): CameraImportResult {
        val token = synchronized(lock) { activeToken }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val n = input.read(chunk); if (n < 0) break
                require(out.size() + n <= CameraReferenceParser.MAX_BYTES) { "参考配置超过 64 KiB" }
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        } ?: error("无法读取参考配置")
        val parsed = CameraReferenceParser.parse(bytes)
        val mapping = mapOf("BSD_FE_LB" to CameraView.LEFT_REAR, "BSD_FE_RB" to CameraView.RIGHT_REAR,
            "BSD_FE_LF" to CameraView.LEFT_FRONT, "BSD_FE_RF" to CameraView.RIGHT_FRONT)
        val updated = current.sanitized().views.toMutableMap()
        parsed.angles.forEach { (key, angles) ->
            val view = mapping.getValue(key); val old = updated.getValue(view)
            updated[view] = old.copy(yawDegrees = angles.yawDegrees, pitchDegrees = angles.pitchDegrees,
                rollDegrees = angles.rollDegrees, lens = old.lens.copy(sourceCameraTag = key,
                    sourceId = parsed.source.cameraId?.toString(), calibrationConfirmed = false))
        }
        synchronized(lock) {
            require(token != null && activeToken == token) { "预览已结束，请重新导入" }
            draftReference = parsed
        }
        return CameraImportResult(current.copy(views = updated).sanitized(), "已读取视角和候选来源；未验证采集或镜头校准，保存后生效")
    }

    /** The callback receives one shared immutable snapshot off the main thread. Do not modify or retain it. */
    fun subscribe(view: CameraView, onFrame: (ByteArray, CameraFrameLayout) -> Unit): AutoCloseable {
        val subscription = Subscription(onFrame)
        synchronized(lock) { nodes.getOrPut(view) { Node() }.subscribers.add(subscription) }
        return AutoCloseable {
            subscription.active.set(false)
            synchronized(lock) {
                nodes[view]?.let { node -> node.subscribers.remove(subscription)
                    if (node.subscribers.isEmpty()) { node.pending?.let { framePool.release(it.bytes) }; node.pending = null }
                }
            }
        }
    }

    /** Caller must supply independently verified framing and explicitly identify the image's interpretation. */
    fun publish(view: CameraView, bytes: ByteArray, layout: CameraFrameLayout): Boolean = synchronized(lock) {
        if (!layout.accepts(bytes.size) || bytes.size.toLong() > MAX_FRAME_BYTES) return@synchronized false
        val node = nodes[view] ?: return@synchronized false
        if (node.subscribers.isEmpty()) return@synchronized false
        when (host?.frameKind(view) ?: CameraFrameKind.UNVERIFIED) {
            CameraFrameKind.UNVERIFIED -> return@synchronized false
            CameraFrameKind.RAW -> if (projection(values.views.getValue(view), 1f) == CameraProjection.None) return@synchronized false
            CameraFrameKind.CORRECTED -> if (values.views.getValue(view).lens.projectionMode != CameraProjectionMode.CORRECTED) return@synchronized false
        }
        // Pending is exclusively owned by this lock; delivery removes it before callbacks run.
        val previous = node.pending
        val copy = if (previous?.bytes?.size == bytes.size) previous.bytes else {
            previous?.let { framePool.release(it.bytes) }
            node.pending = null
            framePool.acquire(bytes.size) ?: return@synchronized false
        }
        System.arraycopy(bytes,0,copy,0,bytes.size)
        node.pending = Frame(copy, layout, epoch)
        if (!node.scheduled) { node.scheduled = true; frames.execute { deliver(view, node) } }
        true
    }

    private fun deliver(view: CameraView, node: Node) {
        val pair = synchronized(lock) {
            val next = node.pending
            node.pending = null
            if (next == null) { node.scheduled = false; return }
            next to node.subscribers.toList()
        }
        try {
            pair.second.forEach { subscriber ->
                val current = synchronized(lock) { subscriber.active.get() && pair.first.epoch == epoch }
                // A released renderer also checks its lease/version. Never copy into GL under the hub lock.
                if (current && subscriber.active.get())
                    try { subscriber.callback(pair.first.bytes, pair.first.layout) } catch (_: Exception) { /* Isolate a released preview. */ }
            }
        } finally {
            synchronized(lock) {
                framePool.release(pair.first.bytes)
                if (node.pending != null) frames.execute { deliver(view, node) } else node.scheduled = false
            }
        }
    }
    private fun clearPending() { nodes.values.forEach { node -> node.pending?.let { framePool.release(it.bytes) }; node.pending = null } }
    private class Subscription(val callback: (ByteArray, CameraFrameLayout) -> Unit) { val active = AtomicBoolean(true) }
    private class Node { val subscribers = mutableListOf<Subscription>(); var pending: Frame? = null; var scheduled = false }
    private data class Frame(val bytes: ByteArray, val layout: CameraFrameLayout, val epoch: Long)

    fun configurePreview(view: CameraNv12View, settings: CameraViewSettings) {
        val safe = settings.sanitized()
        val apply = Runnable {
            val first = synchronized(lock) { val first = !bindings.containsKey(view); bindings[view] = safe; first }
            if (first) view.addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
                val target = changed as? CameraNv12View ?: return@addOnLayoutChangeListener
                val latest = synchronized(lock) { bindings[target] } ?: return@addOnLayoutChangeListener
                applyPreview(target, latest)
            }
            applyPreview(view, safe)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) apply.run() else main.post(apply)
    }

    fun canProject(settings: CameraViewSettings, outputAspect: Float): Boolean =
        projection(settings, outputAspect) != CameraProjection.None

    private fun applyPreview(view: CameraNv12View, settings: CameraViewSettings) {
        val crop = settings.crop
        view.setViewTransform(CameraViewTransform(CameraCrop(crop.left, crop.top, crop.right, crop.bottom),
            settings.panX, settings.panY, settings.zoom, settings.mirrored))
        view.setProjection(if (view.width > 0 && view.height > 0) projection(settings, view.width.toFloat() / view.height) else CameraProjection.None)
    }
    internal fun projection(settings: CameraViewSettings, outputAspect: Float): CameraProjection {
        val lens = settings.lens
        if (!lens.calibrationConfirmed || !outputAspect.isFinite() || outputAspect <= 0) return CameraProjection.None
        fun radians(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()
        val region = lens.sourceRect.let { CameraCrop(it.left, it.top, it.right, it.bottom) }
        val result = when (lens.projectionMode) {
            CameraProjectionMode.CORRECTED -> CameraProjection.None
            CameraProjectionMode.RECTILINEAR -> CameraProjection.Rectilinear(region,
                radians(lens.sourceHorizontalFovDegrees ?: return CameraProjection.None),
                radians(settings.yawDegrees), radians(settings.pitchDegrees), radians(settings.rollDegrees), radians(settings.fovDegrees), outputAspect)
            CameraProjectionMode.FISHEYE_EQUIDISTANT -> CameraProjection.EquidistantFisheye(
                CameraFisheyeLens(lens.lensCenterX ?: return CameraProjection.None, lens.lensCenterY ?: return CameraProjection.None,
                    lens.lensRadiusX ?: return CameraProjection.None, lens.lensRadiusY ?: return CameraProjection.None,
                    radians(lens.fisheyeFovDegrees ?: return CameraProjection.None), region),
                radians(settings.yawDegrees), radians(settings.pitchDegrees), radians(settings.rollDegrees), radians(settings.fovDegrees), outputAspect)
        }
        return result.takeIf { it.valid() } ?: CameraProjection.None
    }
    private fun saveReference(context: Context, source: CameraSourceReference) {
        val editor = context.getSharedPreferences(REFERENCE_PREFS, Context.MODE_PRIVATE).edit().clear()
        listOf("CameraID" to source.cameraId, "SrcW" to source.sourceWidth,
            "SrcH" to source.sourceHeight).forEach { (key, value) -> value?.let { editor.putInt(key, it) } }
        source.channelOrder?.let { editor.putString("ChannelOrder", it) }
        source.cameraMode?.let { editor.putString("CameraMode", it) }
        editor.apply()
    }
    private fun loadReference(context: Context): CameraSourceReference? {
        val p = context.getSharedPreferences(REFERENCE_PREFS, Context.MODE_PRIVATE)
        fun number(key: String): Int? = if (p.contains(key)) p.getInt(key, 0) else null
        val result = CameraSourceReference(number("CameraID"), p.all["CameraMode"]?.let { runCatching { CameraReferenceParser.cameraMode(it) }.getOrNull() }, number("SrcW"), number("SrcH"), p.all["ChannelOrder"]?.let { runCatching { CameraReferenceParser.channelOrder(it) }.getOrNull() })
        return result.takeIf { p.contains("CameraID") || p.contains("CameraMode") || p.contains("SrcW") || p.contains("SrcH") || p.contains("ChannelOrder") }
    }
}

/** Called under the hub lock. Both pending and in-flight slabs count against the same hard bound. */
internal class CameraHubBufferPool(private val maximumBytes: Int) {
    private val free = java.util.ArrayDeque<ByteArray>()
    private val leased = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<ByteArray,Boolean>())
    private var allocated = 0
    init { require(maximumBytes > 0) }
    fun acquire(size: Int): ByteArray? {
        if (size !in 1..maximumBytes) return null
        val iterator = free.iterator()
        while (iterator.hasNext()) {
            val bytes=iterator.next()
            if (bytes.size==size) { iterator.remove(); leased.add(bytes); return bytes }
        }
        while (allocated.toLong()+size>maximumBytes && free.isNotEmpty()) allocated-=free.removeFirst().size
        if (allocated.toLong()+size>maximumBytes) return null
        return ByteArray(size).also { allocated+=size; leased.add(it) }
    }
    fun release(bytes: ByteArray) { if (leased.remove(bytes)) free.addLast(bytes) }
    internal fun retainedBytes(): Int = allocated
}
