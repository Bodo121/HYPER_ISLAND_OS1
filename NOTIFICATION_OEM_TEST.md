# SakuraIsland — Notification & OEM verification (2026-11-02)

## Evidence from user's uploaded diagnostics

The ROM is Android 14 (`SDK=34`), Xiaomi 12 Lite `taoyao`, `OS1.0.39.0.ULIEUXM`. Native SystemUI classes `PhoneStatusBarView`, `NotifPipeline`, `NotifCollection$1`, Xiaomi charge animation, and MIUIStrongToastControl are all found on the SystemUI classloader. Earlier logs showed two notification callbacks installed, but **no actual notification-delivery log**, so successful display was not proven.

An additional probe pass in the same diagnostic report says many classes are missing; that may reflect a different initialization/classloader pass, not actual removal of the classes. We must keep this inconsistency visible until tested.

`ro.vendor.audio.dolby.dax.support=true` confirms Dolby DAX hardware/ROM support, **not whether processing is currently enabled**.

The display cutout was measured at x=504..576, y=0..98 physical pixels at density 2.75. Idle geometry defaults to 104 × 34 dp, constrained to the actual cutout.

## Changes introduced

- `all_apps=true` by default, with UI switch to opt into the legacy package allowlist. Previously only `com.whatsapp` and `org.telegram.messenger` were routed.
- Three **read-only** native SystemUI notification observation points: `NotifCollection$1.onNotificationPosted`, `GroupCoalescer$1.onNotificationPosted`, and `NotifCollection.postNotification`. Native notifications remain intact. Short key-based dedup avoids double island events if multiple stages receive the same callback.
- Logs record **package and routing outcome only** (`Notif accepted`, `Notif filtered by settings`, `Notif ignored`). No sender, message, notification key, or body content is saved in diagnostics. Diagnostic provider receives updates after a notification is accepted or rejected by settings.
- Group summaries, secret notifications, media transport, and background foreground-service entries are not shown as normal island alerts. Calls and alarms retain independent toggles.
- Native application launcher icons are preferred to tiny white small-notification icons. Notifications now use smaller adaptive-height (~174–225dp) cards with up to four preview lines, preferring MessagingStyle's latest text or longer BigText when provided. One safe native action such as 'Stop' is mirrored into the island using its original PendingIntent.
- Root panel touch handling no longer swallows touch events intended for player buttons and seek bar.
- Xiaomi native `MIUIStrongToastControl.getSupportCharge(MiuiBatteryStatus)` return values are observed **without intercepting/altering charging behavior**. If the OEM returned label clearly indicates turbo/hyper/quick charging during an existing charging event, the island may show a Turbo-charging hint. No numeric wattage is invented.

## What remains unverified

- No APK inspection can tell whether notification callbacks deliver events on the user's running session. **On-device alert tests are required**.
- Native **visual heads-up banners are now optionally replaced** (setting enabled by default), but *only* if the matching standard app notification is displayed in an expanded SakuraIsland card and a valid open intent or copied simple action is available. Calls, alarms, full-screen, secret, system-error and other protected notifications keep OEM behavior. Native shade entries, audio/vibration and OS notification delivery are not disabled. If interception fails, OEM heads-up is the fallback.
- No root daemon is used. LSPosed already injects into the privileged SystemUI process and supplies the required event source.
- Dolby active status and actual charging wattage still need trusted runtime hooks or measurements. A hardware support label is not an active-state indicator.
- Xiaomi Miui Home transition parameters and MIUISystemUIPlugin classes may use different runtime loaders; automatic cross-process hooking has not been enabled.

## Minimal test plan

1. Update the latest debug APK and ensure the module is scoped to `com.android.systemui` in LSPosed; reboot SystemUI/device.
2. In SakuraIsland Settings: enable Island, Notifications, **All apps**. Keep Calls/Timers on as desired.
3. Switch on **Replace Xiaomi pop-up banners**, and post the built-in *Test multiline + banner replacement* alert. The test now uses a fresh HIGH-importance notification channel so a heads-up is eligible. Check that the alert appears in the island, not simultaneously as a normal Xiaomi banner. Then test a WhatsApp message and Gmail.
4. Open **View SystemUI report** and check for `Notif accepted: package=...` or `Notif filtered by settings: ...`.
5. If no `Notif accepted` log appears, inspect the `HOOK` lines. Confirm `Guarded heads-up replacements installed=...` and `Replaced OEM heads-up visual` for eligible messages. If that log is missing, the OEM display is deliberately preserved. Compare against the Android shade.
6. Test music buttons and scrubber after tapping the island. Make sure native notification actions and incoming call behavior remain unchanged.
7. Test plug/unplug. Check native charging code logs, if present, without asserting live wattage.
8. If SystemUI crashes, disable the module in LSPosed and reboot. **Do not merge to main until this test passes on the phone.**

The project intentionally leaves OEM APKs, accounts, private notifications, and privileged firmware binaries out of the repository.

## Rollback

Turn OFF `Replace Xiaomi pop-up banners` in SakuraIsland Settings to restore Xiaomi's visual heads-up behavior without removing its notification entry or changing Android global settings. If LSPosed or SystemUI becomes unstable, disable SakuraIsland in LSPosed and reboot. Do not disable Android notification permissions or run global `settings put global heads_up_notifications_enabled 0`; that can hide alerts even when the island fails.
