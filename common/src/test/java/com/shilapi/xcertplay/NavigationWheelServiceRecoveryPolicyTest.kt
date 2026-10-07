package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class NavigationWheelServiceRecoveryPolicyTest {
    private val own = "com.shihab.diplay.tang21test/com.shilapi.xcertplay.NavigationWheelAccessibilityService"
    private val other = "cc.omycar.magicmanager/.Service:com.dudu/.Accessibility"
    @Test fun threeFailuresCooldownAndUnresolvedFaultBudgetApplyEvenAcrossWake() {
        val policy = NavigationWheelServiceRecoveryPolicy()
        assertFalse(policy.observe(0, true, false, false))
        assertFalse(policy.observe(2000, true, false, false))
        assertTrue(policy.observe(4000, true, false, false))
        repeat(29) { assertFalse(policy.observe(6000L + it * 2000L, true, false, false)) }
        assertTrue(policy.observe(64000, true, true, false))
        policy.wake()
        assertFalse(policy.observe(124000, true, false, false))
        assertFalse(policy.observe(126000, true, false, false))
        assertTrue(policy.observe(128000, true, false, false))
        policy.wake()
        repeat(20) { assertFalse(policy.observe(200000L + it * 2000L, true, false, false)) }
    }
    @Test fun healthyAndDisabledClearConsecutiveFailuresWithoutConsumingBudget() {
        val policy = NavigationWheelServiceRecoveryPolicy()
        repeat(2) { assertFalse(policy.observe(it.toLong(), true, true, false)) }
        assertFalse(policy.observe(2, true, true, true))
        repeat(2) { assertFalse(policy.observe(3L + it, true, false, false)) }
        assertFalse(policy.observe(5, false, false, false))
        assertFalse(policy.observe(6, true, false, false))
        assertFalse(policy.observe(7, true, false, false))
        assertTrue(policy.observe(8, true, false, false))
    }
    @Test fun sixtySecondsHealthyAllowsLaterFailuresToRecoverWithoutRestartOrSave() {
        val policy = exhausted()
        assertFalse(policy.observe(200000, true, true, true))
        assertFalse(policy.observe(259999, true, true, true))
        // One millisecond short of the health period must not refill the exhausted budget.
        assertFalse(policy.observe(260000, true, false, false))
        assertFalse(policy.observe(262000, true, false, false))
        assertFalse(policy.observe(264000, true, false, false))
        assertFalse(policy.observe(266000, true, true, true))
        assertFalse(policy.observe(326000, true, true, true))
        assertFalse(policy.observe(328000, true, false, false))
        assertFalse(policy.observe(330000, true, false, false))
        assertTrue(policy.observe(332000, true, false, false))
    }
    @Test fun FlappingDisabledAndWakeCannotMasqueradeAsSustainedRecovery() {
        val policy = exhausted()
        assertFalse(policy.observe(200000, true, true, true))
        assertFalse(policy.observe(230000, true, true, false))
        assertFalse(policy.observe(232000, true, true, true))
        assertFalse(policy.observe(260000, false, true, true))
        assertFalse(policy.observe(262000, true, true, true))
        policy.wake()
        assertFalse(policy.observe(300000, true, true, true))
        assertFalse(policy.observe(330000, true, true, true))
        repeat(3) { assertFalse(policy.observe(332000L + it * 2000L, true, false, false)) }
    }
    @Test fun connectedWithoutKeyFilterIsAFaultButUnknownFlagsDoNotTriggerRepair() {
        val policy = NavigationWheelServiceRecoveryPolicy()
        assertFalse(policy.observe(0,true,true,true,false))
        assertFalse(policy.observe(2000,true,true,true,false))
        assertTrue(policy.observe(4000,true,true,true,false))
        val unknown = NavigationWheelServiceRecoveryPolicy()
        repeat(5) { assertFalse(unknown.observe(it*2000L,true,true,true,null)) }
    }
    @Test fun disabledMasterNeverRepairsEvenWithMissingOrBrokenService() {
        val policy = NavigationWheelServiceRecoveryPolicy()
        repeat(10) { assertFalse(policy.observe(it*2000L,false,false,false,false)) }
        assertFalse(policy.observe(20000,true,false,false,false))
        assertFalse(policy.observe(22000,true,false,false,false))
        assertTrue(policy.observe(24000,true,false,false,false))
    }
    private fun exhausted(): NavigationWheelServiceRecoveryPolicy {
        val policy = NavigationWheelServiceRecoveryPolicy()
        for (start in listOf(0L, 64000L, 128000L)) {
            assertFalse(policy.observe(start, true, false, false))
            assertFalse(policy.observe(start + 2000, true, false, false))
            assertTrue(policy.observe(start + 4000, true, false, false))
        }
        return policy
    }
    @Test fun actualShellPreservesOtherEntriesDuringRebindAndDoesNotWriteGlobalSwitch() {
        val script = NavigationWheelServiceCommand.build(own, own, true, true)!!
        assertFalse(script.contains("accessibility_enabled"))
        val output = shell(other + ":" + own, script, "sleep() { current=\"\$current:com.new/.Service\"; }")
        assertEquals(other + ":com.new/.Service:" + own, output.second.trim())
        assertEquals(0, output.first)
    }
    @Test fun disableOnlyRemovesOwnAndHandlesShortNameWithoutTouchingSamePrefixService() {
        val full = "app.pkg/app.pkg.Service"
        val short = "app.pkg/.Service"
        val script = NavigationWheelServiceCommand.build(full, short, false, false)!!
        val result = shell("$other:$short:app.pkg/.ServiceExtra:$full", script)
        assertEquals("$other:app.pkg/.ServiceExtra", result.second.trim())
        assertFalse(NavigationWheelServiceList.contains(result.second, full))
    }
    @Test fun failedOrMalformedReadNeverIssuesPutAndInvalidComponentCannotBecomeCode() {
        val script = NavigationWheelServiceCommand.build(own, own, true, true)!!
        val bad = shell("Permission denied", script)
        assertNotEquals(0, bad.first)
        assertFalse(bad.second.contains("WRITTEN"))
        assertNull(NavigationWheelServiceCommand.build("app/evil';id", own, true, false))
        val result = ProcessBuilder("sh", "-c", "settings() { if [ \"\$1\" = get ]; then return 1; else echo WRITTEN; fi; };\n" + script)
            .redirectErrorStream(true).start()
        val text = result.inputStream.bufferedReader().readText()
        assertNotEquals(0, result.waitFor())
        assertFalse(text.contains("WRITTEN"))
    }
    private fun shell(initial: String, script: String, extra: String = ""): Pair<Int, String> {
        val fixture = "current='$initial'; settings() { if [ \"\$1\" = get ]; then printf '%s\\n' \"\$current\"; else current=\"\$4\"; fi; };\n$extra\n"
        val process = ProcessBuilder("sh", "-c", fixture + script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }
}
