package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceEffectsTest {
    private val released = mutableListOf<String>()
    private val reports = mutableListOf<String>()
    private val effects = VoiceEffects<String>(
        enable = { if (it == "throws") throw IllegalStateException() else it != "disabled" },
        release = { released.add(it); if (it == "bad-release") throw IllegalStateException() },
        report = { message, _ -> reports.add(message) },
    )

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
