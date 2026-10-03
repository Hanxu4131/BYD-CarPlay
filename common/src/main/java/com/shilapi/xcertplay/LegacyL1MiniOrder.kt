package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.Executors

/** Restore an existing L1 dashboard after our map window; never restart its camera service. */
internal object LegacyL1MiniOrder {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
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

    private fun attempt(app: Context, id: Int, count: Int, stillWanted: () -> Boolean) {
        main.postDelayed({
            if (id != request || !stillWanted()) return@postDelayed
            worker.execute {
                val result = runCatching {
                    LocalAdb(AdbKeys.load(app)).use { adb ->
                        if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) return@use "adb_unavailable"
                        val tasks = adb.shell("dumpsys activity activities") ?: return@use "adb_unavailable"
                        if (!hasExistingDashboard(tasks)) return@use "waiting_for_dashboard"
                        if (id != request || !stillWanted()) return@use "cancelled"
                        // Do not initialize a service twice or force-stop the user's application.
                        val answer = adb.shell("am start-activity --display 1 -f 0x20020000 -n $DASHBOARD")
                            ?: return@use "adb_unavailable"
                        if (answer.contains("Error:") || answer.contains("Exception")) "launch_rejected"
                        else "restored_existing_dashboard"
                    }
                }.getOrElse { "request_failed" }
                main.post {
                    if (id != request) return@post
                    Log.i("DiPlay-L1Order", "attempt=${count + 1} result=$result")
                    LegacyClusterMap.l1OrderResult(app, count + 1, result)
                    if (result == "waiting_for_dashboard" && count < 14 && stillWanted()) {
                        attempt(app, id, count + 1, stillWanted)
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
