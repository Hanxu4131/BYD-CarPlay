package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class NavigationWheelFallbackPolicyTest {
    private val primary = "app.pkg/app.pkg.Wheel"
    private val fallback = "app.pkg/app.pkg.WheelFallback"
    private val identities = listOf(primary to "app.pkg/.Wheel", fallback to "app.pkg/.WheelFallback")
    @Test fun tenSecondsOfSameUnhealthyBindingIsRequiredAndHealthBreaksContinuity() {
        val gate = NavigationWheelFallbackGate()
        assertFalse(gate.observe(0, primary, true, false))
        assertFalse(gate.observe(9999, primary, true, false))
        assertTrue(gate.observe(10000, primary, true, false))
        assertFalse(gate.observe(10001, primary, true, true))
        assertFalse(gate.observe(20000, primary, true, false))
        assertFalse(gate.observe(29999, primary, true, false))
        assertTrue(gate.observe(30000, primary, true, false))
    }
    @Test fun missingBindingUnknownResetAndIdentityChangeNeverInheritDuration() {
        val gate = NavigationWheelFallbackGate()
        gate.observe(0, primary, true, false)
        assertFalse(gate.observe(10000, primary, false, false))
        assertFalse(gate.observe(11000, primary, true, false))
        assertFalse(gate.observe(21000, fallback, true, false))
        gate.clear()
        assertFalse(gate.observe(50000, fallback, true, false))
        assertFalse(gate.observe(1, fallback, true, false))
    }
    @Test fun durableBudgetSurvivesProcessRestartSwitchTogglingAndOnlyResetsWithBoot() {
        var usedBoot = -2
        var stored = primary
        var commits = 0
        fun claim(boot: Int, selected: String, target: String) = NavigationWheelFallbackBudget.claim(
            boot, usedBoot, selected, target, listOf(primary, fallback)) { count, choice ->
            commits++; usedBoot = count; stored = choice; true
        }
        assertTrue(claim(8, primary, fallback))
        assertEquals(fallback, stored)
        // Recreated callers use the same committed record; toggles do not touch it.
        repeat(10) { assertFalse(claim(8, fallback, primary)) }
        assertEquals(1, commits)
        assertTrue(claim(9, fallback, primary))
        assertEquals(2, commits)
    }
    @Test fun failedCommitUnknownBootAndInvalidTargetCannotAuthorizeSettingsWrites() {
        var writes = 0
        val committed = NavigationWheelFallbackBudget.claim(8, -2, primary, fallback, listOf(primary, fallback)) { _, _ -> false }
        if (committed) writes++
        assertEquals(0, writes)
        for ((boot, target) in listOf(-1 to fallback, 8 to primary, 8 to "other.pkg/.Service")) {
            assertFalse(NavigationWheelFallbackBudget.claim(boot, -2, primary, target, listOf(primary, fallback)) { _, _ -> writes++; true })
        }
        assertEquals(0, writes)
    }
    @Test fun coldStartUsesSavedRegisteredFallbackOrSoleExistingOwnRegistration() {
        val allowed = listOf(primary, fallback)
        assertEquals(fallback, NavigationWheelFallbackBudget.restore(fallback, allowed, primary, allowed))
        assertEquals(fallback, NavigationWheelFallbackBudget.restore(null, listOf(fallback), primary, allowed))
        assertEquals(fallback, NavigationWheelFallbackBudget.restore(fallback, listOf(primary), primary, allowed))
        assertEquals(fallback, NavigationWheelFallbackBudget.restore(fallback, emptyList(), primary, allowed))
        assertEquals(primary, NavigationWheelFallbackBudget.restore("other.pkg/.Service", emptyList(), primary, allowed))
    }
    @Test fun atomicRegistrationRetiresBothOwnFormsPreservesOthersAndNeverTouchesGlobalSwitch() {
        val script = NavigationWheelIdentityCommand.build(identities, fallback)!!
        assertFalse(script.contains("accessibility_enabled"))
        val initial = "other.pkg/.Service:app.pkg/.Wheel:$primary:app.pkg/.WheelFallback:app.pkg/.WheelExtra"
        val fixture = "current='$initial'; settings() { if [ \"${'$'}1\" = get ]; then printf '%s\\n' \"${'$'}current\"; else current=\"${'$'}4\"; fi; };\n"
        val process = ProcessBuilder("sh", "-c", fixture + NavigationWheelMutationReadback.checked(script)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor())
        assertTrue(NavigationWheelIdentityCommand.verified(output, identities.map { it.first }, fallback))
        val actual = output.lines().dropLast(1).last()
        assertEquals("other.pkg/.Service:app.pkg/.WheelExtra:$fallback", actual)
        assertFalse(NavigationWheelIdentityCommand.verified("$primary:$fallback\n${NavigationWheelMutationReadback.MARKER}", identities.map { it.first }, fallback))
    }
    @Test fun invalidComponentOrFailedSettingsReadCannotBecomeAWrite() {
        assertNull(NavigationWheelIdentityCommand.build(identities, "other.pkg/.Service"))
        assertNull(NavigationWheelIdentityCommand.build(listOf(primary to "app/evil';id", identities[1]), fallback))
        val script = NavigationWheelIdentityCommand.build(identities, fallback)!!
        val process = ProcessBuilder("sh", "-c", "settings() { if [ \"${'$'}1\" = get ]; then return 1; else echo WRITTEN; fi; };\n" + NavigationWheelMutationReadback.checked(script)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertNotEquals(0, process.waitFor())
        assertFalse(output.contains("WRITTEN"))
        assertFalse(NavigationWheelIdentityCommand.verified(output, identities.map { it.first }, fallback))
    }
    @Test fun retiredLateConnectionCannotOwnKeysOrRequestFilterAndSwitchingFreezesBoth() {
        assertFalse(NavigationWheelOwnerPolicy.allows(primary, fallback, false, true))
        assertTrue(NavigationWheelOwnerPolicy.allows(fallback, fallback, false, true))
        for (component in listOf(primary, fallback)) {
            assertFalse(NavigationWheelOwnerPolicy.allows(component, fallback, true, true))
            assertFalse(NavigationWheelOwnerPolicy.allows(component, fallback, false, false))
        }
    }

    @Test fun systemBoundRetiredFilterBlocksActivationBeforeItsAppCallbackArrives() {
        assertTrue(NavigationWheelOwnerPolicy.canActivate(fallback, emptyList()))
        assertTrue(NavigationWheelOwnerPolicy.canActivate(fallback, listOf(fallback)))
        assertFalse(NavigationWheelOwnerPolicy.canActivate(fallback, listOf(primary)))
        assertFalse(NavigationWheelOwnerPolicy.canActivate(fallback, listOf(primary, fallback)))
    }

}
