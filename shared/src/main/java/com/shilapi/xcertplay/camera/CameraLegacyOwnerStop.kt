package com.shilapi.xcertplay.camera

import com.shilapi.xcertplay.adb.LocalAdb

/** Stops only L1's two confirmed shell-owned backends when the owner selects the built-in replacement. */
internal object CameraLegacyOwnerStop {
    fun stop(adb: LocalAdb): Boolean {
        val script = """
            ps -A -o USER,PID,NAME | while read user pid name; do
              case "${'$'}name" in l1bg|l1echo) ;; *) continue ;; esac
              case "${'$'}pid" in ''|*[!0-9]*) continue ;; esac
              [ "${'$'}user" = shell ] || continue
              uid=${'$'}(toybox stat -c %u /proc/${'$'}pid 2>/dev/null)
              [ "${'$'}uid" = 2000 ] || continue
              first=${'$'}(tr '\000' '\n' < /proc/${'$'}pid/cmdline 2>/dev/null | head -n 1)
              [ "${'$'}first" = "${'$'}name" ] || continue
              kill -TERM "${'$'}pid" || exit 1
            done || exit 1
            echo legacy-camera-stop-submitted
        """.trimIndent()
        val quoted = "'" + script.replace("'", "'\\''") + "'"
        return adb.shell("toybox timeout 5 sh -c $quoted")?.trim() == "legacy-camera-stop-submitted"
    }
}
