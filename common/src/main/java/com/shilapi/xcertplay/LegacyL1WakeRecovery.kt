package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import java.util.concurrent.Executors

/** Opt-in recovery of L1's dead backend; restarting L1 briefly interrupts its cameras. */
internal object LegacyL1WakeRecovery {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val policy = LegacyL1WakeRecoveryPolicy()
    @Volatile private var generation = 0
    private var registered: Context? = null
    private var wanted: (() -> Boolean)? = null
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LegacyL1MiniOrder.cancel()
                    pauseMonitor()
                }
                Intent.ACTION_SCREEN_ON -> {
                    val app = registered ?: return
                    val stillWanted = wanted ?: return
                    if (!stillWanted()) { cancel(); return }
                    LegacyL1MiniOrder.restore(app, stillWanted)
                    beginMonitor(app, stillWanted)
                }
            }
        }
    }
    // The installed 10101 boot receiver uses this exported service with start=boot.
    // Starting MainActivity_v2 would only open the home page, not start the cameras.
    internal const val START = "am start-foreground-service -n l1tech.com.l1mini/.L1BootService --es start boot"
    internal const val STOP = "am force-stop l1tech.com.l1mini"

    fun start(context: Context, stillWanted: () -> Boolean) {
        cancel()
        val app = context.applicationContext
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        if (runCatching { app.registerReceiver(screenChanges, filter) }.isSuccess) {
            registered = app
            wanted = stillWanted
        } else return // Without cancellation on screen-off, do not authorize a restart.
        beginMonitor(app, stillWanted, postMapStartup = true)
    }

    /** Stop only this wake's pending work; retain the listener for the next screen-on. */
    private fun pauseMonitor() {
        generation++
        worker.execute { policy.cancel() }
    }

    private fun beginMonitor(app: Context, stillWanted: () -> Boolean, postMapStartup: Boolean = false) {
        pauseMonitor()
        poll(app, generation, 0, stillWanted, deadline = SystemClock.elapsedRealtime() + 90_000,
            postMapStartup = postMapStartup)
    }

    /** Map close, opt-out and app stop end the complete subscription. */
    fun cancel() {
        pauseMonitor()
        registered?.let { runCatching { it.unregisterReceiver(screenChanges) } }
        registered = null
        wanted = null
    }

    private fun snapshot(app: Context, adb: LocalAdb): LegacyL1WakeRecoveryPolicy.Snapshot? {
        val power = adb.shell("dumpsys power") ?: return null
        val tasks = adb.shell("dumpsys activity activities") ?: return null
        // The installed L1 package can declare a different application process name.
        val processName = LegacyL1WakeRecoveryPolicy.safeProcessName(runCatching {
            app.packageManager.getApplicationInfo("l1tech.com.l1mini", 0).processName
        }.getOrNull())
        val pid = processName?.let { adb.shell("pidof $it") }
        val tcp = adb.shell("cat /proc/net/tcp") ?: return null
        val tcp6 = adb.shell("cat /proc/net/tcp6") ?: return null
        val service = LegacyL1WakeRecoveryPolicy.bootServicePresent(
            adb.shell("dumpsys activity services l1tech.com.l1mini/.L1BootService"))
        return LegacyL1WakeRecoveryPolicy.snapshot(power, tasks, pid, tcp, tcp6).copy(bootService = service)
    }

    private fun poll(app: Context, id: Int, count: Int, stillWanted: () -> Boolean, requested: Boolean = false,
        deadline: Long, postMapStartup: Boolean = false) {
        main.postDelayed({
            if (id != generation) return@postDelayed
            if (!stillWanted()) { cancel(); return@postDelayed }
            if (SystemClock.elapsedRealtime() >= deadline) {
                LegacyClusterMap.l1OrderResult(app, count + 1, "recovery_monitor_timeout")
                return@postDelayed
            }
            worker.execute {
                var again = true
                var didRequest = requested
                var sessionBudgetOnly = false
                val result = runCatching {
                    LocalAdb(AdbKeys.load(app)).use { adb ->
                        if (adb.connect(mayAsk = false) != LocalAdb.Access.READY) {
                            policy.cancel()
                            return@use "recovery_adb_unavailable"
                        }
                        val state = snapshot(app, adb) ?: run {
                            policy.cancel()
                            return@use "recovery_diagnostics_unavailable"
                        }
                        if (requested) {
                            if (!state.awake) return@use "recovery_cancelled"
                            if (state.listening == true) {
                                main.post {
                                    if (id == generation && stillWanted()) LegacyL1MiniOrder.restore(app, stillWanted)
                                }
                                again = false
                                return@use "recovery_backend_listening_after_request"
                            }
                            return@use "recovery_start_unverified"
                        }
                        when (val decision = policy.observe(SystemClock.elapsedRealtime(), state, postMapStartup)) {
                            LegacyL1WakeRecoveryPolicy.Decision.WAIT -> {
                                again = true
                                "recovery_waiting_for_stable_wake"
                            }
                            LegacyL1WakeRecoveryPolicy.Decision.HEALTHY -> {
                                if (!state.dashboard) main.post {
                                    if (id == generation && stillWanted()) LegacyL1MiniOrder.restore(app, stillWanted)
                                }
                                again = false
                                "recovery_backend_listening"
                            }
                            LegacyL1WakeRecoveryPolicy.Decision.SKIP -> {
                                again = false
                                "recovery_already_attempted_this_wake"
                            }
                            LegacyL1WakeRecoveryPolicy.Decision.START_ONLY,
                            LegacyL1WakeRecoveryPolicy.Decision.RECOVER -> {
                                // Re-read all evidence immediately before either service action.
                                val fresh = snapshot(app, adb) ?: run {
                                    policy.cancel()
                                    return@use "recovery_diagnostics_unavailable"
                                }
                                val freshDecision = policy.observe(SystemClock.elapsedRealtime(), fresh, postMapStartup)
                                if (id != generation || !stillWanted() || SystemClock.elapsedRealtime() >= deadline || fresh != state ||
                                    freshDecision != decision ||
                                    !policy.claim(fresh.wake ?: return@use "recovery_conditions_not_met")) {
                                    return@use "recovery_cancelled"
                                }
                                when (LegacyL1WakeRecoveryBudget.claim(app, fresh.wake!!)) {
                                    LegacyL1WakeRecoveryBudget.Result.ALREADY_ATTEMPTED -> {
                                        again = false
                                        return@use "recovery_already_attempted_this_wake"
                                    }
                                    LegacyL1WakeRecoveryBudget.Result.PERSIST_FAILED -> {
                                        again = false
                                        return@use "recovery_budget_persist_failed"
                                    }
                                    LegacyL1WakeRecoveryBudget.Result.SESSION_ONLY -> sessionBudgetOnly = true
                                    LegacyL1WakeRecoveryBudget.Result.PERSISTED -> Unit
                                }
                                if (id != generation || !stillWanted() || SystemClock.elapsedRealtime() >= deadline)
                                    return@use "recovery_cancelled"
                                // Post-map wake-up and a dead process need only START; retain existing tasks.
                                // One shell transaction keeps the restart path from leaving L1 stopped.
                                val startOnly = decision == LegacyL1WakeRecoveryPolicy.Decision.START_ONLY
                                didRequest = true
                                val answer = adb.shell(if (startOnly) START else "$STOP && $START")
                                    ?: return@use "recovery_start_unverified"
                                if (answer.contains("Error", true) || answer.contains("Exception", true)) "recovery_start_rejected"
                                else {
                                    again = true
                                    if (startOnly) "recovery_start_only_requested"
                                    else "recovery_restart_requested_camera_interruption"
                                }
                            }
                        }
                    }
                }.getOrElse { policy.cancel(); "recovery_request_failed" }
                main.post {
                    if (id != generation) return@post
                    val reported = if (sessionBudgetOnly) "${result}_session_budget_only" else result
                    Log.i("DiPlay-L1Recovery", "attempt=${count + 1} result=$reported")
                    val startedNow = didRequest && !requested
                    val withinDeadline = startedNow || SystemClock.elapsedRealtime() < deadline
                    LegacyClusterMap.l1OrderResult(app, count + 1,
                        if (again && !withinDeadline) "recovery_monitor_timeout" else reported)
                    // Stop polling after the bounded startup window, but retain wake broadcasts while the map owns the session.
                    if (!stillWanted()) cancel()
                    else if (again && withinDeadline) {
                        val nextDeadline = if (startedNow) SystemClock.elapsedRealtime() + 60_000 else deadline
                        poll(app, id, count + 1, stillWanted, didRequest, nextDeadline, postMapStartup)
                    }
                }
            }
        }, 2_000)
    }
}
