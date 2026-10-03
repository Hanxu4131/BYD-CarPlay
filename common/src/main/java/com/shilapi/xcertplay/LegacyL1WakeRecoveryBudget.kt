package com.shilapi.xcertplay

import android.content.Context
import android.provider.Settings

/** Reserve the wake budget before stopping L1, so a DiPlay process restart cannot retry it. */
internal object LegacyL1WakeRecoveryBudget {
    enum class Result { PERSISTED, SESSION_ONLY, ALREADY_ATTEMPTED, PERSIST_FAILED }

    fun claim(context: Context, wake: Long): Result {
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }
            .getOrNull()?.takeIf { it >= 0 }
        if (boot == null) return Result.SESSION_ONLY
        val prefs = context.getSharedPreferences("diplay_l1_wake_recovery", Context.MODE_PRIVATE)
        return claim(boot, wake, prefs.getInt("attempted_boot", -1), prefs.getLong("attempted_wake", -1)) {
            prefs.edit().putInt("attempted_boot", boot).putLong("attempted_wake", wake).commit()
        }
    }

    internal fun claim(boot: Int?, wake: Long, storedBoot: Int, storedWake: Long,
        persist: () -> Boolean): Result {
        if (boot == null || boot < 0) return Result.SESSION_ONLY
        if (boot == storedBoot && wake == storedWake) return Result.ALREADY_ATTEMPTED
        return if (runCatching { persist() }.getOrDefault(false)) Result.PERSISTED else Result.PERSIST_FAILED
    }
}
