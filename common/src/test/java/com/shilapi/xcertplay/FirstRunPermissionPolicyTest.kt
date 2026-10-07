package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class FirstRunPermissionPolicyTest {
    @Test fun onlyUnconfiguredFreshInstallationStartsAutomatically() {
        assertTrue(FirstRunPermissionPolicy.shouldStart(100, 100, false, false))
        assertFalse(FirstRunPermissionPolicy.shouldStart(100, 200, false, false))
        assertFalse(FirstRunPermissionPolicy.shouldStart(100, 100, true, false))
        assertFalse(FirstRunPermissionPolicy.shouldStart(100, 100, false, true))
        assertFalse(FirstRunPermissionPolicy.shouldStart(0, 0, false, false))
    }
    @Test fun sdk28And29OnlyRequestMissingDeclaredRuntimePermissions() {
        val declared = setOf(CAMERA, MIC, FINE, COARSE, CONNECT, SCAN, NOTICE)
        for (sdk in listOf(28, 29)) {
            assertEquals(listOf(MIC, FINE, COARSE), FirstRunPermissionPolicy.runtimePermissions(sdk, declared, setOf(CAMERA)))
            assertTrue(FirstRunPermissionPolicy.runtimePermissions(sdk, declared, declared).isEmpty())
        }
    }
    @Test fun sdk31NearbyDevicesAndSdk33NotificationsRespectActualManifest() {
        val declared = setOf(CAMERA, MIC, FINE, COARSE, CONNECT, NOTICE)
        val granted = setOf(CAMERA, MIC, FINE, COARSE)
        assertEquals(listOf(CONNECT), FirstRunPermissionPolicy.runtimePermissions(31, declared, granted))
        assertEquals(listOf(CONNECT, NOTICE), FirstRunPermissionPolicy.runtimePermissions(33, declared, granted))
        assertFalse(FirstRunPermissionPolicy.runtimePermissions(33, declared, granted).contains(SCAN))
    }
    private companion object {
        const val CAMERA = "android.permission.CAMERA"
        const val MIC = "android.permission.RECORD_AUDIO"
        const val FINE = "android.permission.ACCESS_FINE_LOCATION"
        const val COARSE = "android.permission.ACCESS_COARSE_LOCATION"
        const val CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val SCAN = "android.permission.BLUETOOTH_SCAN"
        const val NOTICE = "android.permission.POST_NOTIFICATIONS"
    }
}
