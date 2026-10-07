// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.shilapi.xcertplay.camera.CameraFrameLayout
import com.shilapi.xcertplay.camera.CameraProjectionMode
import com.shilapi.xcertplay.camera.CameraIntegration
import com.shilapi.xcertplay.camera.CameraNv12View
import com.shilapi.xcertplay.camera.CameraSliderMapping
import com.shilapi.xcertplay.camera.CameraSettings
import com.shilapi.xcertplay.camera.CameraView
import com.shilapi.xcertplay.camera.CameraViewSettings
import com.shilapi.xcertplay.camera.CameraViewport
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Five camera views, each with its own saved image and window settings. */
class CameraSettingsActivity : Activity() {
    private val statusHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var statusLabel: TextView? = null
    private val refreshStatus = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            statusLabel?.text = "当前状态：${runCatching { CameraIntegration.status() }.getOrDefault("等待摄像头") }"
            statusHandler.postDelayed(this, 1_000)
        }
    }
    private var original = CameraSettings.Values()
    private var draft = CameraSettings.Values()
    private var editorDraft: CameraSettings.Values? = null
    private val sliderRefreshers = ArrayList<() -> Unit>()
    private var editingView: CameraView? = null
    private var previewToken: Long? = null
    private var frameSubscription: AutoCloseable? = null
    @Volatile private var previewView: CameraNv12View? = null
    private var importStatus: String? = null
    private val frameSeen = AtomicBoolean(false)
    private var committed = false
    private var importPending = false
    private val importExecutor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "camera-config-import").apply { isDaemon = true }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        CameraServices.ensure(applicationContext)
        original = CameraSettings.load(this)
        draft = original
        previewToken = runCatching { CameraIntegration.beginPreview(applicationContext, draft) }
            .onFailure { importStatus = "预览暂不可用：${it.message.orEmpty()}" }
            .getOrNull()
        renderMain()
    }

    override fun onResume() {
        super.onResume()
        previewDraft(editorDraft ?: draft)
        previewView?.onResume()
        statusHandler.removeCallbacks(refreshStatus)
        statusHandler.post(refreshStatus)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatus)
        previewView?.onPause()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (editingView != null) {
            discardViewEditor()
        } else {
            cancelAndFinish()
        }
    }

    override fun onDestroy() {
        statusHandler.removeCallbacks(refreshStatus)
        closeFramePreview()
        if (!committed) endPreview(restoreOriginal = true)
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun renderMain() {
        sliderRefreshers.clear()
        closeFramePreview()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(12))
            setBackgroundColor(BG)
        }
        content.addView(text("摄像头视角", 24, TEXT, bold = true))
        content.addView(text("打开总开关即可显示左右后视镜。各镜头可以实时调整，保存后保留，取消还原。", 14, MUTED).apply {
            setPadding(0, dp(4), 0, dp(12))
        })

        val master = Switch(this).apply {
            text = "启用镜头叠加"
            textSize = 16f
            setTextColor(TEXT)
            isChecked = draft.enabled
            setOnCheckedChangeListener { _, checked ->
                draft = draft.copy(enabled = checked)
                previewDraft(draft)
                if (checked && !cameraPermissionGranted()) requestCameraPermission()
            }
        }
        content.addView(master, LinearLayout.LayoutParams(-1, dp(54)))
        if (!cameraPermissionGranted()) {
            content.addView(button("允许使用摄像头", action = { requestCameraPermission() }))
        }
        if (!Settings.canDrawOverlays(this)) {
            content.addView(button("允许显示摄像头窗口", action = {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }))
        }
        content.addView(numberRow(listOf(
            NumericField("深踩刹车阈值", draft.brakeDepthThreshold) { value ->
                draft = draft.copy(brakeDepthThreshold = value).sanitized(); previewDraft(draft)
            },
            NumericField("转向角度°", draft.steeringThresholdDegrees) { value ->
                draft = draft.copy(steeringThresholdDegrees = value).sanitized(); previewDraft(draft)
            },
        )))
        content.addView(text("前轮：D挡且CarPlay分屏时，转向灯或方向盘任一条件有效就保持显示。正后方：R挡或达到刹车阈值。", 13, MUTED))

        val status = runCatching { CameraIntegration.status() }.getOrDefault("未连接")
        statusLabel = text("当前状态：$status", 13, MUTED).apply { setPadding(0, 0, 0, dp(10)) }
        content.addView(statusLabel)
        CameraView.entries.forEach { view ->
            val settings = draft.views.getValue(view)
            val card = card()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            info.addView(text(viewLabel(view), 17, TEXT, bold = true))
            info.addView(text("${displayLabel(view)} · ${viewportSummary(settings.viewport)}", 12, MUTED))
            row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(button("编辑", action = { openViewEditor(view) }))
            card.addView(row)
            content.addView(card, matchWrap().apply { bottomMargin = dp(8) })
        }
        content.addView(button("导入 L1 配置参考…", action = { openReferencePicker() }).apply {
            gravity = Gravity.CENTER
        }, matchWrap().apply { topMargin = dp(4) })
        (importStatus ?: "") .takeIf { it.isNotBlank() }?.let { message ->
            content.addView(text(message, 13, MUTED).apply { setPadding(0, dp(8), 0, 0) })
        }

        val scroll = ScrollView(this).apply { addView(content) }
        val root = FrameLayout(this).apply { setBackgroundColor(BG) }
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(72) })
        root.addView(actionBar(
            left = "取消" to { cancelAndFinish() },
            right = "保存" to { saveAndFinish() },
        ), FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM))
        setContentView(root)
    }

    private fun cameraPermissionGranted(): Boolean =
        checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermission() {
        if (!cameraPermissionGranted()) requestPermissions(arrayOf(android.Manifest.permission.CAMERA), 51)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 51) return
        previewDraft(editorDraft ?: draft)
        if (editingView == null) renderMain()
    }

    private fun openViewEditor(view: CameraView) {
        if (importPending) return
        editingView = view
        editorDraft = draft
        frameSeen.set(false)
        renderViewEditor(view)
    }

    private fun renderViewEditor(view: CameraView) {
        sliderRefreshers.clear()
        closeFramePreview()
        val starting = requireNotNull(editorDraft).views.getValue(view)
        val rootContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            setBackgroundColor(BG)
        }
        rootContent.addView(text(viewLabel(view), 22, TEXT, bold = true))
        rootContent.addView(text("${displayLabel(view)} · 预览为当前草稿", 13, MUTED).apply {
            setPadding(0, dp(4), 0, dp(10))
        })

        val previewFrame = FrameLayout(this).apply {
            background = rounded(SURFACE, STROKE)
            clipToPadding = true
            clipChildren = true
        }
        val cameraView = CameraNv12View(this).apply {
            CameraIntegration.configurePreview(this, starting)
        }
        previewView = cameraView
        previewFrame.addView(cameraView, FrameLayout.LayoutParams(-1, -1))
        val waiting = text("等待摄像头画面", 14, MUTED).apply { gravity = Gravity.CENTER }
        previewFrame.addView(waiting, FrameLayout.LayoutParams(-1, -1))
        rootContent.addView(previewFrame, LinearLayout.LayoutParams(-1, dp(210)).apply {
            bottomMargin = dp(6)
        })
        val previewNote = text("预览不写入摄像头文件，也不改变 CarPlay 连接。", 12, MUTED)
        rootContent.addView(previewNote)
        importStatus?.takeIf { it.isNotBlank() }?.let { message ->
            rootContent.addView(text(message, 12, MUTED).apply { setPadding(0, dp(4), 0, 0) })
        }
        subscribePreview(view, cameraView) {
            waiting.visibility = View.GONE
            previewNote.text = "已收到画面数据 · 显示效果以实际画面为准"
        }

        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        section(fields, "镜头视角")
        fun current() = requireNotNull(editorDraft).views.getValue(view)
        fields.addView(slider("偏航", CameraSliderMapping.YAW_ROLL, "°", { current().yawDegrees }) { value -> editView(view) { copy(yawDegrees = value) } })
        fields.addView(slider("俯仰", CameraSliderMapping.PITCH, "°", { current().pitchDegrees }) { value -> editView(view) { copy(pitchDegrees = value) } })
        fields.addView(slider("滚转", CameraSliderMapping.YAW_ROLL, "°", { current().rollDegrees }) { value -> editView(view) { copy(rollDegrees = value) } })
        fields.addView(slider("视场角", CameraSliderMapping.FOV, "°", { current().fovDegrees }) { value -> editView(view) { copy(fovDegrees = value) } })
        fields.addView(slider("水平平移", CameraSliderMapping.PAN, "", { current().panX }) { value -> editView(view) { copy(panX = value) } })
        fields.addView(slider("垂直平移", CameraSliderMapping.PAN, "", { current().panY }) { value -> editView(view) { copy(panY = value) } })
        fields.addView(slider("画面放大", CameraSliderMapping.ZOOM, "×", { current().zoom }) { value -> editView(view) { copy(zoom = value) } })
        fields.addView(Switch(this).apply {
            text = "镜像画面"
            setTextColor(TEXT)
            isChecked = starting.mirrored
            setOnCheckedChangeListener { _, checked -> editView(view) { copy(mirrored = checked) } }
        })
        rootContent.addView(fields)
        val advanced = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        section(rootContent, "窗口位置")
        rootContent.addView(slider("窗口左边", CameraSliderMapping.POSITION, "%", { current().viewport.x }) { value -> editViewport(view) { copy(x = value) } })
        rootContent.addView(slider("窗口上边", CameraSliderMapping.POSITION, "%", { current().viewport.y }) { value -> editViewport(view) { copy(y = value) } })
        rootContent.addView(slider("窗口宽度", CameraSliderMapping.SIZE, "%", { current().viewport.width }) { value -> editViewport(view) { copy(width = value) } })
        rootContent.addView(slider("窗口高度", CameraSliderMapping.SIZE, "%", { current().viewport.height }) { value -> editViewport(view) { copy(height = value) } })
        rootContent.addView(text("位置与尺寸按屏幕比例保存；扩大窗口时会自动把位置约束到屏幕内。仪表三个后视窗口分别调整。", 12, MUTED))
        val advancedToggle = button("高级标定 · 展开", action = {})
        advancedToggle.setOnClickListener {
            val show = advanced.visibility != View.VISIBLE
            advanced.visibility = if (show) View.VISIBLE else View.GONE
            advancedToggle.text = if (show) "高级标定 · 收起" else "高级标定 · 展开"
        }
        rootContent.addView(advancedToggle, matchWrap().apply { topMargin = dp(10) })
        val fovStatus = text(fovStatusText(starting.lens.calibrationConfirmed), 12, MUTED)
        advanced.addView(Switch(this).apply {
            text = "我已手动校准或核对可靠镜头资料"
            setTextColor(TEXT)
            isChecked = starting.lens.calibrationConfirmed
            setOnCheckedChangeListener { _, checked ->
                editView(view) { copy(lens = lens.copy(calibrationConfirmed = checked)) }
                fovStatus.text = fovStatusText(checked)
            }
        })
        advanced.addView(fovStatus)
        section(advanced, "镜头投影")
        advanced.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@CameraSettingsActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("已校正画面", "针孔镜头", "等距鱼眼镜头"))
            setSelection(starting.lens.projectionMode.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, item: View?, position: Int, id: Long) {
                    editView(view) { copy(lens = lens.copy(projectionMode = CameraProjectionMode.entries[position])) }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        })
        advanced.addView(text("原始镜头须核对投影、光心、半径与原始视场角；导入L1角度不会自动补齐这些参数。未校准不显示原始画面。", 12, MUTED))
        advanced.addView(numberRow(listOf(
            NumericField("光心X", starting.lens.lensCenterX ?: .5f) { v -> editView(view) { copy(lens = lens.copy(lensCenterX = v)) } },
            NumericField("光心Y", starting.lens.lensCenterY ?: .5f) { v -> editView(view) { copy(lens = lens.copy(lensCenterY = v)) } },
            NumericField("半径X", starting.lens.lensRadiusX ?: .5f) { v -> editView(view) { copy(lens = lens.copy(lensRadiusX = v)) } },
            NumericField("半径Y", starting.lens.lensRadiusY ?: .5f) { v -> editView(view) { copy(lens = lens.copy(lensRadiusY = v)) } },
        )))
        advanced.addView(numberRow(listOf(
            NumericField("鱼眼原始FOV°", starting.lens.fisheyeFovDegrees ?: 180f) { v -> editView(view) { copy(lens = lens.copy(fisheyeFovDegrees = v)) } },
            NumericField("针孔原始FOV°", starting.lens.sourceHorizontalFovDegrees ?: 90f) { v -> editView(view) { copy(lens = lens.copy(sourceHorizontalFovDegrees = v)) } },
        )))
        advanced.addView(numberRow(listOf(
            NumericField("源左边", starting.lens.sourceRect.left) { v -> editView(view) { copy(lens = lens.copy(sourceRect = lens.sourceRect.copy(left = v))) } },
            NumericField("源上边", starting.lens.sourceRect.top) { v -> editView(view) { copy(lens = lens.copy(sourceRect = lens.sourceRect.copy(top = v))) } },
            NumericField("源右边", starting.lens.sourceRect.right) { v -> editView(view) { copy(lens = lens.copy(sourceRect = lens.sourceRect.copy(right = v))) } },
            NumericField("源下边", starting.lens.sourceRect.bottom) { v -> editView(view) { copy(lens = lens.copy(sourceRect = lens.sourceRect.copy(bottom = v))) } },
        )))

        advanced.addView(text("返回上一页可导入L1视角参考；参考文件只读取，不会改写。", 12, MUTED))
        rootContent.addView(advanced)

        val scroll = ScrollView(this).apply { addView(rootContent) }
        val screen = FrameLayout(this).apply { setBackgroundColor(BG) }
        screen.addView(scroll, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(72) })
        screen.addView(actionBar(
            left = "取消本镜头" to { discardViewEditor() },
            right = "应用到草稿" to { acceptViewEditor(view) },
        ), FrameLayout.LayoutParams(-1, dp(68), Gravity.BOTTOM))
        setContentView(screen)
    }

    private fun editView(view: CameraView, change: CameraViewSettings.() -> CameraViewSettings) {
        val values = editorDraft ?: return
        val updated = values.views.toMutableMap().apply {
            this[view] = getValue(view).change().sanitized()
        }
        editorDraft = values.copy(views = updated).sanitized()
        previewView?.let { target ->
            CameraIntegration.configurePreview(target, requireNotNull(editorDraft).views.getValue(view))
        }
        sliderRefreshers.forEach { it() }
        previewDraft(requireNotNull(editorDraft))
    }

    private fun editViewport(view: CameraView, change: CameraViewport.() -> CameraViewport) =
        editView(view) { copy(viewport = viewport.change().sanitized()) }

    private fun acceptViewEditor(view: CameraView) {
        val values = editorDraft ?: return
        draft = values
        editorDraft = null
        editingView = null
        previewDraft(draft)
        renderMain()
    }

    private fun discardViewEditor() {
        editorDraft = null
        editingView = null
        previewDraft(draft)
        renderMain()
    }

    private fun saveAndFinish() {
        val safe = draft.sanitized()
        runCatching {
            CameraSettings.save(this, safe)
            CameraIntegration.configure(applicationContext, safe)
        }.onFailure {
            importStatus = "保存失败：${it.message.orEmpty()}"
            Toast.makeText(this, importStatus, Toast.LENGTH_LONG).show()
            return
        }
        committed = true
        endPreview(restoreOriginal = false)
        setResult(RESULT_OK)
        finish()
    }

    private fun cancelAndFinish() {
        endPreview(restoreOriginal = true)
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun previewDraft(values: CameraSettings.Values) {
        val token = previewToken ?: return
        runCatching { CameraIntegration.preview(token, values) }
            .onFailure { importStatus = "预览暂不可用：${it.message.orEmpty()}" }
    }

    private fun endPreview(restoreOriginal: Boolean) {
        val token = previewToken ?: return
        previewToken = null
        if (restoreOriginal) runCatching { CameraIntegration.preview(token, original) }
        runCatching { CameraIntegration.endPreview(token) }
    }

    private fun subscribePreview(view: CameraView, target: CameraNv12View, onFrameReady: () -> Unit) {
        frameSubscription = runCatching {
            CameraIntegration.subscribe(view) { bytes: ByteArray, layout: CameraFrameLayout ->
                if (previewView === target && target.submitFrame(bytes, layout) && frameSeen.compareAndSet(false, true)) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) onFrameReady()
                    }
                }
            }
        }.onFailure { importStatus = "等待摄像头画面：${it.message.orEmpty()}" }
            .getOrNull()
    }

    private fun closeFramePreview() {
        runCatching { frameSubscription?.close() }
        frameSubscription = null
        previewView?.let { view ->
            runCatching { view.release() }
            (view.parent as? ViewGroup)?.removeView(view)
        }
        previewView = null
        frameSeen.set(false)
    }

    @Suppress("DEPRECATION")
    private fun openReferencePicker() {
        if (importPending || editingView != null) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_IMPORT) }
            .onFailure { Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Activity result is routed through this activity's document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        importPending = true
        importStatus = "正在读取配置参考…"
        if (editingView == null) renderMain() else renderViewEditor(requireNotNull(editingView))
        val base = editorDraft ?: draft
        importExecutor.execute {
            val result = runCatching { CameraIntegration.importReference(applicationContext, uri, base) }
            runOnUiThread {
                importPending = false
                if (isFinishing || isDestroyed || committed) return@runOnUiThread
                result.onSuccess { imported ->
                    importStatus = imported.message
                    if (editingView == null) draft = draft.copy(views = imported.values.views).sanitized()
                    else editorDraft = imported.values.sanitized()
                    previewDraft(editorDraft ?: draft)
                }.onFailure { importStatus = "导入失败：${it.message.orEmpty()}" }
                if (editingView == null) renderMain() else renderViewEditor(requireNotNull(editingView))
            }
        }
    }

    private fun slider(label: String, mapping: CameraSliderMapping, unit: String,
        read: () -> Float, change: (Float) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(2))
        }
        val valueLabel = text("", 14, TEXT)
        row.addView(valueLabel)
        val seek = SeekBar(this).apply {
            max = mapping.steps
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        val refresh = {
            val value = read()
            seek.progress = mapping.progress(value)
            val shown = if (unit == "%") value * 100f else value
            val step = if (unit == "%") mapping.step * 100f else mapping.step
            valueLabel.text = "$label  ${format(shown)}$unit · 步长 ${format(step)}$unit"
        }
        refresh()
        sliderRefreshers.add(refresh)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) { change(mapping.value(progress)); refresh() }
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
        row.addView(seek, LinearLayout.LayoutParams(-1, dp(48)))
        return row
    }

    private data class NumericField(
        val label: String,
        val value: Float,
        val onValue: (Float) -> Unit,
    )

    private fun numberRow(fields: List<NumericField>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        fields.forEach { field ->
            val cell = LinearLayout(this@CameraSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(text(field.label, 12, MUTED))
                val input = EditText(this@CameraSettingsActivity).apply {
                    setTextColor(TEXT)
                    textSize = 15f
                    isSingleLine = true
                    inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or
                        android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    setText(format(field.value))
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = rounded(SURFACE, STROKE)
                    addTextChangedListener(SimpleTextWatcher { raw ->
                        raw.toFloatOrNull()?.takeIf { it.isFinite() }?.let(field.onValue)
                    })
                }
                addView(input, LinearLayout.LayoutParams(-1, dp(46)))
            }
            addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private class SimpleTextWatcher(private val changed: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            changed(s?.toString().orEmpty())
        }
        override fun afterTextChanged(s: android.text.Editable?) = Unit
    }

    private fun actionBar(left: Pair<String, () -> Unit>, right: Pair<String, () -> Unit>) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundColor(BG)
            addView(button(left.first, left.second), LinearLayout.LayoutParams(0, -1, 1f))
            addView(button(right.first, right.second, primary = true),
                LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(10) })
        }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(SURFACE, STROKE)
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(text(title, 16, ACCENT, bold = true).apply {
            setPadding(dp(2), dp(12), 0, dp(4))
        })
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(label: String, action: () -> Unit, primary: Boolean = false) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 15f
        setTextColor(if (primary) BG else TEXT)
        background = rounded(if (primary) ACCENT else SURFACE, STROKE)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(-1, -2)

    private fun viewportSummary(viewport: CameraViewport) =
        "x ${format(viewport.x)} · y ${format(viewport.y)} · ${format(viewport.width)}×${format(viewport.height)}"

    private fun fovStatusText(confirmed: Boolean) = if (confirmed) {
        "视场角可参与投影；请确保参数来自人工校准或可信镜头资料。"
    } else {
        "未确认校准时，视场角仅保存为草稿，不会用于投影。"
    }

    private fun displayLabel(view: CameraView) = if (view.display == com.shilapi.xcertplay.camera.CameraDisplay.INSTRUMENT) "仪表" else "中控"

    private fun viewLabel(view: CameraView) = when (view) {
        CameraView.LEFT_REAR -> "左后视"
        CameraView.RIGHT_REAR -> "右后视"
        CameraView.REAR -> "正后方"
        CameraView.LEFT_FRONT -> "左前视"
        CameraView.RIGHT_FRONT -> "右前视"
    }

    private fun format(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString()
    else "%.3f".format(java.util.Locale.US, value).trimEnd('0').trimEnd('.')

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_IMPORT = 4107
        private val BG = Color.rgb(12, 17, 27)
        private val SURFACE = Color.rgb(25, 33, 47)
        private val STROKE = Color.rgb(47, 60, 79)
        private val TEXT = Color.rgb(238, 242, 248)
        private val MUTED = Color.rgb(157, 170, 189)
        private val ACCENT = Color.rgb(91, 199, 182)
    }
}
