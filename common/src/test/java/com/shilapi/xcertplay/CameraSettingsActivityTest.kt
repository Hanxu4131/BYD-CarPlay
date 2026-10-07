package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import com.shilapi.xcertplay.camera.CameraSliderMapping
import com.shilapi.xcertplay.camera.CameraSettings
import com.shilapi.xcertplay.camera.CameraView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CameraSettingsActivityTest {
    @Test fun cameraAccessUsesTheSystemPermissionDialogWithoutWritingTheDraft() {
        val controller = Robolectric.buildActivity(CameraSettingsActivity::class.java).setup()
        val activity = controller.get()
        assertNull(org.robolectric.Shadows.shadowOf(activity).lastRequestedPermission)
        click(activity, "允许使用摄像头")
        val request = requireNotNull(org.robolectric.Shadows.shadowOf(activity).lastRequestedPermission)
        assertArrayEquals(arrayOf(android.Manifest.permission.CAMERA), request.requestedPermissions)
        assertEquals(51, request.requestCode)
        assertFalse(CameraSettings.load(activity).enabled)
        controller.pause().stop().destroy()
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun views(activity: CameraSettingsActivity) = descendants(activity.window.decorView)
    private fun click(activity: CameraSettingsActivity, label: String) =
        views(activity).filterIsInstance<Button>().first { it.text.toString() == label }.performClick()

    private fun adjustYaw(activity: CameraSettingsActivity, degrees: Float) {
        val bar = views(activity).filterIsInstance<SeekBar>().first()
        val progress = CameraSliderMapping.YAW_ROLL.progress(degrees)
        bar.progress = progress
        ReflectionHelpers.getField<SeekBar.OnSeekBarChangeListener>(bar, "mOnSeekBarChangeListener")
            .onProgressChanged(bar, progress, true)
    }

    @Test fun cancellingThePageDiscardsAppliedLensDraftAndMasterSwitch() {
        val controller = Robolectric.buildActivity(CameraSettingsActivity::class.java).setup()
        val activity = controller.get()
        views(activity).filterIsInstance<Switch>().first().isChecked = true
        click(activity, "编辑")
        adjustYaw(activity, 12f)
        click(activity, "应用到草稿")
        assertFalse(CameraSettings.load(activity).enabled)
        click(activity, "取消")
        val saved = CameraSettings.load(activity)
        assertFalse(saved.enabled)
        assertEquals(0f, saved.views.getValue(CameraView.LEFT_REAR).yawDegrees, 0f)
        controller.pause().stop().destroy()
    }

    @Test fun savingCommitsLensAndMasterWithoutPromotingAReferenceToLiveCapture() {
        val controller = Robolectric.buildActivity(CameraSettingsActivity::class.java).setup()
        val activity = controller.get()
        views(activity).filterIsInstance<Switch>().first().isChecked = true
        click(activity, "编辑")
        adjustYaw(activity, 14f)
        click(activity, "应用到草稿")
        click(activity, "保存")
        val saved = CameraSettings.load(activity)
        assertTrue(saved.enabled)
        assertEquals(14f, saved.views.getValue(CameraView.LEFT_REAR).yawDegrees, 0f)
        assertFalse(saved.views.getValue(CameraView.LEFT_REAR).lens.calibrationConfirmed)
        assertNull(com.shilapi.xcertplay.camera.CameraIntegration.sourceReference())
        controller.pause().stop().destroy()
    }
}
