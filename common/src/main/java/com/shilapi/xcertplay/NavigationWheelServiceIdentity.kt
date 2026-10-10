package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/** The second component bypasses one component's stuck system binding; it does not clear it. */
internal object NavigationWheelServiceIdentity {
    const val PRIMARY = "com.shilapi.xcertplay.NavigationWheelAccessibilityService"
    const val FALLBACK = "com.shilapi.xcertplay.NavigationWheelFallbackAccessibilityService"
    val classes = listOf(PRIMARY, FALLBACK)
    private const val PREFS = "navigation_wheel_identity"
    @Volatile private var selection: String? = null
    @Volatile var switching = false
        private set
    @Synchronized fun selected(app: Context): String {
        selection?.let { return it }
        val stored = app.getSharedPreferences(PREFS, 0).getString("selected", null)?.takeIf { it in classes }
        val entries = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val registered = classes.filter { NavigationWheelServiceList.contains(entries, component(app, it).flattenToString()) }
        // Restore a committed selection even if the process died before its settings write.
        val value = NavigationWheelFallbackBudget.restore(stored, registered, PRIMARY, classes)
        selection = value
        return value
    }
    fun component(app: Context, className: String = selected(app)) = ComponentName(app.packageName, className)
    fun containsOwn(app: Context, entries: String?): Boolean = classes.any {
        NavigationWheelServiceList.contains(entries, component(app, it).flattenToString())
    }
    fun boot(app: Context): Int = runCatching {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
    fun used(app: Context): Boolean {
        val current = boot(app)
        return current < 0 || app.getSharedPreferences(PREFS, 0).getInt("switch_boot", -2) == current
    }
    @Synchronized fun claimSwitch(app: Context, target: String): Boolean {
        val current = boot(app)
        val prefs = app.getSharedPreferences(PREFS, 0)
        if (!NavigationWheelFallbackBudget.claim(current, prefs.getInt("switch_boot", -2), selected(app), target, classes) { boot, chosen ->
                prefs.edit().putInt("switch_boot", boot).putString("selected", chosen).commit()
            }) return false
        // The durable commit succeeded before selection and any settings mutation.
        selection = target
        switching = true
        return true
    }
    @Synchronized fun finishSwitch() { switching = false }
}
