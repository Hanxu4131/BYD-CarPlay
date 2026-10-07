package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import java.util.UUID
import java.util.concurrent.Executors

/** Opt-in old-platform adapter. Only the explicit check may offer an ADB key for approval. */
internal object LegacyClusterMap {
    private const val PREFS = "diplay_legacy_cluster"
    const val MIRROR = "legacy-cluster"
    const val TOKEN = LegacyClusterTarget.LAUNCH_TOKEN_EXTRA
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var context: Context? = null
    @Volatile private var generation = 0
    private var pending = false
    private var token: String? = null
    private var expectedDisplay = -1
    private var activity: ClusterMapActivity? = null
    private var listening = false
    private val retryPolicy = LegacyClusterRetryPolicy()
    private var retryTick: Runnable? = null
    private fun scheduleRetry() {
        val ctx = context ?: return
        retryTick?.let(main::removeCallbacks)
        retryTick = null
        if (!retryPolicy.wanted || retryPolicy.completed || !enabled(ctx) || !MapMirrors.streamActive) return
        val epoch = retryPolicy.epoch
        val next = Runnable {
            retryTick = null
            if (epoch != retryPolicy.epoch) return@Runnable
            reconcile()
        }
        retryTick = next
        main.postDelayed(next, 5_000L)
    }
    fun mapActuallyPresented(window: ClusterMapActivity, display: Int, surface: android.view.Surface) {
        val ctx = context ?: return
        if (!owns(window) || !enabled(ctx) || !MapMirrors.streamActive || display != expectedDisplay ||
            display != target(ctx) || !window.hasUsableMapSurface(surface) || !MapMirrors.isPresented(MIRROR, surface)) return
        if (retryPolicy.presented(retryPolicy.epoch, display > 0, surface.isValid, true)) {
            retryTick?.let(main::removeCallbacks); retryTick = null
            event(ctx, "retryStopped actualPresented=true display=$display validSurface=true")
        }
    }
    private val previews = LegacyClusterPreviewState()
    private val streamListener: (Boolean) -> Unit = { active -> if (active) { retryPolicy.start(); reconcile() } else closeWindow() }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", false)
    fun target(context: Context) = prefs(context).getInt("target", 1).coerceIn(1, 4)
    fun restoreL1AfterMap(context: Context) = prefs(context).getBoolean("restore_l1_after_map", false)
    fun l1MiniInstalled(context: Context) = LegacyL1MiniOrder.isInstalled(context)
    fun setRestoreL1AfterMap(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("restore_l1_after_map", enabled).apply()
        LegacyL1MiniOrder.cancel()
        LegacyL1WakeRecovery.cancel()
        if (enabled && l1MiniInstalled(context)) activity?.let { mapSurfaceReady(it) }
    }
    fun mapSurfaceReady(window: ClusterMapActivity) {
        if (!owns(window)) return
        LegacyL1MiniOrder.cancel()
        LegacyL1WakeRecovery.cancel()
        mapStartupComplete(window)
    }
    fun mapSurfaceUnavailable(window: ClusterMapActivity) {
        if (!owns(window)) return
        LegacyL1MiniOrder.cancel()
        LegacyL1WakeRecovery.cancel()
    }
    fun mapStartupComplete(window: ClusterMapActivity) {
        if (!l1MiniInstalled(window)) return
        val stillWanted = {
            owns(window) && enabled(window) && restoreL1AfterMap(window) && target(window) == 1 &&
                (!CameraServices.ENABLED || !com.shilapi.xcertplay.camera.CameraSettings.isEnabled(window)) &&
                window.canRestoreL1AfterStartup()
        }
        if (!stillWanted()) return
        LegacyL1MiniOrder.restore(window, stillWanted)
        LegacyL1WakeRecovery.start(window, stillWanted)
    }
    fun layout(context: Context): LegacyClusterLayout.Settings {
        val prefs = prefs(context)
        return LegacyClusterLayout.sanitize(LegacyClusterLayout.Settings(
            prefs.getInt("horizontal", 50), prefs.getInt("vertical", 50), prefs.getInt("width_percent", 50)))
    }
    fun effectiveLayout(context: Context) = previews.layout ?: layout(context)
    fun keyAreaPreview() = previews.keyArea
    fun effectiveTurnArea(context: Context) = previews.turnArea ?: turnArea(context)
    fun beginTurnAreaPreview(value: LegacyClusterTurnArea.Settings): Long = previews.beginTurnArea(value).also { activity?.updateMapLayout() }
    fun previewTurnArea(id: Long, value: LegacyClusterTurnArea.Settings) {
        if (previews.updateTurnArea(id, value)) activity?.updateMapLayout()
    }
    fun commitTurnAreaPreview(context: Context, id: Long, value: LegacyClusterTurnArea.Settings): Boolean {
        if (!previews.owns(id, LegacyClusterPreviewState.Kind.TURN_AREA)) return false
        setTurnArea(context, value)
        endPreview(id)
        return true
    }
    fun beginLayoutPreview(value: LegacyClusterLayout.Settings): Long = previews.beginLayout(value).also { activity?.updateMapLayout() }
    fun beginKeyAreaPreview(value: LegacyClusterKeyArea.Settings): Long = previews.beginKeyArea(value).also { activity?.updateMapLayout() }
    fun previewLayout(id: Long, value: LegacyClusterLayout.Settings) {
        if (previews.updateLayout(id, value)) activity?.updateMapLayout()
    }
    fun previewKeyArea(id: Long, value: LegacyClusterKeyArea.Settings) {
        if (previews.updateKeyArea(id, value)) activity?.updateMapLayout()
    }
    fun endPreview(id: Long) {
        if (previews.end(id)) activity?.updateMapLayout()
    }
    fun commitLayoutPreview(context: Context, id: Long, value: LegacyClusterLayout.Settings): Boolean {
        if (!previews.owns(id, LegacyClusterPreviewState.Kind.LAYOUT)) return false
        setLayout(context, value)
        endPreview(id)
        return true
    }
    fun commitKeyAreaPreview(context: Context, id: Long, value: LegacyClusterKeyArea.Settings): Boolean {
        if (!previews.owns(id, LegacyClusterPreviewState.Kind.KEY_AREA)) return false
        setKeyArea(context, value)
        endPreview(id)
        return true
    }
    fun setLayout(context: Context, settings: LegacyClusterLayout.Settings) {
        val value = LegacyClusterLayout.sanitize(settings)
        prefs(context).edit().putInt("horizontal", value.horizontal).putInt("vertical", value.vertical)
            .putInt("width_percent", value.widthPercent).apply()
        activity?.updateMapLayout()
    }
    private fun keyPrefs(context: Context) = context.getSharedPreferences("diplay_legacy_cluster_safe_area", Context.MODE_PRIVATE)
    fun keyArea(context: Context): LegacyClusterKeyArea.Settings {
        val prefs = keyPrefs(context)
        return LegacyClusterKeyArea.sanitize(LegacyClusterKeyArea.Settings(
            prefs.getFloat("horizontal", 49.5f).toDouble(), prefs.getFloat("vertical", 45.5f).toDouble(),
            prefs.getInt("scale_percent", 100)))
    }
    fun setKeyArea(context: Context, settings: LegacyClusterKeyArea.Settings) {
        val value = LegacyClusterKeyArea.sanitize(settings)
        keyPrefs(context).edit().putFloat("horizontal", value.horizontal.toFloat())
            .putFloat("vertical", value.vertical.toFloat()).putInt("scale_percent", value.scalePercent).apply()
    }
    private fun turnPrefs(context: Context) = context.getSharedPreferences("diplay_legacy_cluster_turn_area", Context.MODE_PRIVATE)
    fun turnArea(context: Context): LegacyClusterTurnArea.Settings {
        val prefs = turnPrefs(context)
        if (!prefs.getBoolean("initialized", false)) {
            val initial = LegacyClusterTurnArea.fromKeyArea(keyArea(context))
            setTurnArea(context, initial)
            return initial
        }
        return LegacyClusterTurnArea.sanitize(LegacyClusterTurnArea.Settings(
            prefs.getFloat("horizontal", 49.5f).toDouble(), prefs.getFloat("vertical", 29.92f).toDouble(),
            prefs.getInt("scale_percent", 100)))
    }
    fun setTurnArea(context: Context, settings: LegacyClusterTurnArea.Settings) {
        val value = LegacyClusterTurnArea.sanitize(settings)
        turnPrefs(context).edit().putFloat("horizontal", value.horizontal.toFloat())
            .putFloat("vertical", value.vertical.toFloat()).putInt("scale_percent", value.scalePercent)
            .putBoolean("initialized", true).apply()
        activity?.updateMapLayout()
    }
    fun keyInsets(context: Context, width: Int, height: Int): com.shilapi.xcertplay.airplay.AirPlayInsets {
        val insets = LegacyClusterKeyArea.insets(width, height, keyArea(context))
        return com.shilapi.xcertplay.airplay.AirPlayInsets(insets.top, insets.bottom, insets.left, insets.right)
    }
    fun content(context: Context): CarPlayClusterDisplay.Content =
        CarPlayClusterDisplay.Content.entries.firstOrNull { it.name == prefs(context).getString("content", "MAP") }
            ?: CarPlayClusterDisplay.Content.MAP
    fun setContent(context: Context, content: CarPlayClusterDisplay.Content) {
        prefs(context).edit().putString("content", content.name).apply()
    }
    fun report(context: Context): String {
        val prefs = prefs(context)
        val status = prefs.getString("report", "尚未检查。默认 display 1 仅为实验候选，不能证明是仪表屏。")!!
        val events = prefs.getString("events", "").orEmpty()
        val guidance = if (prefs.contains("guidance_available")) {
            "\n有效导航提示：" + if (prefs.getBoolean("guidance_available", false)) "已收到" else "尚未收到"
        } else ""
        return if (events.isEmpty()) status + guidance else "$status$guidance\n最近窗口事件：\n$events"
    }
    fun guidanceState(context: Context, available: Boolean, type: Int?, distance: Int, visible: Boolean) {
        prefs(context).edit().putBoolean("guidance_available", available)
            .putInt("guidance_type", type ?: -1).putInt("guidance_distance", distance)
            .putBoolean("guidance_visible", visible).apply()
    }
    fun l1OrderResult(context: Context, attempt: Int, result: String) {
        prefs(context).edit().putInt("l1_order_attempt", attempt).putString("l1_order_result", result).apply()
    }

    /** Only fixed event names, integer displays and booleans enter this trace; no Intent extras. */
    private fun event(context: Context, message: String) {
        val line = "${android.os.SystemClock.elapsedRealtime()}ms $message"
        Log.i("DiPlay-LegacyCluster", line)
        val prefs = prefs(context)
        val history = (prefs.getString("events", "").orEmpty().lineSequence().filter { it.isNotEmpty() }.toList() + line)
            .takeLast(12).joinToString("\n")
        prefs.edit().putString("events", history).apply()
    }

    fun lifecycle(window: ClusterMapActivity, phase: String, actualDisplay: Int) {
        event(window.applicationContext, "$phase actualDisplay=$actualDisplay expectedDisplay=$expectedDisplay ownsWindow=${activity === window} finishing=${window.isFinishing}")
    }
    fun enable(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        closeWindow()
        if (enabled) { retryPolicy.start(); reconcile() }
    }
    fun select(context: Context, display: Int) {
        require(display in 1..4)
        closeWindow()
        prefs(context).edit().putInt("target", display).apply()
        retryPolicy.start()
        reconcile()
    }
    fun startSession(context: Context) {
        if (this.context == null) retryPolicy.start()
        this.context = context.applicationContext
        if (!listening) { MapMirrors.addStreamListener(streamListener); listening = true }
        reconcile()
    }
    fun stopSession() {
        closeWindow()
        MapMirrors.removeStreamListener(streamListener)
        listening = false
        context = null
    }
    fun closeWindow() {
        CameraInstrumentHost.releaseMap()
        retryPolicy.cancel()
        retryTick?.let(main::removeCallbacks); retryTick = null
        LegacyL1MiniOrder.cancel()
        LegacyL1WakeRecovery.cancel()
        previews.clearAll()
        activity?.clearEditorReference()
        if (pending || activity != null) context?.let {
            event(it, "closeWindow pending=$pending ownsWindow=${activity != null} expectedDisplay=$expectedDisplay enabled=${enabled(it)} streamActive=${MapMirrors.streamActive}")
            saveReport(it, "独立仪表窗口已关闭；当前未验证投屏可见。")
        }
        generation++
        pending = false
        token = null
        expectedDisplay = -1
        activity?.finish()
        activity = null
        MapMirrors.set(MIRROR, null)
    }
    /** Fallback for firmware that exposes display 0 through the Activity's Context. */
    fun verifySystemDisplay(window: ClusterMapActivity, launchToken: String?, contextDisplay: Int,
        done: (Int?) -> Unit) {
        val ctx = context
        val attempt = generation
        val task = window.taskId
        val expected = expectedDisplay
        val matches = token != null && launchToken == token
        val eligible = !window.isFinishing && !window.isDestroyed && ctx != null &&
            LegacyClusterTarget.accepts(expected, expected, enabled(ctx), MapMirrors.streamActive, true, matches)
        event(window.applicationContext, "verifyRequested contextDisplay=$contextDisplay taskId=$task expectedDisplay=$expected eligible=$eligible launchKeyMatch=$matches")
        if (!eligible || ctx == null) { done(null); return }
        worker.execute {
            val verified = runCatching {
                LocalAdb(AdbKeys.load(ctx)).use { adb ->
                    if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) null
                    else LegacyClusterTarget.activityDisplay(adb.shell("dumpsys activity activities").orEmpty(), ctx.packageName, task)
                }
            }.getOrNull()
            main.post {
                val stillEligible = attempt == generation && window.taskId == task && !window.isFinishing && !window.isDestroyed &&
                    token != null && launchToken == token && expected == expectedDisplay && context != null &&
                    enabled(ctx) && MapMirrors.streamActive
                val confirmed = stillEligible && verified == expected && expected > 0
                event(window.applicationContext, "verifyResult contextDisplay=$contextDisplay taskId=$task verifiedSystemDisplay=${verified ?: -1} systemVerified=$confirmed stillEligible=$stillEligible")
                if (!confirmed) {
                    if (expected == 1) CameraInstrumentHost.mapLaunchFailed(window.applicationContext)
                    saveReport(window.applicationContext,
                        "只读 AMS 未唯一确认当前 task $task 位于目标 display $expected；拒绝绘制仪表地图。")
                }
                done(if (confirmed) verified else null)
            }
        }
    }

    fun accept(window: ClusterMapActivity, launchToken: String?, display: Int): Boolean {
        val ctx = context
        val isEnabled = enabled(ctx ?: window.applicationContext)
        val tokenMatches = token != null && launchToken == token
        val accepted = !window.isFinishing && !window.isDestroyed &&
            LegacyClusterTarget.accepts(display, expectedDisplay, isEnabled,
                MapMirrors.streamActive, ctx != null, tokenMatches)
        // launchKeyMatch is a boolean, never the launch credential itself.
        event(window.applicationContext, "accept=${if (accepted) "confirmed" else "rejected"} actualDisplay=$display expectedDisplay=$expectedDisplay enabled=$isEnabled streamActive=${MapMirrors.streamActive} hasContext=${ctx != null} launchKeyMatch=$tokenMatches")
        if (!accepted) {
            saveReport(window.applicationContext, "仪表窗口拒绝接收；请查看下方窗口事件中的屏幕、会话及凭据匹配状态。")
            return false
        }
        activity?.takeIf { it !== window }?.finish()
        activity = window
        saveReport(window.applicationContext, "已确认独立窗口位于 display $display；实车可见、车速保留及车辆投屏保护仍需停车检查。")
        return true
    }
    fun released(window: ClusterMapActivity) {
        event(window.applicationContext, "released ownsWindow=${activity === window} expectedDisplay=$expectedDisplay streamActive=${MapMirrors.streamActive}")
        if (activity === window) {
            LegacyL1MiniOrder.cancel()
            LegacyL1WakeRecovery.cancel()
            previews.clearAll()
            window.clearEditorReference()
            activity = null
            token = null
            expectedDisplay = -1
            generation++
            pending = false
            MapMirrors.set(MIRROR, null)
            context?.let { saveReport(it, "独立仪表窗口已退出，地图表面已释放；当前未验证投屏可见。") }
            if (window.hadUsableMapSurface() && !retryPolicy.completed) {
                retryPolicy.pause()
                event(window, "retryPaused stoppedAfterValidSurface=true; not reclaiming external top window")
            } else scheduleRetry()
        }
    }
    fun owns(window: ClusterMapActivity) = activity === window
    private fun saveReport(ctx: Context, text: String, notify: Boolean = false) {
        prefs(ctx).edit().putString("report", text).apply()
        if (notify) Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }
    fun check(context: Context, done: (String) -> Unit) {
        val ctx = context.applicationContext
        val selected = target(ctx)
        val localDisplays = ClusterMapPresentation.describeDisplays(ctx)
        worker.execute {
            var readyTarget = false
            val message = runCatching {
                LocalAdb(AdbKeys.load(ctx)).use { adb ->
                    val access = adb.connect(mayAsk = true)
                    if (access != LocalAdb.Access.READY) "ADB未就绪：$access；未启动仪表窗口。"
                    else {
                        val dump = adb.shell("dumpsys display").orEmpty()
                        val ids = LegacyClusterTarget.ids(dump)
                        readyTarget = LegacyClusterTarget.allowed(selected, dump)
                        "系统 ${android.os.Build.DISPLAY} / Android ${android.os.Build.VERSION.RELEASE}\n应用可见屏幕：$localDisplays\nADB逻辑屏幕：$ids；目标 display $selected " +
                            if (LegacyClusterTarget.allowed(selected, dump)) "存在。仍需实车确认它对应仪表，未验证车速保留或投屏保护状态。" else "不存在或无法识别，拒绝启动。"
                    }
                }
            }.getOrElse { "ADB检查失败：${it.javaClass.simpleName}；未启动仪表窗口。" }
            main.post {
                saveReport(ctx, message)
                done(message)
                // A successful owner-initiated authorization can resume the already-enabled
                // stream immediately; background launch still connects with mayAsk=false.
                if (readyTarget && selected == target(ctx)) reconcile()
            }
        }
    }
    private fun reconcile() {
        val ctx = context ?: return
        val owner = activity
        when (retryPolicy.action(retryPolicy.epoch, enabled(ctx), MapMirrors.streamActive, pending,
            owner != null, owner?.hasUsableMapSurface() == true)) {
            LegacyClusterRetryPolicy.Action.STOP -> return
            LegacyClusterRetryPolicy.Action.WAIT -> {
                event(ctx, "retryCheck windowReady=${owner != null} surfaceReady=${owner?.hasUsableMapSurface() == true} actualPresented=false; no relaunch")
                scheduleRetry(); return
            }
            LegacyClusterRetryPolicy.Action.LAUNCH -> Unit
        }
        if (owner != null) {
            activity = null
            owner.finish()
            MapMirrors.set(MIRROR, null)
            event(ctx, "retryReplacingWindow validSurface=false")
        }
        event(ctx, "retryAttempt intervalMs=5000 target=${target(ctx)}")
        val selected = target(ctx)
        val attempt = ++generation
        val launchToken = UUID.randomUUID().toString()
        token = launchToken
        expectedDisplay = selected
        pending = true
        if (selected == 1) CameraInstrumentHost.prepareMapWindow()
        worker.execute {
            var launchAccepted = false
            val result = runCatching {
                LocalAdb(AdbKeys.load(ctx)).use { adb ->
                    val access = adb.connect(mayAsk = false)
                    if (access != LocalAdb.Access.READY) return@use "ADB未就绪：$access。请停车后点击检查ADB授权。"
                    if (!LegacyClusterTarget.allowed(selected, adb.shell("dumpsys display").orEmpty()))
                        return@use "目标 display $selected 不存在或无法识别，拒绝启动；不会退回中控屏。"
                    if (attempt != generation || !enabled(ctx)) return@use "已取消启动。"
                    // A cancelled launch still has an invalid token and the activity immediately exits.
                    val command = LegacyClusterTarget.launchCommand(selected, ctx.packageName, launchToken)
                    val output = adb.shell(command)
                    launchAccepted = LegacyClusterTarget.launched(output)
                    val safeOutput = output?.lineSequence()?.mapNotNull(DiagnosticRedactor::redact)
                        ?.joinToString("\n")?.take(500)
                    if (!launchAccepted) "仪表窗口启动失败：${if (output == null) "ADB无返回" else safeOutput?.ifEmpty { "shell未返回可公开错误信息" }}"
                    else "窗口启动命令已接受；等待目标屏窗口确认，尚未验证实车可见。"
                }
            }.getOrElse { "仪表窗口启动失败：${it.javaClass.simpleName}" }
            main.post {
                if (attempt != generation) return@post
                pending = false
                event(ctx, "retryLaunchResult accepted=$launchAccepted windowReady=${activity != null} result=${result.take(500)}")
                saveReport(ctx, if (activity == null) result else "已确认独立窗口位于 display $selected；实车可见、车速保留及车辆投屏保护仍需停车检查。", notify = false)
                scheduleRetry()
                if (!launchAccepted) {
                    if (activity == null) {
                        token = null; expectedDisplay = -1
                        if (selected == 1) CameraInstrumentHost.mapLaunchFailed(ctx)
                    }
                    return@post
                }
                main.postDelayed({
                    if (attempt == generation && activity == null) {
                        token = null
                        if (selected == 1) CameraInstrumentHost.mapLaunchFailed(ctx)
                        saveReport(ctx, "未收到目标 display $selected 的窗口确认；继续每5秒静默重试。", false)
                    }
                }, 5_000)
            }
        }
    }
}
