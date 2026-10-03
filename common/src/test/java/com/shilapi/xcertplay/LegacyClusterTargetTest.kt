package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class LegacyClusterTargetTest {
    @Test fun logicalDisplaySectionsDoNotUsePhysicalAddresses() {
        val dump = """
            Display Devices:
              address{port=1, model=4}, modeId=2
            Logical Displays:
              Display 0:
                mDisplayId=0
              Display 1:
                mDisplayId=1
        """.trimIndent()
        assertEquals(setOf(0, 1), LegacyClusterTarget.ids(dump))
        assertTrue(LegacyClusterTarget.allowed(1, dump))
        assertFalse(LegacyClusterTarget.allowed(0, dump))
        assertFalse(LegacyClusterTarget.allowed(2, dump))
    }
    @Test fun displayInfoFormatWorksWithoutPresentationCategory() {
        val dump = "mBaseDisplayInfo=DisplayInfo{\"Cluster\", displayId 3, 1920 x 720, state ON}"
        assertTrue(LegacyClusterTarget.allowed(3, dump))
        assertFalse(LegacyClusterTarget.allowed(1, dump))
    }
    @Test fun absentOrUnrecognizedOutputCannotAuthorizeLaunch() {
        for (dump in listOf("", "Permission Denial", "DisplayDeviceInfo{displayId=1, 1920 x 720}", "uniqueId=local:1"))
            assertFalse(LegacyClusterTarget.allowed(1, dump))
    }
    @Test fun launchRequiresExplicitSuccessWithoutErrors() {
        assertTrue(LegacyClusterTarget.launched("Status: ok\nActivity: com.test/ClusterMapActivity"))
        assertFalse(LegacyClusterTarget.launched(null))
        assertTrue(LegacyClusterTarget.launched("Starting: Intent { ... }"))
        assertFalse(LegacyClusterTarget.launched("Status: ok\nError: Activity not started"))
        assertFalse(LegacyClusterTarget.launched("SecurityException: Permission Denial"))
    }

    @Test fun launchUsesCompatibleNumericFlagsAndAnIndependentComponent() {
        val token = "12345678-1234-4234-8234-123456789abc"
        assertEquals(
            "am start-activity --display 1 -f 0x18000000 -n com.shihab.diplay.tang21test/com.shilapi.xcertplay.ClusterMapActivity --es cluster_launch_token $token",
            LegacyClusterTarget.launchCommand(1, "com.shihab.diplay.tang21test", token),
        )
        assertFalse(LegacyClusterTarget.launchCommand(3, "com.test", token).contains("--activity-"))
        assertTrue(LegacyClusterTarget.launchCommand(3, "com.test", token).contains("--display 3"))
    }

    @Test fun launchRejectsDefaultDisplayAndShellInjection() {
        val token = "12345678-1234-4234-8234-123456789abc"
        fun rejected(target: Int = 1, applicationId: String = "com.test", value: String = token) {
            try {
                LegacyClusterTarget.launchCommand(target, applicationId, value)
                fail("Unsafe command input was accepted")
            } catch (_: IllegalArgumentException) { }
        }
        rejected(target = 0)
        rejected(target = -1)
        for (value in listOf("com.test;reboot", "com.test\nreboot", "com.test/foo", "com.test ", "com.test'"))
            rejected(applicationId = value)
        for (value in listOf("", "$token;reboot", "$token\nreboot", "$(reboot)", "--display 0", "1234"))
            rejected(value = value)
    }

    @Test fun nonWaitingLaunchStillRejectsShellFailures() {
        assertTrue(LegacyClusterTarget.launched("Starting: Intent { cmp=com.test/ClusterMapActivity }"))
        for (failure in listOf("Error: Activity class does not exist", "SecurityException", "PermissionDenied", "Permission denial"))
            assertFalse(LegacyClusterTarget.launched("Starting: Intent { ... }\n$failure"))
        assertFalse(LegacyClusterTarget.launched("Unknown option: --display"))
        assertFalse(LegacyClusterTarget.launchCommand(1, "com.test", "12345678-1234-4234-8234-123456789abc").contains(" -W "))
    }

    @Test fun activityConfirmationRequiresEveryGuardAndExactNonDefaultDisplay() {
        assertTrue(LegacyClusterTarget.accepts(1, 1, true, true, true, true))
        assertFalse(LegacyClusterTarget.accepts(0, 0, true, true, true, true))
        assertFalse(LegacyClusterTarget.accepts(1, 2, true, true, true, true))
        assertFalse(LegacyClusterTarget.accepts(1, 1, false, true, true, true))
        assertFalse(LegacyClusterTarget.accepts(1, 1, true, false, true, true))
        assertFalse(LegacyClusterTarget.accepts(1, 1, true, true, false, true))
        assertFalse(LegacyClusterTarget.accepts(1, 1, true, true, true, false))
    }

    private fun history(display: Int, task: Int = 222, component: String = "com.test/com.shilapi.xcertplay.ClusterMapActivity") =
        "Display #$display (activities from top to bottom):\n" +
        "      * Hist #0: ActivityRecord{abcd u0 $component, StackId =238, windowingMode =1 t$task}\n"

    @Test fun verifiesExactTaskAndComponentInsideDisplayHistory() {
        assertEquals(1, LegacyClusterTarget.activityDisplay(history(1), "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(history(0), "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(history(1, task = 223), "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(history(1, component = "com.test/com.shilapi.xcertplay.CarPlayHostActivity"), "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(history(1, component = "com.other/com.shilapi.xcertplay.ClusterMapActivity"), "com.test", 222))
    }

    @Test fun ambiguousDisplayAssignmentIsRejected() {
        assertNull(LegacyClusterTarget.activityDisplay(history(1) + history(28), "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(history(1) + history(0), "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(history(1) + history(1), "com.test", 222))
    }

    @Test fun globalFooterAndSummaryRecordsCannotBeAssignedToLastDisplay() {
        val record = "ActivityRecord{abcd u0 com.test/com.shilapi.xcertplay.ClusterMapActivity, StackId =238, windowingMode =1 t222}"
        val prefix = history(1, task = 999)
        val footer = "ActivityStackSupervisor state:\n      * Hist #0: $record\n"
        assertNull(LegacyClusterTarget.activityDisplay(prefix + footer, "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(prefix + "  " + footer, "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(prefix + "  ResumedActivity: $record\n      * Hist #0: $record\n", "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(prefix + "    mResumedActivity: $record\n", "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay(prefix + "        Run #0: $record\n", "com.test", 222))
        assertNull(LegacyClusterTarget.activityDisplay("      * Hist #0: $record\n", "com.test", 222))
    }
}
