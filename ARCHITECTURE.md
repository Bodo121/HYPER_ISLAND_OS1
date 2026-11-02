# Architecture

`SystemUiHook` → validated constructors/callbacks → `IslandRuntime` → SystemUI-owned WindowManager panel.

- **Core**: `Policy` selects the highest-priority unexpired event; newest wins ties. Calls 100, alarms/timers 80, selected messages 60, media 40, charging 20.
- **Hook layer**: exact Android/device/ROM gate; main SystemUI process only. Status bar constructors get lifecycle listeners. Private callback methods must match name, first parameter and void return. Optional notification hooks are isolated. No native method results are changed.
- **Rendering / animation**: bounded native layouts, cutout-derived center, compact camera gap, expanded content below camera, ValueAnimator resizing. A type-2017 window belongs to SystemUI, is non-focusable and does not intercept outside its rectangular bounds. No TYPE_APPLICATION_OVERLAY window or standalone overlay service exists.
- **Notification controller**: observes native collection callbacks, filters current user, secret notifications, summaries and app allowlist/categories. Stores at most 32 events and short-lived text. Native notifications remain authoritative.
- **Media controller**: session-list and per-session callbacks, max eight sessions; playing preferred over paused; detach unregisters callbacks. No polling/audio capture.
- **Configuration**: Material 3 app writes an allowlisted content provider. SystemUI reads on a single worker and receives content-observer updates. Unknown or unavailable settings fail disabled. Diagnostics writes cannot change settings.
- **Diagnostics**: at most 200 lines, 500 characters per line, with a 32,000-character provider limit; no notification contents or media metadata. Shared report is user initiated. Reflection reports are bounded to 12 methods per candidate plus installed hook signatures.
- **Safety**: master off by default, portrait/unlocked/interactive gating, clean detach, native behavior preserved, three caught runtime errors disable rendering for that host lifetime. Fatal VM failures are not hidden. This is error isolation, not a proven crash-loop detector.

Required validation: target hook discovery → SystemUI window add success → one test notification → media controls → simultaneous priority → lock/unlock/rotate → reboot/disable recovery. Do not infer hardware success from compilation or JVM policy tests.
