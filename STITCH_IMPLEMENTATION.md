# SakuraIsland — Stitch UI integration (v0.3 experimental)

Source: user-supplied `sakuraisland_jetpack_compose_ui_code_export (1).txt`, based on the visual Stitch concept.

## Architecture

The exported source was a **UI concept**, not a drop-in patch. It referenced a missing `IslandState` class, an unrelated `com.miui.sakuraisland` package, placeholder playback commands, fake 67W/sound-quality telemetry, and hardcoded device metrics. Using its `ComposeView` within injected SystemUI without careful lifecycle setup could destabilize the phone.

The project therefore preserves its existing `io.github.sakuraisland` ID and SystemUI/LSPosed hook architecture:

* **App settings**: `StitchSettingsScreen.kt` uses Jetpack Compose Material3, fully bound to the existing Settings `ContentProvider`. It has editable activities, appearance, camera offset, speed, app filter, diagnostics, test notification, and a labeled *demo* visual preview.
* **SystemUI island**: `IslandVisuals.kt` renders Android platform Views (no Compose runtime in the SystemUI process), driven by `IslandRuntime.kt`.
* **No overlay permission**, new background daemon, fake audio badges, or sensor data.
* All changes are isolated on `feat/stitch-sakura-ui-integration` and leave the release signing identity intact.

## Implemented visual states

| State | Source of truth | Behavior |
| --- | --- | --- |
| Idle camera capsule | Enabled, unlocked, portrait | Passive non-touchable 40dp minimum width, measured from real top display cutout |
| Compact media | SystemUI MediaSessionManager | Real artwork, playback-aware animated equalizer; artwork is never represented by fake music data |
| Expanded media | SystemUI MediaController | Album-derived ambient backdrop, current title/artist, real timeline and seek when ACTION_SEEK_TO is supported, genuine previous/play/next actions |
| Notifications | SystemUI collection callbacks | Real notification app icon, text, and content PendingIntent when available |
| Call and timer | Notification category | Category-appropriate presentation, without claiming native call handling not present in source |
| Charging | BatteryManager sticky broadcast | Real integer battery level. No unsupported fractional percentage or guessed charging wattage |
| Settings | ConfigProvider preferences | Real toggles, sliders persisted on release, app filter, compatibility report, self-test |

## Cutout / status icon policy

Compact width is automatically limited by visible status icon/clock bounds when SystemUI can expose them; the camera location uses actual `DisplayCutout.boundingRectTop` and a user-adjustable vertical offset. Expanded backgrounds leave the top outside the camera capsule transparent, while the larger card extends below the status row.

Android cannot safely reflow all apps' launcher content with a normal window overlay. Expanded cards necessarily occupy screen real estate; no fake claim of complete icon collision immunity is made. Device-specific UI validation remains necessary.

## Features intentionally not faked

* **Inline quick reply** needs a secure, focusable text-input flow and a genuine `RemoteInput` action. The current implementation only offers the real notification content action.
* **Dolby Atmos** and **67W maximum Turbo Charging** are officially documented hardware capabilities of Xiaomi 12 Lite. We now label them as *supported* only on `Build.DEVICE == "taoyao"`; these labels do **not** claim active Dolby processing or current charging wattage. Live Hi-Res 96kHz, Buds device selection, and exact active wattage remain unimplemented. The uploaded UI had mock telemetry.
* **Battery decimal percentages** are not exposed by regular battery broadcasts and are not fabricated.
* **Native HyperOS blur** and modifications to other applications' layouts require separate runtime capabilities and testing. A native gradient fallback is used.

## On-device validation

1. Install debug APK on **exact ROM** `OS1.0.39.0.ULIEUXM`, Xiaomi 12 Lite / Android 14; enable LSPosed scope **only com.android.systemui**, reboot.
2. Open settings. Enable SakuraIsland and its activities. Test default idle pill, compact media, paused/playing, expanded controls and seeking in apps that expose a MediaSession.
3. Check clock, notification icons, signal and Wi-Fi in home, app drawer, notifications shade, full-screen apps, incoming calls, timer, screen-off/on and rotation.
4. Plug/unplug charger; validate % against OS battery level. Check battery drain, SystemUI memory, animations and event expiry.
5. Share **Compatibility diagnostics** if there are crashes or misplaced UI. Back out by disabling SakuraIsland in LSPosed and rebooting. Root manager safe mode is fallback if SystemUI cannot start.

## Build

`./gradlew --no-daemon assembleDebug testDebugUnitTest lintDebug`

GitHub Actions uploads a debug APK from successful builds. Do **not** merge to main or publish an untested release before physical-phone QA.
