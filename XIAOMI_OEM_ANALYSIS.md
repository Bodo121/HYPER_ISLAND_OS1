# Xiaomi 12 Lite OEM integration investigation

Source: user-provided `for xiaomi implementation.zip` (~245 MB compressed, 494 MB uncompressed), inspected on 2026-11-02. **Do not commit or redistribute the proprietary APKs.** This document records class and method names only.

## Supplied material

11 top-level APKs: `MiuiSystemUI.apk`, `MIUISystemUIPlugin.apk`, `MiuiHome.apk`, `MISettings.apk`, `MIUISecurityCenterGlobal.apk`, `PowerKeeper.apk`, `MIUIDeskClockGlobal.apk`, plus SystemUI resource overlays. A full `framework/` directory was also provided, including `framework-res.apk`, `framework.jar` and `services.jar`.

The ZIP does **not** include actual device runtime screenshots of charging state, `/sys/class/power_supply`, audio effect settings, live Dolby property values, or a SystemUI class loader map. Those must be discovered on the phone before exposing live watts or active Dolby state.

## Verified by inspecting installed APK DEX string/method tables

### 1) Native SystemUI toasts and charging

`MiuiSystemUI.apk` contains:

- `com.android.systemui.toast.MIUIStrongToast` with `showCustomStrongToast(StrongToastModel)`, `getWindowParam()`, `hideStrongToast()` and animation methods.
- `com.android.systemui.toast.MIUIStrongToastControl` and `MIUIStrongToastUtils`.
- `com.miui.charge.container.MiuiChargeAnimationView`, with `getWindowParam()`, `setProgress(int)`, `startDismiss(String)`, `setChargeAnimationListener(...)`.
- `com.miui.charge.container.MiuiChargeContainerView` with `setProgress(int)`.

The LSPosed module now **probes** these classes on its strict ROM target without hooking or mutating them. Presence is verified by the SystemUI class loader, not by string matches alone.

Native charge animation != continuous measured charging power. The implementation continues to use battery broadcasts until validated OEM charging events or sysfs values are available.

### 2) Native Dolby and sound effects

`MIUISystemUIPlugin.apk` contains:
- `miui.systemui.quicksettings.soundeffect.DolbyAtomsSoundEffectTile`, with `getDolbyEffectStatus(): boolean`, `isAvailable(): boolean`, `setDolbyEffectEnable(boolean)`.
- `miui.systemui.quicksettings.soundeffect.dirac.AudioEffectCenter`, with `isEffectActive(String): boolean`, `isEffectAvailable(String): boolean`.
- String constants `dolby_active`, `dolby_available`, `ACTION_SYSTEM_UI_DOLBY_EFFECT_SWITCH` and `ro.vendor.audio.dolby.dax.support`.

These are potentially useful *read-only* status APIs, but the quick-settings plug-in uses a separate class loader/lifecycle. Do not call or hook methods blindly inside SystemUI. We need verified runtime class loading, configuration, and callback behavior before showing a live ON/OFF label. Do **not** enable/disable Dolby through the island without an explicit user-facing toggle and safe capability checks.

### 3) Xiaomi launcher island transitions

`MiuiHome.apk` includes:
- `com.miui.home.launcher.anim.DynamicIslandAnimInterruptHelper`.
- `com.miui.home.recents.util.DynamicIslandParamsCalculator`.
- `com.miui.home.recents.anim.DynamicIslandInterruptInfoParser`.
- Methods for island animation start/end/interruption and target RectF calculations.

Presence in launcher code does **not** prove dynamic-island animations are enabled on this phone. Coordinating cross-process transitions would require observing events/parameters and version-specific compatibility testing before hooking.

### 4) Framework/display cutout

Both `framework-res.apk` and `MiuiSystemUI.apk` are present. The application still uses `DisplayCutout.boundingRectTop` from the running phone, avoiding hardcoded physical pixels. It now has configurable idle dimensions independent of media:

- Default idle: **104 dp × 34 dp**.
- Adjustable 72–132 dp width, 28–44 dp height.
- Width/height may increase to fully cover the detected camera cutout.
- Compact media retains an independent width; both remain bounded by available clock/icon space.

This specifically addresses the screenshot showing an approximately circular idle island instead of a horizontal pill.

## Safe follow-up validation

The phone must confirm actual loaded classes and their logs, package versions and user-visible states before we build native charging/quick-settings/launcher hooks. Useful read-only commands include:

```sh
adb shell wm size
adb shell wm density
adb shell getprop ro.mi.os.version.incremental
adb shell getprop ro.vendor.audio.dolby.dax.support
adb shell pm path miui.systemui.plugin
adb shell su -c 'ls /sys/class/power_supply'
```

No writable root properties, sysfs mutations, or SystemUI replacement APKs are required. If additional diagnostics are shared, exclude personal notification content, identifiers, account data, and raw system logs.

## Priorities

1. Test idle appearance on light and dark background and adjust its two sliders. Record runtime cutout bounds if misaligned.
2. Compare SystemUI's native toast/charging animation behavior with SakuraIsland; avoid duplicate overlapping displays.
3. Confirm live Dolby status read in the plug-in class loader before offering an 'active' badge.
4. Validate charger detection and rate reporting via trustworthy OEM fields, never by a fixed 67W number.
5. Consider launcher transition hooks only after stability testing and reversibility planning.

The archive is strong source evidence for *existence of native code*, not proof that all features are enabled and operating on the currently installed ROM.
