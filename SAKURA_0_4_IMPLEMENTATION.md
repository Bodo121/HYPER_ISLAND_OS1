# SakuraIsland 0.4 — implementation and device QA

All changes are committed on the EXISTING feat/stitch-sakura-ui-integration branch. No extra PR, branch, or workflow was created. The only GitHub Actions workflow is the manually triggered .github/workflows/build.yml.

## Implemented

- Native Xiaomi standard heads-up replacement: SystemUI notification collection and coalescer hooks, original notification manager display hook, plus the OEM-verified MiuiNotificationInterruptStateProviderImpl.checkHeadsUp and HeadsUpCoordinator shouldHeadsUpEver/shouldHeadsUpAgain decision paths. The original visual popup is skipped ONLY if the matching noncritical alert is already displayed as an expanded, attached SakuraIsland card. Android shade entries, sound/vibration, calls, alarms and full-screen notifications remain native.
- Notification redesign: adaptive narrower card, 2–6 preview lines, most recent MessagingStyle text or BigText, title/icon alignment, original PendingIntent actions, and configurable duration.
- Notification queue: tap Next in the card or swipe the compact island; urgent events retain priority and queue selection expires.
- Actionable Xiaomi Security/PowerKeeper 'Stop boosting charging speed' notification is recognized and prioritized above the passive battery status so the original Stop action remains accessible in SakuraIsland, while unrelated system warnings remain protected.
- Camera-safe compact states: the physical lens is a reserved central zone; charging percent appears at the right and the app/status icon stays on the left. Dedicated compact/expanded charging, call and timer layouts use real data.
- Xiaomi typed charge-toast observer: model methods getCharge and getChargeLevel were confirmed in the user's MiuiSystemUI.apk. It only intercepts a genuine charging strong-toast if a charging island is visible. The fractional OEM battery percentage is displayed only when consistent with Android's integer battery report (within 3 percentage points). Actual charging watts are NOT inferred.
- Improved motion easing, bounded icon caching, throttled host layout updates, optional haptic feedback, safe actions, ROM-specific checks and clean uninstall/LSPosed fallback.
- Settings controls: preview lines, duration, queue, notifications, OEM heads-up replacement, OEM charging-toast replacement, charging expansion, haptics, camera offset and motion.
- Added JUnit tests for camera-safe sizes, alert heights, selective OEM popup replacement policy, queue rotation, and trustworthy battery percentage.

## Not yet safely implemented

- Dolby Atmos ACTIVE state or toggling: support property is true, but Xiaomi quick-settings plugin has a separate class loader; the current hardware badge is not a live state reading.
- Exact LIVE charging wattage: getChargeLevel is a battery percentage, not charger input watts.
- Inline message reply: a secure, focusable RemoteInput flow must be designed and tested. Direct non-text notification actions are implemented.
- Launcher cross-process native morphs and proprietary HyperOS blur: require verified runtime interfaces. SakuraIsland currently uses safe View-based transitions.
- It remains possible that another Xiaomi-specific HUN or charge animation path bypasses these guarded hooks. A phone-generated diagnostic report must confirm interception before any stronger suppression is attempted.

## Build and verify

1. Open https://github.com/Bodo121/HYPER_ISLAND_OS1/actions/workflows/build.yml
2. Click Run workflow; select feat/stitch-sakura-ui-integration, then debug.
3. Download the SakuraIsland-debug artifact after a successful build.
4. In LSPosed scope only com.android.systemui; reboot. Keep a root-manager safe mode fallback.
5. Test: long and repeated WhatsApp messages, scrolling a queue, one alert action, expanded card with keyboard showing, and only one visual heads-up while the notification remains in the shade.
6. Check charge plug/unplug and compare the decimal battery percentage with Xiaomi's display. Test a normal noncharging Xiaomi strong-toast too.
7. Confirm the diagnostic lines HOOK heads-up policy, Heads-up decision hooks installed, Replaced OEM heads-up visual, and Replaced Xiaomi charging toast.
8. Test important calls, alarms, lockscreen, screen rotation, media controls and 30-minute stability. Disable replacement toggles if any alert is missed.

Do NOT globally disable Android heads-up, alter /sys power limits, or upload Xiaomi proprietary APKs. Do not merge or publish a stable APK before successful on-device validation.

## RemoteInput reply implementation and visual notification ownership

- The notification card shows a real **Reply** button only when the originating app publishes a free-form Android RemoteInput action. It opens a focusable, system-owned dialog with the keyboard and calls RemoteInput.addResultsToIntent + the original PendingIntent. No fake reply control is shown for apps without compatible actions.
- After sending, an exact outgoing-text echo of that notification is ignored for up to six seconds so a user doesn't see their own sent message as a new island alert. New, different incoming messages under the same conversation key are still processed. Reply text stays in volatile SystemUI memory and is not persisted or logged.
- Existing Xiaomi heads-up and eligible native charging-toast interception remain selective and reversible; ordinary notifications remain present in the Android notification shade and retain sounds/vibrations. The module deliberately does **not** blanket-disable every system toast, which would remove privacy indicators, low battery/security notices and call/emergency UI on this ROM.
- On-device tests: WhatsApp Reply, Send and Cancel, keyboard opening, new incoming message just after sending, duplicate Xiaomi heads-up in both app drawer and browser, and a critical incoming call. In Settings, ensure Replace Xiaomi pop-up banners is enabled. Export only sanitized diagnostics.
