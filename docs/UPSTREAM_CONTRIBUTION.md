# DiPlay contribution proposal: 2023 Tang DM-i / platform 21

Adaptation author and vehicle tester: **寒叙 ([@Hanxu4131](https://github.com/Hanxu4131))**.

I have adapted DiPlay for my 2023 BYD Tang DM-i with platform/controller 21 firmware. The starting point was DiPlay 0.2.9, commit `18429e737228e8d75d9b6c850af89dcca2f591b6`. The local name BYD CarPlay is for my own build. An upstream contribution should retain the DiPlay name, application ID, versioning and existing attribution.

The most useful changes to review are:

1. Legacy instrument-display targeting and a dedicated map Activity. Display availability and target identity are checked before launching; the map is placed in the instrument's available region while native speed and gear indicators remain visible.
2. Separate overall-map and navigation-region editors with live preview, fine movement, scaling, save and cancel. A local maneuver card can be moved independently; this does not claim to split arbitrary content already composited into the iPhone video.
3. Quiet instrument-start recovery at five-second intervals, cancelled when an actual frame is presented, the session ends, or the option is disabled. A valid active window is not repeatedly relaunched merely to take the foreground.
4. Optional L1Mini ordering and bounded wake recovery. The camera windows and map have been observed together on this vehicle. No L1Mini APK or implementation is included, and a cross-process fade is not implemented.
5. Dudu launcher embedding, connected-session return and H.264 view-area updates. Split/full-screen transitions can retain the same CarPlay session and ask the iPhone to lay out the declared view area. Geometry confirmation, bounded retries, touch mapping and a transition cover handle the resize. Bidirectional split/full-screen operation has been confirmed on the test vehicle. HEVC is outside this validated resize path.
6. Live head-unit appearance updates and optional direct OEM song metadata publication for the older platform, while MediaSession remains available to launchers regardless of the direct-OEM display option. The artist display is configurable.
7. Restart control and configurable steering-wheel media, call, Siri and Home actions, subject to the head unit delivering the button events.

The test31 adaptation passed 54 focused startup/resize tests, a debug build and lint without errors in the local verification record. Other modules have their own regression records. Vehicle confirmation is separate from automated tests; the complete cold-boot failure/retry scenario still needs a targeted reproduction.

I suggest small pull requests in this order: legacy display targeting/recovery; editors and local guidance; H.264 viewport/lifecycle support; optional L1 coordination; older-platform appearance/OEM metadata; button controls. Each should be rebased and tested on current main rather than copying the modified 0.2.9 tree over 0.2.10. The updated upstream MediaSession and artwork implementation should be retained and extended, not replaced.

Navigation-volume wheel routing and actual CarPlay Ultra instrument themes are not implemented and should not be described as supported. The original audio stream selector already offers auto/0 and 1–20. Private branding, Apple-derived startup artwork, local authentication/signing assets, device logs, third-party binaries and decompilation material should stay out of upstream patches. Existing diagnostics should remain available upstream even though my private build hides their settings sections.
