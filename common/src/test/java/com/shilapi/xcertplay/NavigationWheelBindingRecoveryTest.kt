package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class NavigationWheelBindingRecoveryTest {
    private val own = "app.pkg/app.pkg.Wheel"
    private fun dump(bound: String = "", binding: String = "") =
        "User state[attributes:{id=0, currentUser=true}\n Bound services:{$bound}\n Enabled services:{{$own}}\n Binding services:{$binding}]"

    @Test fun fullAndShortNamesMatchButPrefixAndOtherUsersDoNot() {
        for (entry in listOf(own, "app.pkg/.Wheel")) {
            assertEquals(NavigationWheelBindingDump.Snapshot(true, true),
                NavigationWheelBindingDump.parse(dump(binding = "{$entry}"), own))
        }
        assertEquals(NavigationWheelBindingDump.Snapshot(true, false),
            NavigationWheelBindingDump.parse(dump(bound = "{app.pkg/.WheelExtra}, {other.pkg/.Wheel}"), own))
        val otherUser = dump(binding = "{$own}").replace("currentUser=true", "currentUser=false")
        assertEquals(NavigationWheelBindingDump.Snapshot(true, false),
            NavigationWheelBindingDump.parse(otherUser + "\n" + dump(), own))
    }

    @Test fun androidNineLabelOnlyAndIncompleteDumpNeverProveAbsence() {
        val labelOnly = dump(bound = "Service[label=导航滚轮, capabilities=9, eventTypes=[TYPE_WINDOWS_CHANGED]]")
        assertFalse(NavigationWheelBindingDump.parse(labelOnly, own).verifiable)
        assertFalse(NavigationWheelBindingDump.parse(dump().replace("Binding services:", "Missing:"), own).verifiable)
        assertFalse(NavigationWheelBindingDump.parse(dump() + "\n" + dump(), own).verifiable)
        assertFalse(NavigationWheelBindingDump.parse(null, own).verifiable)
        assertFalse(NavigationWheelBindingDump.parse(dump().replace("Bound services:{}", "Bound services:{"), own).verifiable)
    }

    @Test fun androidNineBoundApiComplementsLabelOnlyDump() {
        val labels = dump(bound = "Service[label=嘟嘟], Service[label=导航滚轮]")
        assertEquals(NavigationWheelBindingDump.Snapshot(true, false),
            NavigationWheelBindingDump.parse(labels, own, listOf("other.pkg/.Service")))
        assertEquals(NavigationWheelBindingDump.Snapshot(true, true),
            NavigationWheelBindingDump.parse(labels, own, listOf("app.pkg/.Wheel")))
        assertEquals(NavigationWheelBindingDump.Snapshot(true, true),
            NavigationWheelBindingDump.parse(dump(binding = "{app.pkg/.Wheel}"), own, emptyList()))
        assertFalse(NavigationWheelBindingDump.parse(labels, own, listOf("unidentified")).verifiable)
        assertFalse(NavigationWheelBindingDump.parse(labels.replace("Binding services:", "Missing:"), own, emptyList()).verifiable)
    }

    private inner class Fixture {
        var enabled = true
        var time = 0L
        val mutations = mutableListOf<Boolean>()
        val phases = mutableListOf<String>()
        var reads = 0
        var source: () -> String? = { dump(binding = "{$own}") }
        var mutation: (Boolean) -> Boolean = { true }
        fun recovery() = NavigationWheelBindingRecovery(own, { enabled },
            { reads++; NavigationWheelBindingDump.parse(source(), own) }, { mutations.add(it); mutation(it) },
            { time }, { time += it }, { phases.add(it) })
    }

    @Test fun disabledAndUnverifiablePreflightNeverMutate() {
        val f = Fixture()
        f.enabled = false
        assertEquals(NavigationWheelBindingRecovery.Result.DISABLED, f.recovery().run(true))
        assertEquals(0, f.reads)
        f.enabled = true
        f.source = { dump(bound = "Service[label=嘟嘟]") }
        assertEquals(NavigationWheelBindingRecovery.Result.NO_SYSTEM_PROOF, f.recovery().run(true))
        assertTrue(f.mutations.isEmpty())
    }

    @Test fun waitsForActualDetachBeforeRestoringAndNeverReportsConnected() {
        val f = Fixture()
        f.source = { if (f.reads < 3) dump(binding = "{$own}") else dump() }
        assertEquals(NavigationWheelBindingRecovery.Result.REGISTERED, f.recovery().run(true))
        assertEquals(listOf(false, true), f.mutations)
        assertEquals(3, f.reads)
        assertEquals(250L, f.time)
    }

    @Test fun stuckBindingHasBoundedReadsAndStillRestores() {
        val f = Fixture()
        assertEquals(NavigationWheelBindingRecovery.Result.DETACH_TIMEOUT, f.recovery().run(true))
        assertEquals(listOf(false, true), f.mutations)
        assertEquals(7, f.reads)
        assertTrue(f.time <= 2500)
    }

    @Test fun failedRemoveAndUnknownAfterRemoveStillRestore() {
        val failed = Fixture()
        failed.mutation = { add -> add }
        assertEquals(NavigationWheelBindingRecovery.Result.DETACH_TIMEOUT, failed.recovery().run(true))
        assertEquals(listOf(false, true), failed.mutations)
        val unknown = Fixture()
        unknown.source = { if (unknown.reads == 1) dump() else null }
        assertEquals(NavigationWheelBindingRecovery.Result.NO_SYSTEM_PROOF, unknown.recovery().run(true))
        assertEquals(listOf(false, true), unknown.mutations)
    }

    @Test fun shutdownDuringDetachRestoresPreexistingRegistrationAndStopsPolling() {
        val f = Fixture()
        f.mutation = { add -> if (!add) f.enabled = false; true }
        assertEquals(NavigationWheelBindingRecovery.Result.DISABLED, f.recovery().run(true))
        assertEquals(listOf(false, true), f.mutations)
        assertEquals(1, f.reads)
    }

    @Test fun exceptionDuringDetachRunsFinallyAndRestoreFailureIsExplicit() {
        val f = Fixture()
        f.source = { if (f.reads == 1) dump() else throw IllegalStateException("dump failed") }
        try { f.recovery().run(true); fail("exception expected") } catch (_: IllegalStateException) { }
        assertEquals(listOf(false, true), f.mutations)
        val restore = Fixture()
        restore.source = { dump() }
        restore.mutation = { add -> !add }
        assertEquals(NavigationWheelBindingRecovery.Result.RESTORE_FAILED, restore.recovery().run(true))
    }

    @Test fun ownStartIsOncePerFaultIncludingRefusalThenResetsOnConnectionOrOff() {
        val start = NavigationWheelOwnStart()
        assertTrue(start.claim())
        repeat(10) { start.observe(true, false); assertFalse(start.claim()) }
        start.observe(true, true)
        assertTrue(start.claim())
        start.observe(false, false)
        assertTrue(start.claim())
    }

    @Test fun mutationReadbackRequiresSuccessfulCompletionAndExactComponent() {
        val marker = NavigationWheelMutationReadback.MARKER
        assertFalse(NavigationWheelMutationReadback.verified(null, own, false))
        assertFalse(NavigationWheelMutationReadback.verified("", own, false))
        assertFalse(NavigationWheelMutationReadback.verified("Permission denied", own, false))
        assertFalse(NavigationWheelMutationReadback.verified(own, own, true))
        assertTrue(NavigationWheelMutationReadback.verified("other.pkg/.Service:$own\n\n$marker", own, true).not())
        assertTrue(NavigationWheelMutationReadback.verified("other.pkg/.Service:$own\n$marker", own, true))
        assertFalse(NavigationWheelMutationReadback.verified("app.pkg/.WheelExtra\n$marker", own, true))
        assertTrue(NavigationWheelMutationReadback.verified("DIPLAY_WHEEL_MUTATION_BEGIN\n\n$marker", own, false))
    }

    @Test fun actualShellMutationEmptyListSurvivesTransportTrimAndErrorsNeverSucceed() {
        val script = NavigationWheelServiceCommand.build(own, "app.pkg/.Wheel", false, false)!!
        val fakeSettings = "current='$own'; settings() { if [ \"${'$'}1\" = get ]; then printf '%s\\n' \"${'$'}current\"; else current=\"${'$'}4\"; fi; };\n"
        val process = ProcessBuilder("sh", "-c", fakeSettings + NavigationWheelMutationReadback.checked(script))
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, process.waitFor())
        assertTrue(NavigationWheelMutationReadback.verified(output, own, false))
        val failed = ProcessBuilder("sh", "-c", "settings() { return 1; };\n" + NavigationWheelMutationReadback.checked(script))
            .redirectErrorStream(true).start()
        val failure = failed.inputStream.bufferedReader().readText().trim()
        assertNotEquals(0, failed.waitFor())
        assertFalse(NavigationWheelMutationReadback.verified(failure, own, false))
    }
}
