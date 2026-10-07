package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.View
import android.view.SurfaceView
import android.widget.FrameLayout
import com.shilapi.xcertplay.camera.CameraPixelRect
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** A transparent same-UID display owner, independent of CarPlay and L1. */
object CameraInstrumentHost {
    internal const val TOKEN_EXTRA = "camera_instrument_host_token"
    private const val COMPONENT = "com.shilapi.xcertplay.CameraInstrumentHostActivity"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "diplay-camera-display-owner").apply { isDaemon = true }
    }
    @Volatile private var requested = false
    @Volatile private var ready = false
    @Volatile private var generation = 0L
    private var token: String? = null
    private var activity: CameraInstrumentHostActivity? = null
    private var mapOwner: ClusterMapActivity? = null
    private var mapCanvas: CameraInstrumentCanvas? = null
    private var mapReady = false
    private var mapPending = false
    private var verifyingCanvas = false
    private var verifiedAt = 0L
    private var launching = false
    private var nextLaunch = 0L
    private var retry: ScheduledFuture<*>? = null

    @Synchronized fun isReady(): Boolean = requested && (mapReady || ready)

    @Synchronized fun contextOrNull(): Context? {
        val borrowed = mapOwner
        if (requested && mapReady && borrowed != null && !borrowed.isFinishing && !borrowed.isDestroyed) return borrowed
        return activity?.takeIf { requested && ready && !it.isFinishing && !it.isDestroyed }
    }
    @Synchronized private fun activeCanvas(): CameraInstrumentCanvas? = when (contextOrNull()) {
        mapOwner -> mapCanvas
        activity -> activity?.cameraCanvas()
        else -> null
    }
    @Synchronized fun viewportSize(): Pair<Int, Int>? {
        val root = activeCanvas() ?: return null
        return if (root.width > 0 && root.height > 0) root.width to root.height else null
    }

    /** View ownership operations are main-thread only and require the verified resident canvas. */
    fun attach(view: View, rect: CameraPixelRect): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return activeCanvas()?.attachCamera(view, rect) == true
    }
    fun update(view: View, rect: CameraPixelRect): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        return activeCanvas()?.updateCamera(view, rect) == true
    }
    fun remove(view: View): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        val root = view.parent as? CameraInstrumentCanvas ?: return false
        root.removeView(view)
        return true
    }

    /** Reserve the one instrument task before map launch, without disabling requested cameras. */
    @Synchronized internal fun prepareMapWindow() {
        if (!CameraServices.ENABLED) return
        mapPending = true
        ready = false
        generation++
        token = if (requested) UUID.randomUUID().toString() else null
        retry?.cancel(false); retry = null
        val old = activity
        activity = null
        // LegacyClusterMap calls this on main before submitting its shell launch.
        if (old != null && !old.isDestroyed) { old.clearCameraViews(); old.finish() }
    }
    internal fun borrowMap(window: ClusterMapActivity, parent: FrameLayout) {
        if (!CameraServices.ENABLED) return
        check(Looper.myLooper() == Looper.getMainLooper())
        synchronized(this) {
            mapOwner = window
            mapPending = true
            mapReady = true
            verifiedAt = SystemClock.elapsedRealtime()
            mapCanvas?.let { (it.parent as? FrameLayout)?.removeView(it) }
            mapCanvas = CameraInstrumentCanvas(window) { canvas -> checkCanvas(window, canvas) }.also {
                it.setBackgroundColor(Color.TRANSPARENT)
                parent.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        }
    }
    @Synchronized internal fun mapLaunchFailed(context: Context) {
        if (mapOwner != null) return
        mapPending = false
        if (requested) retry(context.applicationContext, 5000)
    }
    @Synchronized internal fun releaseMap(window: ClusterMapActivity? = null) {
        if (window != null && mapOwner !== window) return
        mapReady = false
        mapPending = false
        mapCanvas?.removeAllViews()
        mapCanvas = null
        val app = mapOwner?.applicationContext
        mapOwner = null
        if (requested && app != null) retry(app, 5000)
    }
    internal fun ownsCanvas(owner: Activity, canvas: CameraInstrumentCanvas): Boolean = checkCanvas(owner, canvas)

    private fun checkCanvas(owner: Activity, canvas: CameraInstrumentCanvas): Boolean {
        val display = canvas.display?.displayId ?: -1
        val valid = synchronized(this) {
            val owns = (mapOwner === owner && mapCanvas === canvas && mapReady) ||
                (activity === owner && ready)
            requested && owns && (display <= 0 || display == 1)
        }
        if (display > 0 && display != 1) {
            val changed = synchronized(this) {
                val wasReady = (mapOwner === owner && mapReady) || (activity === owner && ready)
                if (mapOwner === owner) mapReady = false
                if (activity === owner) ready = false
                wasReady
            }
            if (changed) Log.w("DiPlay-CameraHost", "canvas migrated display=$display; refusing camera draw")
            verifyCanvas(owner, canvas)
        }
        // ViewRoot's non-default display is checked on every traversal; only OEM display-0
        // contexts need a bounded cached AMS proof rather than a shell command per frame.
        if (valid && display <= 0 && SystemClock.elapsedRealtime() - verifiedAt > 1500) verifyCanvas(owner, canvas)
        return valid && (display == 1 || SystemClock.elapsedRealtime() - verifiedAt <= 2500)
    }
    internal fun canvasGeometryChanged(owner: Activity, canvas: CameraInstrumentCanvas) {
        synchronized(this) {
            if (!requested) return
            if (mapCanvas === canvas && mapOwner === owner) mapReady = false
            else if (activity === owner) ready = false
            else return
        }
        verifyCanvas(owner, canvas)
    }
    private fun verifyCanvas(owner: Activity, canvas: CameraInstrumentCanvas) {
        val attempt: Long
        synchronized(this) {
            if (verifyingCanvas || !requested || owner.isFinishing || owner.isDestroyed) return
            verifyingCanvas = true
            attempt = generation
        }
        worker.execute {
            val display = runCatching {
                LocalAdb(AdbKeys.load(owner.applicationContext)).use { adb ->
                    if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) null else
                        actualDisplay(adb.shell("toybox timeout 3 dumpsys activity activities").orEmpty(),
                            owner.packageName, owner.taskId, owner.javaClass.name)
                }
            }.getOrNull()
            main.post {
                val current = synchronized(this) {
                    verifyingCanvas = false
                    requested && generation == attempt && !owner.isFinishing && !owner.isDestroyed &&
                        ((mapOwner === owner && mapCanvas === canvas) || activity === owner)
                }
                if (!current) return@post
                val confirmed = display == 1 && (canvas.display?.displayId ?: 0).let { it <= 0 || it == 1 }
                synchronized(this) {
                    if (mapOwner === owner) mapReady = confirmed else if (activity === owner) ready = confirmed
                    if (confirmed) verifiedAt = SystemClock.elapsedRealtime()
                }
                if (!confirmed) {
                    Log.w("DiPlay-CameraHost", "canvas AMS no longer verifies display1 actual=$display; releasing owner")
                    owner.finish()
                } else canvas.invalidate()
            }
        }
    }

    /** Returns immediately. Shell work is serialized off the UI thread; failures retry after 5s. */
    @Synchronized fun ensure(context: Context): Boolean {
        if (!CameraServices.ENABLED) return false
        val app = context.applicationContext
        if (!requested) {
            requested = true
            generation++
            token = UUID.randomUUID().toString()
        }
        if (mapPending || mapOwner != null) {
            val borrowed = mapOwner
            val root = mapCanvas
            if (!mapReady && borrowed != null && root != null) verifyCanvas(borrowed, root)
            return mapReady
        }
        if (ready || launching) return ready
        val wait = nextLaunch - SystemClock.elapsedRealtime()
        if (wait > 0) { retry(app, wait); return false }
        launching = true
        val attempt = generation
        val launchToken = token ?: return false
        nextLaunch = SystemClock.elapsedRealtime() + 5000
        worker.execute {
            val result = runCatching {
                if (!matches(attempt, launchToken)) return@runCatching false
                LocalAdb(AdbKeys.load(app)).use { adb ->
                    if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use false
                    val displays = adb.shell("toybox timeout 2 dumpsys display") ?: return@use false
                    if (!LegacyClusterTarget.allowed(1, displays) || !matches(attempt, launchToken)) return@use false
                    val packageName = app.packageName
                    if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return@use false
                    val command = "toybox timeout 3 am start-activity --display 1 -f 0x10000000 " +
                        "-n $packageName/$COMPONENT --es $TOKEN_EXTRA $launchToken"
                    LegacyClusterTarget.launched(adb.shell(command))
                }
            }.getOrElse { Log.w("DiPlay-CameraHost", "launch failed", it); false }
            synchronized(this) {
                launching = false
                if (requested && !ready && !mapPending) retry(app, 5000)
            }
            Log.i("DiPlay-CameraHost", "launch submitted=$result generation=$attempt; awaiting AMS target verification")
        }
        return false
    }

    @Synchronized private fun retry(app: Context, delay: Long) {
        retry?.cancel(false)
        retry = worker.schedule({ ensure(app) }, delay.coerceAtLeast(1), TimeUnit.MILLISECONDS)
    }
    @Synchronized private fun matches(attempt: Long, supplied: String?): Boolean =
        requested && generation == attempt && supplied != null && supplied == token

    @Synchronized internal fun accepts(supplied: String?): Boolean = matches(generation, supplied)

    internal fun verify(window: CameraInstrumentHostActivity, supplied: String?) {
        val attempt: Long
        synchronized(this) {
            if (!accepts(supplied)) { window.finish(); return }
            attempt = generation
            activity = window
            ready = false
        }
        val task = window.taskId
        val app = window.applicationContext
        worker.execute {
            val display = runCatching {
                if (!matches(attempt, supplied)) return@runCatching null
                LocalAdb(AdbKeys.load(app)).use { adb ->
                    if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use null
                    actualDisplay(adb.shell("toybox timeout 3 dumpsys activity activities").orEmpty(), app.packageName, task)
                }
            }.getOrNull()
            main.post {
                // A delayed verification for an old token cannot finish/relabel a reused new owner.
                if (!synchronized(this) { activity === window && matches(attempt, supplied) }) return@post
                val accepted = synchronized(this) {
                    val current = activity === window && matches(attempt, supplied) &&
                        window.taskId == task && !window.isFinishing && !window.isDestroyed && display == 1
                    if (activity === window) ready = current
                    if (current) { verifiedAt = SystemClock.elapsedRealtime(); retry?.cancel(false); retry = null }
                    current
                }
                Log.i("DiPlay-CameraHost", "target verified=$accepted display=$display task=$task generation=$attempt")
                if (!accepted) { window.finish(); synchronized(this) { if (matches(attempt, supplied)) retry(app, 5000) } }
            }
        }
    }

    @Synchronized internal fun detached(window: CameraInstrumentHostActivity) {
        if (activity !== window) return
        activity = null
        ready = false
        if (requested && !mapPending) retry(window.applicationContext, 5000)
    }

    /** Invalidates pending launches before finishing our own window on the main thread. */
    @Synchronized fun close() {
        requested = false
        ready = false
        generation++
        token = null
        retry?.cancel(false); retry = null
        val old = activity
        val borrowed = mapCanvas
        activity = null
        main.post { borrowed?.removeAllViews() }
        main.post { if (old != null && !old.isDestroyed) { old.clearCameraViews(); old.finish() } }
    }

    /** Per-display task history only; context.display can incorrectly report 0 on this firmware. */
    internal fun actualDisplay(dump: String, packageName: String, task: Int, component: String = COMPONENT): Int? {
        if (!packageName.matches(Regex("[A-Za-z0-9_.]+")) || task < 0) return null
        val target = "$packageName/$component"
        var display: Int? = null
        val found = mutableListOf<Int>()
        val header = Regex("^Display #(\\d+) \\(activities from top to bottom\\):\\s*$")
        val history = Regex("^\\s{4,}\\* Hist #\\d+: ActivityRecord\\{([^{}]+)\\}\\s*$")
        val recordComponent = Regex("\\bu\\d+\\s+(\\S+)")
        val recordTask = Regex("(?:^|\\s)t(\\d+)(?=\\s|$)")
        for (line in dump.lineSequence()) {
            val heading = header.matchEntire(line)
            if (heading != null) { display = heading.groupValues[1].toIntOrNull(); continue }
            if ((line.isNotEmpty() && !line.first().isWhitespace()) ||
                line.trimStart().startsWith("ActivityStackSupervisor state:") ||
                line.trimStart().startsWith("ResumedActivity:")) display = null
            val current = display ?: continue
            val record = history.matchEntire(line)?.groupValues?.get(1) ?: continue
            if (recordComponent.find(record)?.groupValues?.get(1)?.removeSuffix(",") == target &&
                recordTask.find(record)?.groupValues?.get(1)?.toIntOrNull() == task) found.add(current)
        }
        return found.singleOrNull()
    }
}

/** Transparent canvas with no default content, focus or touch input. */
class CameraInstrumentHostActivity : Activity() {
    private var canvas: CameraInstrumentCanvas? = null

    internal fun cameraCanvas(): CameraInstrumentCanvas? = canvas
    internal fun clearCameraViews() { canvas?.removeAllViews() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        val token = intent.getStringExtra(CameraInstrumentHost.TOKEN_EXTRA)
        if (!CameraInstrumentHost.accepts(token)) { finish(); return }
        canvas = CameraInstrumentCanvas(this) { root -> CameraInstrumentHost.ownsCanvas(this, root) }.also { root ->
            root.setBackgroundColor(Color.TRANSPARENT)
            root.isFocusable = false
            root.isClickable = false
            setContentView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        CameraInstrumentHost.verify(this, token)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val token = intent.getStringExtra(CameraInstrumentHost.TOKEN_EXTRA)
        // A foreign launch cannot close or replace an already valid resident owner.
        if (!CameraInstrumentHost.accepts(token)) {
            CameraInstrumentHost.verify(this, getIntent().getStringExtra(CameraInstrumentHost.TOKEN_EXTRA))
            return
        }
        setIntent(intent)
        CameraInstrumentHost.verify(this, token)
    }
    override fun onDestroy() {
        CameraInstrumentHost.detached(this)
        clearCameraViews()
        canvas = null
        super.onDestroy()
    }
}

/** Size comes from the instrument window, never application DisplayManager's filtered list. */
internal class CameraInstrumentCanvas(context: Context, private val mayDraw: (CameraInstrumentCanvas) -> Boolean) : FrameLayout(context) {
    init {
        isFocusable = false
        isClickable = false
        viewTreeObserver.addOnPreDrawListener {
            visibility = if (mayDraw(this)) View.VISIBLE else View.INVISIBLE
            true // A camera gate must never cancel the shared map window's traversal.
        }
    }
    fun attachCamera(view: View, rect: CameraPixelRect): Boolean {
        val params = parameters(rect) ?: return false
        if (view.parent === this) { view.layoutParams = params; return true }
        if (view.parent != null) return false
        // Camera surfaces above the map SurfaceView, but below this window's UI/OEM windows.
        (view as? SurfaceView)?.setZOrderMediaOverlay(true)
        addView(view, params)
        return true
    }
    fun updateCamera(view: View, rect: CameraPixelRect): Boolean {
        if (view.parent !== this) return false
        view.layoutParams = parameters(rect) ?: return false
        return true
    }
    private fun parameters(rect: CameraPixelRect): FrameLayout.LayoutParams? {
        if (width <= 0 || height <= 0 || rect.width <= 0 || rect.height <= 0) return null
        val left = rect.left.coerceIn(0, width - 1)
        val top = rect.top.coerceIn(0, height - 1)
        return FrameLayout.LayoutParams(rect.width.coerceAtMost(width - left), rect.height.coerceAtMost(height - top)).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            leftMargin = left; topMargin = top
        }
    }
    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0 && (width != oldWidth || height != oldHeight)) {
            Log.i("DiPlay-CameraHost", "canvas layout width=$width height=$height")
            CameraInstrumentHost.canvasGeometryChanged(context as Activity, this)
            mayDraw(this)
        }
    }
}
