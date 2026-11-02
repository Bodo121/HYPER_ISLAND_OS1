# User-supplied ROM inspection — 0.1.1

Files supplied locally by the device owner on 2026-11-02, identified by the owner as the target phone's files. These are not independently authenticated full firmware images. APK binaries and decompiled proprietary sources are kept outside the public project and are not redistributed.

| File | Package/version | SHA-256 |
| --- | --- | --- |
| MiuiSystemUI.apk | com.android.systemui / 20230316.0 (202303160), min/target SDK 34 | 82334baf6250fba2d23969139236ef0a93f5d00b3f59a0d585ebde738378fa44 |
| MIUISystemUIPlugin.apk | miui.systemui.plugin / 15.0.3.69.0 (150036900) | 3dafd9e068ebee7e88344ae1c7d146c7e2d41e79b5c52b7736cd3e58be0cc999 |
| XiaomiServiceFrameworkGlobal.apk | com.xiaomi.xmsf / 6.1.1-G (60001001) | 9ef47c803fd92e033e847c27c3935df644ccb95df375d80254184da7f381abdd |

## Evidence

The 4,131-byte supplied diagnostic says SDK 34, device taoyao, `ROM=OS1.0; exactTarget=false`. It lists PhoneStatusBarView, StatusBarWindowController, CentralSurfacesImpl and NotifPipeline as found, and legacy StatusBar as missing. It ends mid-signature. This proves execution of SakuraIsland's diagnostic hook in the reported environment, but not rendering, media controls or an exact full ROM version.

JADX 1.5.6 inspected SystemUI and the plugin locally; aapt inspected all three manifests. JADX reported 47 SystemUI and 17 plugin decompilation errors across the full APKs, so the output is not treated as a complete source reconstruction. The specific methods below are readable and do not contain failed-method placeholders.

- `NotifCollection$1` implements `NotificationListener.NotificationHandler`; its exact callbacks are `void onNotificationPosted(StatusBarNotification, NotificationListenerService.RankingMap)` and `void onNotificationRemoved(StatusBarNotification, NotificationListenerService.RankingMap, int)`. The module now validates these full signatures. Native code asserts main-thread dispatch; the module continues to schedule its observer work on the main thread without changing native results.
- `PhoneStatusBarView` extends FrameLayout, with `(Context, AttributeSet)` constructor and attach/detach methods. `MiuiPhoneStatusBarView` extends it. This supports capturing the base constructor and attaching a lifecycle listener without hardcoding a vendor subclass.
- `MIUIStrongToast.getWindowParam()` creates a type-2017 WindowManager window and calls `setTrustedOverlay()`, with top/center gravity. SakuraIsland uses the same window type inside SystemUI, now marks it trusted through a guarded reflection call, and calls `setFitInsetsTypes(0)` to avoid automatic status-bar displacement. This is an independently written implementation, not copied vendor code.
- The SystemUI manifest requests `MEDIA_CONTENT_CONTROL` and `INTERNAL_SYSTEM_WINDOW`. Its NotificationMediaManager uses active media-session queries and controller callbacks. Manifest requests and existing code support the design but do not by themselves prove effective runtime grants for every call.
- No newer `miui.systemui.dynamicisland` renderer source was found in these two decompilation outputs. HyperOS 1's StrongToast is present. Because decompilation was not error-free, this search result is not a blanket claim about every possible dynamically loaded class.
- XMSF was fingerprinted and its package metadata inspected; no hooks into it are needed or installed by this implementation.

## Changes and remaining evidence needed

0.1.1 fixes the marketing-name/full-version mix-up, tightens notification signatures, prevents duplicate host listener registration, disposes partially initialized hosts, adjusts SystemUI window setup and adds full diagnostic file export. Seven JVM tests cover the original event policy plus version resolution and conservative rejection of unknown/conflicting versions.

The supplied report does not contain the incremental fields. The new build records them and only accepts the exact configured full ROM build. If neither property supplies a recognizable full build, it stays disabled and reports `unknown`; there is no generic OS1 bypass.

No connected phone was available through ADB. No APK was installed, no root command was run on a phone, and no device state was modified. A new full report plus one notification/media test is still needed to establish rendering on the device.
