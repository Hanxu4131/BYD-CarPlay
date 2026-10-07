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

    private fun component(context: Context) = ComponentName(context, NavigationWheelAccessibilityService::class.java).flattenToString()
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
            registered && bound -> "导航滚轮服务已连接"
            registered -> "导航滚轮服务已启用，等待连接"
            else -> "导航滚轮服务未启用"
        }
        val allowed = requested
        if (policy.observe(SystemClock.elapsedRealtime(), allowed, registered, bound, keyFilter)) {
            status = "正在恢复导航滚轮服务"
            val success = changeOwnEntry(app, true, rebind = true)
            Log.i("DiPlay-NavService", "automatic rebind submitted=$success enabled=$registered connected=$bound")
            if (!success) status = "导航滚轮服务恢复未成功，等待冷却后重试"
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

    private fun changeOwnEntry(app: Context, enable: Boolean, rebind: Boolean): Boolean {
        // Read inside each shell mutation, so entries added by other apps between rebind steps survive.
        val own = component(app)
        if (!own.matches(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.]+"))) return false
        val short = ComponentName(app, NavigationWheelAccessibilityService::class.java).flattenToShortString()
        val script = NavigationWheelServiceCommand.build(own, short, enable, rebind) ?: return false
        return try {
            LocalAdb(AdbKeys.load(app)).use { adb ->
                if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return false
                val result = adb.shell("toybox timeout 3 sh -c " + quote(script)) ?: return false
                val actual = result.lineSequence().lastOrNull().orEmpty()
                NavigationWheelServiceList.contains(actual, own) == enable
            }
        } catch (error: Exception) {
            Log.w("DiPlay-NavService", "own service update failed", error)
            false
        }
    }
    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
