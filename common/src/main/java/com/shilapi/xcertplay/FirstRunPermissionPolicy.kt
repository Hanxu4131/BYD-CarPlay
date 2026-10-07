package com.shilapi.xcertplay

/** Missing new preferences alone must never turn an upgraded installation into a first run. */
internal object FirstRunPermissionPolicy {
    fun shouldStart(firstInstall: Long, lastUpdate: Long, configured: Boolean, evaluated: Boolean): Boolean =
        !evaluated && !configured && firstInstall > 0 && firstInstall == lastUpdate

    fun runtimePermissions(sdk: Int, declared: Set<String>, granted: Set<String>): List<String> {
        val requested = mutableListOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        if (sdk >= 31) requested += listOf("android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_ADVERTISE")
        if (sdk >= 33) requested += "android.permission.POST_NOTIFICATIONS"
        return requested.filter { it in declared && it !in granted }
    }
}
