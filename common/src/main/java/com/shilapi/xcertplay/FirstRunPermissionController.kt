package com.shilapi.xcertplay

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.shilapi.xcertplay.hud.BydAdbAccess

/** One compact, skippable sequence. Only the ADB button may request approval for our existing key. */
internal class FirstRunPermissionController(
    private val activity: ComponentActivity,
    private val external: (() -> Unit) -> Unit,
    private val finished: () -> Unit,
) {
    private enum class Step { INTRO, RUNTIME, OVERLAY, ACCESSIBILITY, ADB }
    private val appLabel by lazy { activity.packageManager.getApplicationLabel(activity.applicationInfo).toString() }
    private val prefs by lazy { activity.getSharedPreferences("diplay", Context.MODE_PRIVATE) }
    var active = false
        private set
    private var step = Step.INTRO
    private var waiting = false
    private var closed = false
    private var dialog: AlertDialog? = null
    private val runtime = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (active && step == Step.RUNTIME) { waiting = false; next(Step.OVERLAY) }
    }
    private val special = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (active && waiting) {
            waiting = false
            next(if (step == Step.OVERLAY) Step.ACCESSIBILITY else Step.ADB)
        }
    }

    @Suppress("DEPRECATION")
    fun prepare(saved: Bundle?) {
        // Capture before bootstrap/services create their default preferences.
        val info = runCatching { activity.packageManager.getPackageInfo(activity.packageName, 0) }.getOrNull()
        val configured = prefs.all.keys.any { it != EVALUATED } ||
            activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).all.isNotEmpty()
        val automatic = FirstRunPermissionPolicy.shouldStart(info?.firstInstallTime ?: 0,
            info?.lastUpdateTime ?: 0, configured, prefs.getBoolean(EVALUATED, false))
        // Mark evaluated before launching anything: denial, cancellation, or process death never loops.
        prefs.edit().putBoolean(EVALUATED, true).commit()
        if (saved?.getBoolean(ACTIVE) == true) {
            active = true
            step = Step.entries.getOrNull(saved.getInt(STEP)) ?: Step.INTRO
            waiting = saved.getBoolean(WAITING)
        } else active = automatic
    }

    fun save(out: Bundle) {
        out.putBoolean(ACTIVE, active); out.putInt(STEP, step.ordinal); out.putBoolean(WAITING, waiting && step != Step.ADB)
    }
    fun startManually() {
        if (active) return
        active = true; step = Step.INTRO; waiting = false; show()
    }
    fun resume() { if (active && !waiting && dialog == null) show() }
    fun close() { closed = true; dialog?.dismiss(); dialog = null }

    private fun next(value: Step) { step = value; show() }
    private fun finish() {
        active = false; waiting = false; dialog?.dismiss(); dialog = null
        if (!closed) finished()
    }
    private fun prompt(title: String, message: String, action: String, run: () -> Unit, skip: () -> Unit) {
        dialog = AlertDialog.Builder(activity).setTitle(title).setMessage(message)
            .setPositiveButton(action) { _, _ -> dialog = null; run() }
            .setNegativeButton("跳过") { _, _ -> dialog = null; skip() }
            .setOnCancelListener { dialog = null; finish() }.create().also { it.show() }
    }
    @Suppress("DEPRECATION")
    private fun missingRuntime(): Array<String> {
        val declared = runCatching { activity.packageManager.getPackageInfo(activity.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet() }.getOrNull().orEmpty()
        val granted = declared.filter { activity.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet()
        return FirstRunPermissionPolicy.runtimePermissions(Build.VERSION.SDK_INT, declared, granted).toTypedArray()
    }
    private fun accessibilityAllowed(): Boolean {
        return NavigationWheelServiceIdentity.containsOwn(activity,
            Settings.Secure.getString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
    private fun openSetting(intent: Intent, after: Step) {
        waiting = true
        runCatching { external { special.launch(intent) } }.onFailure {
            waiting = false
            Toast.makeText(activity, "车机没有开放该设置入口，可稍后从系统设置开启", Toast.LENGTH_LONG).show()
            next(after)
        }
    }
    private fun show() {
        if (!active || closed || waiting || dialog != null || activity.isFinishing) return
        when (step) {
            Step.INTRO -> prompt("首次使用权限", "一次检查相机、麦克风、定位及系统支持的附近设备和通知权限。随后按系统要求开启悬浮窗与导航滚轮无障碍服务；各项均可跳过，已有授权会略过。", "开始检查", { next(Step.RUNTIME) }, { finish() })
            Step.RUNTIME -> {
                val missing = missingRuntime()
                if (missing.isEmpty()) next(Step.OVERLAY)
                else {
                    waiting = true
                    runCatching { external { runtime.launch(missing) } }.onFailure {
                        waiting = false; next(Step.OVERLAY)
                    }
                }
            }
            Step.OVERLAY -> if (Settings.canDrawOverlays(activity)) next(Step.ACCESSIBILITY)
                else prompt("悬浮窗 · 2/4", "允许 $appLabel 显示在其他应用上层，用于桌面地图和画面窗口。系统设置返回后继续检查下一项。", "打开系统设置", {
                    openSetting(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")), Step.ACCESSIBILITY)
                }, { next(Step.ACCESSIBILITY) })
            Step.ACCESSIBILITY -> if (accessibilityAllowed()) next(Step.ADB)
                else prompt("导航滚轮 · 3/4", "在系统无障碍设置中选择 $appLabel 导航滚轮服务并开启，用于导航时处理物理滚轮。授权由系统确认。", "打开无障碍设置", {
                    openSetting(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Step.ADB)
                }, { next(Step.ADB) })
            Step.ADB -> probeAdb()
        }
    }
    private fun probeAdb() {
        waiting = true
        Thread({
            val ready = runCatching { BydAdbAccess.check(activity.applicationContext, mayAsk = false).state == BydAdbAccess.State.READY }.getOrDefault(false)
            activity.runOnUiThread {
                if (closed || !active) return@runOnUiThread
                waiting = false
                if (ready) finish()
                else prompt("仪表与氛围灯 · 4/4", "这些车机功能共用本机 ADB 授权，没有单独的系统权限。点击后只请求一次 $appLabel 现有密钥授权；如车机弹出调试确认，请自行确认。", "检查并申请本机授权", { requestAdb() }, { finish() })
            }
        }, "diplay-permission-adb-probe").start()
    }
    private fun requestAdb() {
        waiting = true
        Thread({
            val result = runCatching { BydAdbAccess.check(activity.applicationContext, mayAsk = true).state }.getOrNull()
            activity.runOnUiThread {
                if (closed || !active) return@runOnUiThread
                waiting = false
                val message = when (result) {
                    BydAdbAccess.State.READY -> "本机 ADB 已授权"
                    BydAdbAccess.State.NOT_APPROVED -> "尚未批准，可稍后在权限帮助中再检查"
                    else -> "本机 ADB 暂不可用，可稍后检查车机调试设置"
                }
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
                finish()
            }
        }, "diplay-permission-adb-approval").start()
    }
    private companion object {
        const val EVALUATED = "first_run_permissions_evaluated_v1"
        const val ACTIVE = "first_run_permissions_active"
        const val STEP = "first_run_permissions_step"
        const val WAITING = "first_run_permissions_waiting"
    }
}
