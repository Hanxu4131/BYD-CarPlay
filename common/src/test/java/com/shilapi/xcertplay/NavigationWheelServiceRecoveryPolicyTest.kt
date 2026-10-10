package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class NavigationWheelServiceRecoveryPolicyTest {
    private val own = "com.shihab.diplay.tang21test/com.shilapi.xcertplay.NavigationWheelAccessibilityService"
    private val other = "cc.omycar.magicmanager/.Service:com.dudu/.Accessibility"
    @Test fun unresolvedFaultRetriesAfterThreeFailuresAndBackoffNeverGivesUp() {
        val policy = NavigationWheelServiceRecoveryPolicy()
        assertFalse(policy.observe(0, true, false, false))
        assertFalse(policy.observe(2000, true, false, false))
        assertTrue(policy.observe(4000, true, false, false))
        for ((before, due) in listOf(63999L to 64000L, 183999L to 184000L,
            423999L to 424000L, 723999L to 724000L, 1023999L to 1024000L)) {
            // Wake and repeated observations cannot bypass the previous attempt's cooldown.
            policy.wake()
            repeat(3) { assertFalse(policy.observe(before - 2 + it, true, true, false)) }
            assertTrue(policy.observe(due, true, true, false))
        }
        // A long-running unresolved fault still gets a bounded later retry.
        assertFalse(policy.observe(1323997, true, false, false))
        assertFalse(policy.observe(1323998, true, false, false))
        assertTrue(policy.observe(1324000, true, false, false))
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
    @Test fun sustainedHealthResetsBackoffButPreservesLastAttemptCooldown() {
        val policy = backedOff()
        assertFalse(policy.observe(200000, true, true, true))
        assertFalse(policy.observe(260000, true, true, true))
        repeat(3) { assertFalse(policy.observe(262000L + it * 2000L, true, false, false)) }
        assertFalse(policy.observe(423999, true, false, false))
        assertTrue(policy.observe(424000, true, false, false))
        // Healthy reset returned the next delay to one minute rather than five minutes.
        repeat(3) { assertFalse(policy.observe(426000L + it * 2000L, true, false, false)) }
        assertFalse(policy.observe(483999, true, false, false))
        assertTrue(policy.observe(484000, true, false, false))
    }
    @Test fun flappingDisabledAndWakeDoNotResetBackoff() {
        val policy = backedOff()
        assertFalse(policy.observe(200000, true, true, true))
        assertFalse(policy.observe(230000, true, true, false))
        assertFalse(policy.observe(232000, true, true, true))
        assertFalse(policy.observe(260000, false, true, true))
        assertFalse(policy.observe(262000, true, true, true))
        policy.wake()
        assertFalse(policy.observe(300000, true, true, true))
        assertFalse(policy.observe(330000, true, true, true))
        repeat(3) { assertFalse(policy.observe(332000L + it * 2000L, true, false, false)) }
        assertTrue(policy.observe(424000, true, false, false))
        repeat(3) { assertFalse(policy.observe(426000L + it * 2000L, true, false, false)) }
        assertFalse(policy.observe(484000, true, false, false))
        assertTrue(policy.observe(724000, true, false, false))
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
    private fun backedOff(): NavigationWheelServiceRecoveryPolicy {
        val policy = NavigationWheelServiceRecoveryPolicy()
        for (start in listOf(0L, 60000L, 180000L)) {
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
