package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEffectsTest {
    @Test fun fallbackDisablesEveryEffectAndKeepsHandlesUntilClose() {
        val states = mutableListOf<Pair<String, Boolean>>()
        val released = mutableListOf<String>()
        val manager = VoiceEffects<String>(
            configure = { name, enabled -> states += name to enabled; true },
            release = { released += it },
            report = { _, _ -> },
        )
        org.junit.Assert.assertTrue(manager.add("AEC") { "aec" })
        org.junit.Assert.assertTrue(manager.add("NS") { "ns" })
        org.junit.Assert.assertTrue(manager.disableAll())
        assertEquals(listOf("aec" to true, "ns" to true, "aec" to false, "ns" to false), states)
        assertEquals(emptyList<String>(), released)
        manager.close()
        assertEquals(listOf("aec", "ns"), released)
    }

    @Test fun failedFallbackDoesNotSkipTheOtherEffect() {
        val attempts = mutableListOf<String>()
        val manager = VoiceEffects<String>(
            configure = { name, enabled ->
                if (!enabled) { attempts += name; if (name == "aec") throw IllegalStateException() }
                true
            },
            release = { }, report = { _, _ -> },
        )
        manager.add("AEC") { "aec" }
        manager.add("NS") { "ns" }
        org.junit.Assert.assertFalse(manager.disableAll())
        assertEquals(listOf("aec", "ns"), attempts)
        manager.close()
    }

    private val released = mutableListOf<String>()
    private val reports = mutableListOf<String>()
    private val effects = VoiceEffects<String>(
        configure = { effect, _ -> if (effect == "throws") throw IllegalStateException() else effect != "disabled" },
        release = { released.add(it); if (it == "bad-release") throw IllegalStateException() },
        report = { message, _ -> reports.add(message) },
    )

    @Test fun disabledEffectsAreExplicitlyConfiguredAndRetainedUntilClose() {
        val states = mutableListOf<Boolean>()
        val manager = VoiceEffects<String>(
            configure = { _, enabled -> states.add(enabled); true },
            release = { released.add(it) },
            report = { _, _ -> },
        )
        manager.add("AEC", false) { "aec" }
        manager.add("NS", true) { "ns" }
        assertEquals(listOf(false, true), states)
        assertEquals(emptyList<String>(), released)
        manager.close()
        assertEquals(listOf("aec", "ns"), released)
    }

    @Test fun supportedEffectsReleasedExactlyOnce() {
        effects.add("AEC") { "aec" }
        effects.add("NS") { "ns" }
        effects.close()
        effects.close()
        assertEquals(listOf("aec", "ns"), released)
    }

    @Test fun unavailableOrFailedCreationDoesNotPreventOtherEffect() {
        effects.add("AEC") { throw IllegalArgumentException() }
        effects.add("NS") { null }
        effects.add("next") { "working" }
        effects.close()
        assertEquals(listOf("working"), released)
        assertEquals(3, reports.size)
    }

    @Test fun disabledAndThrowingEffectsReleasedImmediately() {
        effects.add("AEC") { "disabled" }
        effects.add("NS") { "throws" }
        assertEquals(listOf("disabled", "throws"), released)
        effects.close()
        assertEquals(2, released.size)
    }

    @Test fun releaseFailureDoesNotLeakOtherEffect() {
        effects.add("AEC") { "bad-release" }
        effects.add("NS") { "working" }
        effects.close()
        assertEquals(listOf("bad-release", "working"), released)
        assertEquals(3, reports.size)
    }
}
