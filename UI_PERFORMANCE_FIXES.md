# SakuraIsland 0.4 — Xiaomi 12 Lite UI / performance patch

Target: Xiaomi 12 Lite (taoyao), Android 14, HyperOS OS1.0.39.0.ULIEUXM.

## Screenshot-led fixes

- Expanded music: the camera surround / music card used two independently rounded rectangles, producing a dark 'bulb' at their boundary. The native draw now merges the cap into the card with a flush rectangle bridging their overlap.
- Playback controls: transport bar now has additional bottom spacing; the music card height also increased to avoid clipping the buttons.
- Animation jank: SystemUI may trigger layout callbacks every frame. The window size animator now retains its current target and refuses to restart for duplicate requests, uses a smooth path interpolator and keeps window updates on animated size changes.
- High refresh preference: optional `high_refresh=true`. The island window uses `WindowManager.LayoutParams.preferredRefreshRate` with the highest actual advertised panel mode up to 120 Hz. This is a request only; HyperOS power saving / thermal policy, app conditions and system display settings can still run at 60 Hz. There is no safe guarantee of 120 frames per second.
- Notification banners: earlier hooks intercept Android heads-up showNotification and Xiaomi `checkHeadsUp` decisions only if the same notification is already displayed in the island. **No global notifications or shade entries are disabled.** If native duplicate banners survive, collect diagnostics to identify the remaining Xiaomi method/class; don't globally disable all Android visual alerts at the cost of losing critical events.

## On-device verification

1. Manually start the existing `Build SakuraIsland APK` GitHub Actions workflow on `feat/stitch-sakura-ui-integration`, variant `debug`. No new workflows, branches or PRs.
2. Ensure compile succeeds; install the freshly generated APK and reboot the SystemUI/phone.
3. Test expanded music screen, pause/next/previous and scrubbing with keyboard displayed and hidden.
4. Observe whether the top join is flush and no dark protrusion appears around the physical lens.
5. Test smooth compact ↔ expanded motion with the phone set to 120 Hz in Xiaomi Settings. The setting requests (does not force) 120 Hz.
6. Receive a WhatsApp notification. Compare island with native Xiaomi banner and report diagnostic lines for heads-up hook installation/replacement. Native critical alerts remain preserved.
7. Switch off `Replace Xiaomi pop-up banners` for any lost ordinary heads-up. If SystemUI crashes disable SakuraIsland in LSPosed and reboot.

Status: code updated, physical-phone rendering/120Hz and duplicate-banner interception **not yet verified**.
