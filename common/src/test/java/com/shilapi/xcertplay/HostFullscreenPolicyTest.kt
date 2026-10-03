package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class HostFullscreenPolicyTest {
    @Test fun onlyMainDisplayAndKnownFullWidthCanOverrideBars() {
        assertTrue(HostFullscreenPolicy.overrideBars(0,1920,1920,1920,990,1080,false,"fullscreen"))
        assertTrue(HostFullscreenPolicy.overrideBars(0,1920,1920,1920,990,1080,false,null))
        assertFalse(HostFullscreenPolicy.overrideBars(28,1920,1920,1920,1080,1080,false,"fullscreen"))
        assertFalse(HostFullscreenPolicy.overrideBars(0,1284,1920,1284,990,1080,false,"fullscreen"))
        assertFalse(HostFullscreenPolicy.overrideBars(0,1920,1920,1920,1080,1080,true,"fullscreen"))
        assertFalse(HostFullscreenPolicy.overrideBars(0,1920,1920,1920,1080,1080,false,"freeform"))
        assertFalse(HostFullscreenPolicy.overrideBars(0,1920,1920,1920,500,1080,false,null))
    }
}
