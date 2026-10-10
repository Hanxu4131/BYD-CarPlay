package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoDecoderSelectionTest {
    private fun candidate(name: String, hardware: Boolean = true, software: Boolean = false,
        mime: String = "video/avc", encoder: Boolean = false) =
        VideoDecoderCandidate(name, listOf(mime), encoder, hardware, software)

    @Test fun allHardwareStartFailuresNeverReachSoftwareOrDefaultDecoder() {
        val plan = VideoDecoderSelection.plan("video/avc", listOf(
            candidate("qcom"), candidate("software", hardware = false, software = true),
        ), false)
        // Exhausting this plan after any start error has no software/default attempt.
        assertEquals(listOf("qcom", "qcom"), plan.attempts.map { it.codecName })
        assertTrue(plan.hardwareOnly)
        assertTrue(plan.attempts.all { it.codecName != null })
    }

    @Test fun anotherHardwareDecoderCanStartWithMinimalFormat() {
        val plan = VideoDecoderSelection.plan("video/avc", listOf(candidate("first"), candidate("second")), false)
        val succeeded = plan.attempts.firstOrNull { it.codecName == "second" && !it.tuned }
        assertEquals(VideoDecoderAttempt("second", false), succeeded)
        assertEquals(listOf(VideoDecoderAttempt("first", true), VideoDecoderAttempt("first", false),
            VideoDecoderAttempt("second", true), VideoDecoderAttempt("second", false)), plan.attempts)
    }

    @Test fun noExplicitHardwareKeepsDefaultAndSoftwareCompatibility() {
        val plan = VideoDecoderSelection.plan("video/avc", listOf(
            candidate("unknown", hardware = false), candidate("software", hardware = false, software = true),
        ), false)
        assertFalse(plan.hardwareOnly)
        assertEquals(listOf(VideoDecoderAttempt(null, true), VideoDecoderAttempt(null, false),
            VideoDecoderAttempt("software", false)), plan.attempts)
        assertEquals(listOf(VideoDecoderAttempt(null, true), VideoDecoderAttempt(null, false)),
            VideoDecoderSelection.plan("video/avc", emptyList(), false).attempts)
    }

    @Test fun explicitSoftwareHevcPreferenceKeepsTheExistingCompatibilityPath() {
        val candidates = listOf(candidate("hevc-hw", mime = "video/hevc"),
            candidate("hevc-sw", hardware = false, software = true, mime = "video/hevc"))
        val plan = VideoDecoderSelection.plan("video/hevc", candidates, true)
        assertFalse(plan.hardwareOnly)
        assertEquals(listOf(VideoDecoderAttempt(null, true), VideoDecoderAttempt(null, false),
            VideoDecoderAttempt("hevc-sw", false)), plan.attempts)
        assertTrue(VideoDecoderSelection.plan("video/hevc", candidates, false).hardwareOnly)
        assertTrue(VideoDecoderSelection.plan("video/avc", listOf(candidate("avc-hw")), true).hardwareOnly)
    }

    @Test fun encodersUnsupportedMimeAndSoftwareFlagsCannotEnterHardwarePlan() {
        val plan = VideoDecoderSelection.plan("video/avc", listOf(
            candidate("encoder", encoder = true), candidate("hevc", mime = "video/hevc"),
            candidate("ambiguous", software = true), candidate("valid"), candidate("valid"),
        ), false)
        assertEquals(listOf(VideoDecoderAttempt("valid", true), VideoDecoderAttempt("valid", false)), plan.attempts)
        val unsupported = VideoDecoderSelection.plan("video/vp9", listOf(candidate("avc")), false)
        assertFalse(unsupported.hardwareOnly)
        assertTrue(unsupported.attempts.all { it.codecName == null })
    }
}
