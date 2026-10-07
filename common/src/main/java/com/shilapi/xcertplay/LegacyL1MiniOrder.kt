package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.Executors

/** Restore L1 after our map window; create a missing Dashboard only with a healthy backend. */
internal object LegacyL1MiniOrder {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val dashboardPolicy = LegacyL1DashboardRecoveryPolicy()
    @Volatile private var request = 0
    private const val DASHBOARD = "l1tech.com.l1mini/.Dashboard"
    private const val PACKAGE = "l1tech.com.l1mini"

    fun isInstalled(context: Context): Boolean = isInstalled(context.packageManager)

    internal fun isInstalled(packageManager: PackageManager): Boolean = try {
        packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    fun restore(context: Context, stillWanted: () -> Boolean) {
        if (!isInstalled(context)) return
        val app = context.applicationContext
        val id = ++request
        attempt(app, id, 0, stillWanted)
    }

    fun cancel() { request++ }

    internal fun dashboardLaunchable(packageManager: PackageManager): Boolean = runCatching {
        val info = packageManager.getActivityInfo(ComponentName(PACKAGE, "$PACKAGE.Dashboard"), 0)
        info.exported && info.enabled && info.applicationInfo?.enabled == true
    }.getOrDefault(false)

    private fun health(adb: LocalAdb, tasks: String): LegacyL1WakeRecoveryPolicy.Snapshot? {
        val power = adb.shell("dumpsys power") ?: return null
        val tcp = adb.shell("cat /proc/net/tcp") ?: return null
        val tcp6 = adb.shell("cat /proc/net/tcp6") ?: return null
        return LegacyL1WakeRecoveryPolicy.snapshot(power, tasks, null, tcp, tcp6)
    }

    private fun attempt(app: Context, id: Int, count: Int, stillWanted: () -> Boolean, started: Boolean = false) {
        main.postDelayed({
            if (id != request || !stillWanted()) return@postDelayed
            worker.execute {
                var didStart = started
                val result = runCatching {
                    LocalAdb(AdbKeys.load(app)).use { adb ->
                        if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use "adb_unavailable"
                        val tasks = adb.shell("dumpsys activity activities") ?: return@use "adb_unavailable"
                        val existing = hasExistingDashboard(tasks)
                        if (started) return@use if (existing) "dashboard_present_after_start" else "waiting_for_dashboard_after_start"
                        if (!existing) {
                            val state = health(adb, tasks) ?: return@use "waiting_for_dashboard"
                            val launchable = dashboardLaunchable(app.packageManager)
                            when (dashboardPolicy.observe(state, launchable, id == request && stillWanted())) {
                                LegacyL1DashboardRecoveryPolicy.Decision.WAIT -> return@use "waiting_for_dashboard"
                                LegacyL1DashboardRecoveryPolicy.Decision.ALREADY_ATTEMPTED -> return@use "dashboard_already_attempted_this_wake"
                                LegacyL1DashboardRecoveryPolicy.Decision.START -> Unit
                            }
                            val freshTasks = adb.shell("dumpsys activity activities") ?: return@use "waiting_for_dashboard"
                            val fresh = health(adb, freshTasks) ?: return@use "waiting_for_dashboard"
                            if (fresh != state || id != request || !stillWanted() ||
                                !dashboardPolicy.claim(fresh, dashboardLaunchable(app.packageManager),
                                    id == request && stillWanted())) return@use "waiting_for_dashboard"
                            if (id != request || !stillWanted()) return@use "cancelled"
                            didStart = true // An uncertain shell reply still consumes the one-per-wake request.
                        }
                        if (id != request || !stillWanted()) return@use "cancelled"
                        // Do not initialize a service twice or force-stop the user's application.
                        val answer = adb.shell("am start-activity --display 1 -f 0x20020000 -n $DASHBOARD")
                            ?: return@use "adb_unavailable"
                        if (answer.contains("Error:") || answer.contains("Exception")) "launch_rejected"
                        else if (existing) "restored_existing_dashboard" else "dashboard_start_requested"
                    }
                }.getOrElse { "request_failed" }
                main.post {
                    if (id != request) return@post
                    val awaiting = result == "waiting_for_dashboard" || result == "waiting_for_dashboard_after_start" ||
                        result == "dashboard_start_requested" || (didStart && result == "adb_unavailable")
                    val reported = if (awaiting && count >= 14) {
                        if (didStart) "dashboard_confirmation_timeout" else "dashboard_wait_timeout"
                    } else result
                    Log.i("DiPlay-L1Order", "attempt=${count + 1} result=$reported")
                    LegacyClusterMap.l1OrderResult(app, count + 1, reported)
                    if (awaiting && count < 14 && stillWanted()) {
                        attempt(app, id, count + 1, stillWanted, didStart)
                    }
                }
            }
        }, if (count == 0) 0 else 2_000)
    }

    /** An ActivityRecord, rather than historical Intent text, is required to restore a task. */
    internal fun hasExistingDashboard(tasks: String): Boolean = tasks.lineSequence().any {
        it.contains("ActivityRecord{") && it.contains("$DASHBOARD,")
    }
}
