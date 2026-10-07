package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.media.AudioManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Key filtering only: no windows, nodes or accessibility event content are inspected. */
class NavigationWheelAccessibilityService : AccessibilityService() {
    private val policy = NavigationWheelKeyPolicy()
    private var connected = false
    companion object {
        @Volatile private var connectedOwner: NavigationWheelAccessibilityService? = null
        fun isConnected(): Boolean = connectedOwner != null
    }

    override fun onServiceConnected() {
        connected = true
        connectedOwner = this
        NavigationWheelServiceRecovery.ensure(applicationContext)
        Log.i("DiPlay-NavWheel", "connected keyFilter=${serviceInfo.flags and android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0}")
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = false
        if (connectedOwner === this) connectedOwner = null
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        connected = false
        if (connectedOwner === this) connectedOwner = null
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in setOf(24, 25, 291, 292, 307, 308)) return false
        val started = android.os.SystemClock.uptimeMillis()
        val handled = policy.onKey(
            event.action, event.keyCode, event.deviceId, event.downTime, event.repeatCount,
            event.action == KeyEvent.ACTION_DOWN && connected && NavigationWheelServiceRecovery.enabled(this) && NavigationWheelRoutingState.canRouteNow(),
        ) { delta -> adjustNavigation(delta, event.keyCode) }
        Log.i("DiPlay-NavWheel", "key=${event.keyCode} action=${event.action} handled=$handled elapsedMs=${android.os.SystemClock.uptimeMillis() - started}")
        return handled
    }

    private fun adjustNavigation(delta: Int, key: Int): Boolean {
        val audio = getSystemService(AudioManager::class.java) ?: return false
        var writeAttempted = false
        return try {
            if (!connected) return false
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
