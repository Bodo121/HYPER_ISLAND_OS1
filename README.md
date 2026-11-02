# SakuraIsland

Experimental SystemUI-integrated island for **Xiaomi 12 Lite (taoyao), Android 14, OS1.0.39.0.ULIEUXM**. This is an original Kotlin implementation informed by upstream research, not a binary port of HyperOS 3. **Physical-device compatibility is not established.**

## What is implemented

- Legacy Xposed/LSPosed entry point, scoped to the main SystemUI process.
- Exact device/API/ROM gate, runtime class and method inspection, bounded diagnostics.
- SystemUI-owned type-2017 status-bar panel, tied to the PhoneStatusBarView lifecycle. No application overlay permission, notification-listener app service or polling loop.
- Camera-cutout positioning, adjustable compact dimensions, expanded card, animated resizing, hidden state, portrait-only rendering.
- Native notification collection observers: selected apps (WhatsApp/Telegram by default), categorized call/missed-call and alarm/stopwatch notifications. No notification suppression.
- MediaSessionManager callbacks, artwork, title/artist, supported previous/play/pause/next actions and session activity launch.
- Charging transitions, event priorities and expiration, tap expansion, explicit Open button and local swipe dismissal.
- Material 3 day/night configuration app, in-app interactive preview, switches, geometry sliders, package allowlist, diagnostic sharing and test notification.
- Provider bridge limited to this app and the SystemUI UID. Only the app can change settings. Logs exclude notification content.
- Hidden while locked, screen off or in landscape. Native call screens and heads-up notifications continue working.

## Install and first test

1. Keep a working recovery path (LSPosed manager / root manager safe mode, or previously authorized ADB). Read [RECOVERY.md](RECOVERY.md).
2. Install the debug APK manually. It is signed with a development certificate, not a production key.
3. Enable SakuraIsland in LSPosed, selecting **System UI (`com.android.systemui`) only**. Restart the phone.
4. Open SakuraIsland → View compatibility report. Confirm the exact-target gate and status-bar/notification hook lines. Missing classes mean the related feature cannot work on that build.
5. Enable SystemUI island. Post the test notification (grant notification permission, then tap again). Test on an unlocked portrait screen.
6. Start a music player that publishes a media session; test controls, screen lock, unlock, rotation and simultaneous messages.
7. Share diagnostics for compatibility work. No device installation, restart or flashing was performed during development.

The master switch is off by default. Changes apply through a content observer; enabling LSPosed itself requires a SystemUI restart/reboot. An exact-target mismatch allows diagnostics only. A build fingerprint match is a safety gate, **not proof that private hooks work**.

## Build

JDK 17, Android SDK platform 35 and Build Tools 35.0.0; Gradle 8.13, AGP 8.9.2, Kotlin 2.1.20. Configure `ANDROID_HOME` or an untracked `local.properties`.

```sh
./gradlew assembleDebug testDebugUnitTest lintDebug
```

Windows: `gradlew.bat assembleDebug testDebugUnitTest lintDebug`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
The debug signing key is generated locally by the Android build tooling and is never included in source. Builds on different machines have different debug signatures; uninstalling an older development build may be necessary (removes its settings).

GitHub Actions builds and uploads the debug APK and reports. The manual release workflow requires the `release` environment and `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets. It never substitutes a debug key for a missing release key. No production signing credentials are generated or committed. Dependency versions are pinned; builds are reproducible from source, not claimed byte-identical across signing environments.

## Remaining limitations

- No emulator or physical HyperOS test has been performed. Hook availability, SystemUI window permission, touch routing, LSPosed recognition and positioning require device validation.
- Exact firmware SystemUI APK was not extracted. No verified notification or media event on the target hardware can yet be claimed.
- Notification events are captured after host attachment; existing notifications are not replayed. Paused media expires after 15 seconds. Message cards expire after 8 seconds; call/alarm cards after 30 seconds or native removal.
- Calls/timers depend on notification categories; no direct telephony hook, live countdown parsing, answering calls, inline replies or vendor clock integration.
- No audio-capture visualization. The music symbol is an indicator, not an audio spectrum.
- Overlap-based icon hiding is optional and disabled by default; it must be tested against Xiaomi's layout. Native heads-up may overlap the island.
- Island content is always black with white text; configuration follows system light/dark theme. Hidden on lock screen, secondary users and landscape rather than attempting unverified privacy integration.
- Runtime error isolation reduces risk but cannot guarantee prevention of SystemUI crashes or reboot loops.

See [RESEARCH.md](RESEARCH.md), [ARCHITECTURE.md](ARCHITECTURE.md), [VALIDATION.md](VALIDATION.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

