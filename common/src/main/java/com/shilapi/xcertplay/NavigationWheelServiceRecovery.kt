package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Recovery follows the navigation wheel switch. Background ADB never requests approval. */
object NavigationWheelServiceRecovery {
    private const val PREFS = "navigation_wheel_service"
    private val worker = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diplay-nav-service-health").apply { isDaemon = true }
    }
    private val policy = NavigationWheelServiceRecoveryPolicy()
    private var started = false
    private var ownServiceStarted = false
    private var ownServiceStartedComponent: ComponentName? = null
    private val fallbackGate = NavigationWheelFallbackGate()
    private var fallbackVerificationScheduled = false
    private val ownStart = NavigationWheelOwnStart()
    private var loggedState: String? = null
    private fun transition(value: String) {
        if (value == loggedState) return
        loggedState = value
        Log.i("DiPlay-NavService", value)
    }
    @Volatile private var status = "正在检查导航滚轮服务"

    fun enabled(context: Context): Boolean = AirPlayPersistence.loadNavigationVolumeWheelEnabled(context)
    fun autoRepair(context: Context): Boolean = enabled(context)
    fun status(): String = status
    fun setEnabled(context: Context, value: Boolean) {
        val app = context.applicationContext
        AirPlayPersistence.saveNavigationVolumeWheelEnabled(app, value)
        changed(app)
    }
    fun setAutoRepair(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("auto_repair", value).apply()
        changed(context)
    }
    fun changed(context: Context) {
        val app = context.applicationContext
        ensure(app)
        worker.execute { policy.wake(); inspect(app) }
    }
    @Synchronized fun ensure(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_ON) worker.execute { policy.wake(); inspect(app) }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        worker.scheduleWithFixedDelay({
            runCatching { inspect(app) }.onFailure {
                status = "导航滚轮服务检查失败，等待重试"
                Log.w("DiPlay-NavService", "health check failed", it)
            }
        }, 0, 2, TimeUnit.SECONDS)
    }

    private fun component(context: Context) = NavigationWheelServiceIdentity.component(context).flattenToString()
    private fun inspect(app: Context) {
        val own = component(app)
        val registered = NavigationWheelServiceList.contains(
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), own)
        val bound = NavigationWheelAccessibilityService.isConnected()
        val requested = AirPlayPersistence.loadNavigationVolumeWheelEnabled(app)
        val keyFilter = if (bound) keyFilterRequested(app, own) else null
        status = when {
            !requested -> "导航滚轮服务已关闭"
            registered && bound && keyFilter == false -> "导航滚轮服务按键过滤未启用，正在检查恢复"
            registered && bound && keyFilter == true -> "导航滚轮服务已连接"
            registered && bound -> "导航滚轮服务已连接，等待按键过滤核验"
            registered -> "导航滚轮服务已启用，等待连接"
            else -> "导航滚轮服务未启用"
        }
        if (!requested || (bound && keyFilter == true)) fallbackGate.clear()
        if (!requested) onMain { NavigationWheelAccessibilityService.refreshSelectedFilter(app) }
        ownStart.observe(requested, bound)
        transition("health requested=$requested registered=$registered connected=$bound keyFilter=$keyFilter")
        releaseOwnServiceStart(app, requested, bound)
        val allowed = requested
        if (policy.observe(SystemClock.elapsedRealtime(), allowed, registered, bound, if (bound) keyFilter ?: false else null)) {
            status = "正在恢复导航滚轮服务"
            if (registered && !bound && startOwnService(app)) {
                status = "正在启动导航滚轮服务，等待系统连接"
                worker.schedule({
                    runCatching {
                        if (enabled(app) && !NavigationWheelAccessibilityService.isConnected()) submitRebind(app)
                    }.onFailure { Log.w("DiPlay-NavService", "delayed own service recovery failed", it) }
                }, 3, TimeUnit.SECONDS)
            } else submitRebind(app)
        }
    }

    internal fun startOwnService(app: Context): Boolean {
        if (!enabled(app) || !ownStart.claim()) return false
        return try {
            // The owning UID may start its protected service without restarting the app.
            val startedComponent = NavigationWheelServiceIdentity.component(app)
            val result = app.startService(Intent().setComponent(startedComponent))
            val submitted = result != null
            if (submitted) { ownServiceStarted = true; ownServiceStartedComponent = startedComponent }
            transition("own-start submitted=$submitted connected=${NavigationWheelAccessibilityService.isConnected()}")
            submitted
        } catch (error: Exception) {
            Log.w("DiPlay-NavService", "own service start unavailable", error)
            false
        }
    }

    internal fun releaseOwnServiceStart(app: Context, requested: Boolean, bound: Boolean) {
        ownStart.observe(requested, bound)
        if (!ownServiceStarted || (requested && !bound)) return
        // Once bound, Android accessibility owns the service's lifetime.
        val startedComponent = ownServiceStartedComponent ?: return
        runCatching { app.stopService(Intent().setComponent(startedComponent)) }
            .onSuccess { ownServiceStarted = false; ownServiceStartedComponent = null }
    }

    private fun submitRebind(app: Context) {
        if (!enabled(app)) return
        val result = recoverOwnEntry(app)
        transition("recovery result=$result connected=${NavigationWheelAccessibilityService.isConnected()}")
        status = when (result) {
            NavigationWheelBindingRecovery.Result.REGISTERED -> "导航滚轮服务已登记，等待系统连接"
            NavigationWheelBindingRecovery.Result.ADB_UNAVAILABLE -> "导航滚轮服务恢复通道不可用，等待冷却后重试"
            NavigationWheelBindingRecovery.Result.NO_SYSTEM_PROOF -> "系统未提供可核验的服务绑定信息，等待重试"
            NavigationWheelBindingRecovery.Result.DISABLED -> "导航滚轮服务已关闭"
            NavigationWheelBindingRecovery.Result.WAIT_BINDING -> "导航滚轮系统绑定未完成，等待再次核验"
            NavigationWheelBindingRecovery.Result.SWITCH_SUBMITTED -> "已切换导航滚轮服务身份，等待实际连接"
            NavigationWheelBindingRecovery.Result.SWITCH_BUDGET_USED -> "本次开机已尝试备用服务，等待系统连接"
            NavigationWheelBindingRecovery.Result.TARGET_BUSY -> "备用导航滚轮服务也未完成连接，停止切换"
            else -> "导航滚轮服务恢复未成功，等待冷却后重试"
        }
    }

    // Flags can detect missing filter registration, not whether OEM/another service delivers each key.
    private fun keyFilterRequested(app: Context, own: String): Boolean? = runCatching {
        val manager = app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?: return@runCatching null
        val info = manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .firstOrNull { item ->
                val service = item.resolveInfo?.serviceInfo ?: return@firstOrNull false
                NavigationWheelServiceList.contains(ComponentName(service.packageName, service.name).flattenToString(), own)
            } ?: return@runCatching null
        info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
    }.getOrNull()

    private fun onMain(action: () -> Boolean): Boolean {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        val done = java.util.concurrent.CountDownLatch(1)
        var success = false
        val accepting = java.util.concurrent.atomic.AtomicBoolean(true)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { if (accepting.get()) success = runCatching(action).getOrDefault(false) }
            finally { done.countDown() }
        }
        val finished = done.await(1, TimeUnit.SECONDS)
        if (!finished) accepting.set(false)
        return finished && success
    }

    private fun readBinding(app: Context, adb: LocalAdb, own: String): NavigationWheelBindingDump.Snapshot {
        val dump = adb.shell("toybox timeout 2 dumpsys accessibility")
        val bound = boundComponents(app) ?: return NavigationWheelBindingDump.Snapshot(false, false)
        return NavigationWheelBindingDump.parse(dump, own, bound)
    }
    private fun boundComponents(app: Context): List<String>? = runCatching {
        val manager = app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            ?: error("AccessibilityManager unavailable")
        manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .map { info ->
                val service = info.resolveInfo?.serviceInfo ?: error("service identity unavailable")
                ComponentName(service.packageName, service.name).flattenToString()
            }
    }.getOrNull()

    private fun unverified(result: NavigationWheelBindingRecovery.Result): NavigationWheelBindingRecovery.Result {
        // An unknown interval cannot count as a continuous stuck binding.
        fallbackGate.clear()
        return result
    }

    private fun recoverOwnEntry(app: Context): NavigationWheelBindingRecovery.Result {
        val selected = NavigationWheelServiceIdentity.selected(app)
        val own = component(app)
        val short = NavigationWheelServiceIdentity.component(app).flattenToShortString()
        if (NavigationWheelAccessibilityService.isConnected()) {
            val filter = keyFilterRequested(app, own) ?: return unverified(NavigationWheelBindingRecovery.Result.NO_SYSTEM_PROOF)
            if (filter || (onMain { NavigationWheelAccessibilityService.refreshSelectedFilter(app) } && keyFilterRequested(app, own) == true))
                return NavigationWheelBindingRecovery.Result.REGISTERED
        }
        return try {
            LocalAdb(AdbKeys.load(app)).use { adb ->
                val access = adb.connect(mayAsk = false)
                transition("local-adb access=$access")
                if (access != LocalAdb.Access.READY) return unverified(NavigationWheelBindingRecovery.Result.ADB_UNAVAILABLE)
                val dump = adb.shell("toybox timeout 2 dumpsys accessibility")
                val bound = boundComponents(app) ?: return unverified(NavigationWheelBindingRecovery.Result.NO_SYSTEM_PROOF)
                val state = NavigationWheelBindingDump.parse(dump, own, bound)
                val pending = NavigationWheelBindingDump.parse(dump, own, emptyList())
                if (!state.verifiable || !pending.verifiable) return unverified(NavigationWheelBindingRecovery.Result.NO_SYSTEM_PROOF)
                if (!enabled(app)) return NavigationWheelBindingRecovery.Result.DISABLED
                val healthy = NavigationWheelAccessibilityService.isConnected() && keyFilterRequested(app, own) == true
                val canSwitch = fallbackGate.observe(SystemClock.elapsedRealtime(), selected, pending.ownPresent, healthy)
                if (healthy) return NavigationWheelBindingRecovery.Result.REGISTERED
                if (pending.ownPresent) {
                    if (NavigationWheelServiceIdentity.used(app)) return NavigationWheelBindingRecovery.Result.SWITCH_BUDGET_USED
                    if (!canSwitch) {
                        if (!fallbackVerificationScheduled) {
                            fallbackVerificationScheduled = true
                            worker.schedule({
                                fallbackVerificationScheduled = false
                                if (enabled(app) && !NavigationWheelAccessibilityService.isConnected()) submitRebind(app)
                            }, 10, TimeUnit.SECONDS)
                        }
                        return NavigationWheelBindingRecovery.Result.WAIT_BINDING
                    }
                    val targetClass = NavigationWheelServiceIdentity.classes.first { it != selected }
                    val target = NavigationWheelServiceIdentity.component(app, targetClass).flattenToString()
                    val targetState = NavigationWheelBindingDump.parse(dump, target, bound)
                    if (!targetState.verifiable) return unverified(NavigationWheelBindingRecovery.Result.NO_SYSTEM_PROOF)
                    if (targetState.ownPresent) return NavigationWheelBindingRecovery.Result.TARGET_BUSY
                    if (!NavigationWheelServiceIdentity.claimSwitch(app, targetClass)) return NavigationWheelBindingRecovery.Result.COMMIT_FAILED
                    // The budget and selected identity are durable before filtering or settings changes.
                    try {
                        if (!onMain { NavigationWheelAccessibilityService.suspendSelectedFilter() })
                            return NavigationWheelBindingRecovery.Result.FILTER_PREP_FAILED
                        if (!enabled(app)) return NavigationWheelBindingRecovery.Result.DISABLED
                        val identities = NavigationWheelServiceIdentity.classes.map {
                            NavigationWheelServiceIdentity.component(app, it).let { item -> item.flattenToString() to item.flattenToShortString() }
                        }
                        val script = NavigationWheelIdentityCommand.build(identities, target)
                            ?: return NavigationWheelBindingRecovery.Result.RESTORE_FAILED
                        val output = adb.shell("toybox timeout 3 sh -c " + quote(NavigationWheelMutationReadback.checked(script)))
                        if (!NavigationWheelIdentityCommand.verified(output, identities.map { it.first }, target)) {
                            // Keep the consumed budget. A bounded retry registers only the selected identity.
                            return NavigationWheelBindingRecovery.Result.RESTORE_FAILED
                        }
                        transition("fallback from=$selected to=$targetClass boot=${NavigationWheelServiceIdentity.boot(app)} registered=true connected=false")
                        return NavigationWheelBindingRecovery.Result.SWITCH_SUBMITTED
                    } finally {
                        NavigationWheelServiceIdentity.finishSwitch()
                        onMain { NavigationWheelAccessibilityService.refreshSelectedFilter(app) }
                    }
                }
                val registered = NavigationWheelServiceList.contains(
                    Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), own)
                NavigationWheelBindingRecovery(
                    own = own, requested = { enabled(app) },
                    snapshot = { readBinding(app, adb, own) },
                    mutateOwn = { add ->
                        // When selected registration is missing, retire the other own identity too.
                        val script = if (add) NavigationWheelIdentityCommand.build(
                            NavigationWheelServiceIdentity.classes.map {
                                NavigationWheelServiceIdentity.component(app, it).let { item -> item.flattenToString() to item.flattenToShortString() }
                            }, own)
                        else NavigationWheelServiceCommand.build(own, short, false, rebind = false)
                        val output = script?.let { adb.shell("toybox timeout 3 sh -c " + quote(NavigationWheelMutationReadback.checked(it))) }
                        NavigationWheelMutationReadback.verified(output, own, add)
                    },
                    now = { SystemClock.elapsedRealtime() }, pause = { Thread.sleep(it) },
                    phase = { transition("recovery phase=$it connected=${NavigationWheelAccessibilityService.isConnected()}") },
                ).run(registered)
            }
        } catch (error: Exception) {
            Log.w("DiPlay-NavService", "own service recovery failed", error)
            unverified(NavigationWheelBindingRecovery.Result.RESTORE_FAILED)
        }
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
