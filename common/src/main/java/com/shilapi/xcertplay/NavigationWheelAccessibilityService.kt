package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Key filtering only: no windows, nodes or accessibility event content are inspected. */
open class NavigationWheelAccessibilityService : AccessibilityService() {
    private val policy = NavigationWheelKeyPolicy()
    @Volatile private var connected = false
    companion object {
        @Volatile private var connectedOwner: NavigationWheelAccessibilityService? = null
        private val instances = java.util.concurrent.ConcurrentHashMap<String, NavigationWheelAccessibilityService>()
        // A retained instance alone does not prove its system connection is still alive.
        fun isConnected(): Boolean {
            val owner = connectedOwner ?: return false
            return owner.connected && !NavigationWheelServiceIdentity.switching &&
                owner.javaClass.name == NavigationWheelServiceIdentity.selected(owner) &&
                runCatching { owner.serviceInfo != null }.getOrDefault(false)
        }
        fun suspendSelectedFilter(): Boolean {
            connectedOwner = null
            return instances.values.map { instance ->
                runCatching {
                    if (instance.serviceInfo == null) {
                        instance.connected = false
                        instances.remove(instance.javaClass.name, instance)
                    } else instance.updateFilter(false)
                }.isSuccess
            }.all { it }
        }
        fun refreshSelectedFilter(app: android.content.Context): Boolean {
            if (!suspendSelectedFilter()) return false
            val selected = NavigationWheelServiceIdentity.selected(app)
            val owner = instances[selected] ?: return true
            if (!NavigationWheelOwnerPolicy.allows(owner.javaClass.name, selected,
                    NavigationWheelServiceIdentity.switching, NavigationWheelServiceRecovery.enabled(app))) return true
            if (!owner.connected) return true
            // A retired instance may be Bound before its app callback arrives after a restart.
            // Do not activate the target while that system-side identity still requests filtering.
            val othersClear = runCatching {
                val manager = app.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                    ?: error("AccessibilityManager unavailable")
                val filtered = manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    .mapNotNull { info ->
                        val service = info.resolveInfo?.serviceInfo ?: error("service identity unavailable")
                        val name = android.content.ComponentName(service.packageName, service.name).className
                        if (service.packageName == app.packageName && name in NavigationWheelServiceIdentity.classes &&
                            info.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0) name else null
                    }
                NavigationWheelOwnerPolicy.canActivate(selected, filtered)
            }.getOrDefault(false)
            if (!othersClear) return false
            return runCatching { owner.updateFilter(true); connectedOwner = owner; true }.getOrDefault(false)
        }
    }

    private fun updateFilter(value: Boolean) {
        val info = serviceInfo ?: error("service connection unavailable")
        val flag = android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        info.flags = if (value) info.flags or flag else info.flags and flag.inv()
        serviceInfo = info
    }
    private fun selectedOwner(): Boolean = connectedOwner === this && connected &&
        NavigationWheelOwnerPolicy.allows(javaClass.name, NavigationWheelServiceIdentity.selected(this),
            NavigationWheelServiceIdentity.switching, NavigationWheelServiceRecovery.enabled(this))

    override fun onCreate() {
        super.onCreate()
        Log.i("DiPlay-NavWheel", "service created")
    }

    override fun onServiceConnected() {
        connected = true
        instances[javaClass.name] = this
        // Default XML flags contain no key filter. Only the selected live instance enables it.
        refreshSelectedFilter(applicationContext)
        NavigationWheelServiceRecovery.ensure(applicationContext)
        val filter = runCatching { serviceInfo?.flags?.let {
            it and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0
        } }.getOrNull()
        Log.i("DiPlay-NavWheel", "connected identity=${javaClass.name} owner=${connectedOwner === this} keyFilter=$filter")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i("DiPlay-NavWheel", "unbind identity=${javaClass.name}")
        connected = false
        instances.remove(javaClass.name, this)
        if (connectedOwner === this) connectedOwner = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        Log.i("DiPlay-NavWheel", "destroy identity=${javaClass.name}")
        connected = false
        instances.remove(javaClass.name, this)
        if (connectedOwner === this) connectedOwner = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!selectedOwner() || event.keyCode !in setOf(24, 25, 291, 292, 307, 308)) return false
        val started = android.os.SystemClock.uptimeMillis()
        val handled = policy.onKey(
            event.action, event.keyCode, event.deviceId, event.downTime, event.repeatCount,
            event.action == KeyEvent.ACTION_DOWN && selectedOwner() && NavigationWheelServiceRecovery.enabled(this) && NavigationWheelRoutingState.canRouteNow(),
        ) { delta -> adjustNavigation(delta, event.keyCode) }
        Log.i("DiPlay-NavWheel", "key=${event.keyCode} action=${event.action} handled=$handled elapsedMs=${android.os.SystemClock.uptimeMillis() - started}")
        return handled
    }

    private fun adjustNavigation(delta: Int, key: Int): Boolean {
        val audio = getSystemService(AudioManager::class.java) ?: return false
        var writeAttempted = false
        return try {
            if (!selectedOwner()) return false
            // BYD's private stream 14 rejects the public getStreamMinVolume validator.
            val maximum = audio.getStreamMaxVolume(14)
            val current = audio.getStreamVolume(14)
            val target = navigationWheelTarget(current, maximum, delta) ?: return false
            if (!NavigationWheelRoutingState.canRouteNow()) return false
            // Request the OEM volume panel for stream 14, including an unchanged limit value.
            writeAttempted = true
            audio.setStreamVolume(14, target, AudioManager.FLAG_SHOW_UI)
            val actual = audio.getStreamVolume(14)
            Log.i("DiPlay-NavWheel", "key=$key stream=14 previous=$current target=$target actual=$actual")
            if (actual != target) Log.w("DiPlay-NavWheel", "stream=14 readback mismatch target=$target actual=$actual")
            // At either limit consume the key too, so the OEM cannot change another stream.
            true
        } catch (error: Exception) {
            Log.w("DiPlay-NavWheel", "navigation adjustment failed writeAttempted=$writeAttempted", error)
            // Once a write was attempted it may have taken effect; do not apply this key twice.
            writeAttempted
        }
    }
}
