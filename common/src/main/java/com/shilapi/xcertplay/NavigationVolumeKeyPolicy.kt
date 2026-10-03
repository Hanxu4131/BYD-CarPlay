package com.shilapi.xcertplay

/** Uses the resumed session's ownership and a confirmed legacy navigation route. */
internal object NavigationVolumeKeyPolicy {
    fun shouldSelectNavigation(enabled: Boolean, resumed: Boolean, sessionOwner: Boolean,
        connected: Boolean, navigationActive: Boolean, legacyStreamType: Int?): Boolean =
        enabled && resumed && sessionOwner && connected && navigationActive && legacyStreamType == 14
}
