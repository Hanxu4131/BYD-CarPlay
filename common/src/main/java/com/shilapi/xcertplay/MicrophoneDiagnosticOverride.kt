package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.shilapi.xcertplay.media.MicrophoneProcessing
import java.io.File

/** Short-lived local ADB comparison; normal settings and non-debug builds are unaffected. */
internal object MicrophoneDiagnosticOverride {
    fun load(context: Context, normal: MicrophoneProcessing): MicrophoneProcessing {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return normal
        return try {
            val file = File(context.filesDir, "microphone-diagnostic-profile")
            if (!file.isFile || file.length() !in 1..32 ||
                System.currentTimeMillis() - file.lastModified() !in 0..180_000) return normal
            val profile = file.readText().trim()
            val selected = when (profile) {
                "baseline" -> MicrophoneProcessing(systemEffectsAllowed = false)
                "ns-mic" -> MicrophoneProcessing(noiseSuppression = true, preferMicSource = true)
                "both-mic" -> MicrophoneProcessing(noiseSuppression = true, echoCancellation = true, preferMicSource = true)
                "aec-mic" -> MicrophoneProcessing(echoCancellation = true, preferMicSource = true)
                "ns-default" -> MicrophoneProcessing(noiseSuppression = true)
                "aec-default" -> MicrophoneProcessing(echoCancellation = true)
                else -> return normal
            }
            Log.i("DiPlay-MicDiagnostic", "temporary profile=$profile; applies only to this capture")
            selected
        } catch (_: Exception) { normal }
    }
}
