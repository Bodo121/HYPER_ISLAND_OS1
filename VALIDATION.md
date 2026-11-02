# Validation — 2026-11-02

## 0.1.1 update

After inspecting the user-supplied files, `assembleDebug testDebugUnitTest lintDebug` passed again. Seven tests passed with zero failures/skips; lint reported zero errors and 14 warnings. The three added regressions cover short marketing names, unknown versions and conflicting full-version precedence. See [ROM_INSPECTION.md](ROM_INSPECTION.md) for the verified APK contracts. The supplied diagnostic confirms module execution, but the new renderer has not been exercised on the phone. The earlier build/run details below refer to 0.1.0.

## Executed locally on Windows

- Gradle 8.13 / Temurin JDK 17.0.20.1 / Android platform 35.
- `assembleDebug testDebugUnitTest lintDebug`: **BUILD SUCCESSFUL** for final implementation.
- Four JVM tests passed, zero failures/skips: exact-target gate; call priority over media and expiry fallback; latest event on equal priority; hidden state with no valid events.
- Android lint: **0 errors, 14 warnings**. Warnings include intentionally pinned older target/dependency versions, private SystemProperties reflection, runtime SystemUI resource lookup, UID-checked exported provider, static host ownership (released on detach), backup-rule recommendation and Kotlin style suggestions. They have not been suppressed to make the report appear clean.
- `apksigner verify --verbose --print-certs`: **Verifies**, APK v2 signature valid, one Android Debug signer, RSA 2048.
- `aapt dump badging`: `io.github.sakuraisland`, version `0.1.0-experimental`, min/target SDK 34, label SakuraIsland.
- No native libraries are required; the bytecode APK is usable on ARM64 subject to OS/LSPosed compatibility.
- Build-time analytics could not write outside the workspace; this warning did not prevent build or lint completion.

Debug certificate SHA-256: `a1384515027ad95732d458f6eaf2fc397068b3b6f757d80c5c10d539a801eddc`.

## Hosted CI

GitHub Actions run https://github.com/Bodo121/HYPER_ISLAND_OS1/actions/runs/41473526403 completed successfully for commit `efde4765f4581c3e0454df805d143412996de6bc`. SDK setup, `assembleDebug testDebugUnitTest lintDebug`, APK upload and report upload all passed. The first run failed because the Android setup action requested the removed `tools` package; both workflows now explicitly request platform 35 and Build Tools 35.0.0.

The hosted APK uses its own runner-generated debug certificate; it is not byte-identical to the locally delivered APK. Source archive integrity was checked against all 29 project files. `adb devices -l` returned no connected devices.

## Not executed / not established

- No emulator install or visual UI test.
- No connected phone was available; no install, restart, firmware flashing or device modification.
- No physical-device notification/media event test, LSPosed runtime recognition, SystemUI class-resolution result, cutout calibration or stability test.
- No signed production release. The APK is a signed **debug experimental artifact**.

## Hardware acceptance checklist

1. Exact-target diagnostic passes; expected status-bar class and notification signatures found.
2. Island remains absent with the master switch off and after disabling it.
3. Built-in test notification expands and opens the settings app.
4. WhatsApp and Telegram selected-app events work; removal/expiry restores media.
5. Music artwork/title/artist and advertised transport controls work; switching players and destroying sessions releases old callbacks.
6. Incoming/missed call and clock events work when categorized; original call UI/notifications remain accessible.
7. Screen lock/off hides all island content; rotation hides the island; unlock returns only unexpired events.
8. Repeated settings changes and status-bar recreation do not duplicate windows/listeners.
9. Icon overlap handling is assessed separately before turning it on.
10. Disabling via LSPosed and rebooting restores stock behavior.

Only after these tests on OS1.0.39.0.ULIEUXM should device compatibility be reported.
