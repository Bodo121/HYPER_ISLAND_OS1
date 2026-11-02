# Research record — 2026-11-02

**Follow-up:** the owner has since supplied SystemUI/plugin APKs and a runtime report. Findings and hashes are in [ROM_INSPECTION.md](ROM_INSPECTION.md); the original discovery record below describes the evidence available before those files arrived.

## Source inspected

Repositories were fetched from their public GitHub origins. CustoMIUIzer's initial checkout hit Windows path limits in unrelated `.agents` files; its `app`, `README.md` and `LICENSE` were explicitly restored and inspected. No instructions from upstream repos were used to authorize actions.

### HyperIsland

Origin: https://github.com/1812z/HyperIsland
Commit: `b25f8992f1196fdd5d4aeb6069e6ef564b421b18`
License: MIT, copyright 2026 1812z.

Inspected `android/app/build.gradle.kts`, `xposed/HyperIslandModule.kt`, `hook/SystemUI/DynamicIslandVisibilityHook.kt`, `hook/SystemUI/animation/ExpandedCollapseAnimationHook.kt`, `template/renderer/IslandRenderer.kt` under `android/app/src/main/kotlin/io/github/hyperisland`.

The renderer interface serializes HyperOS island JSON into notification extras. Actual display depends on Xiaomi's native island system, not a standalone renderer portable by changing SDK checks. Visibility hooks reference `miui.systemui.dynamicisland.event.DynamicIslandEventCoordinator`, `window.content.DynamicIslandContentView`, `anim.DynamicIslandAnimationController`, `com.android.systemui.statusbar.StatusBarIslandControllerImpl` and `notification.DynamicIslandController`. Animation customization references `miuix.animation.controller.AnimState` and private island delegates. The inspected build uses compile/target SDK 37 and Java 21; its module uses modern libxposed interfaces. Notification templates, dispatch, geometry and animations are coupled to native newer-ROM APIs. Its API-independent event/geometry ideas can inform Android 14 work, but those private hooks are not assumed available.

### CustoMIUIzer A14

Origin: https://github.com/thetvplus/customiuizer-a14
Commit: `11e3f4973e4867f409fe15e6da0d101fb7f7bb2a`
License file: GNU GPL version 3.

Inspected `app/src/main/java/tv/withaibuild/customiuizer/mods/island/DynamicIslandActivities.kt`, `IslandActivityController.kt`, `IslandActivityView.kt`, preference references and package scope helpers. **It does contain a substantial Dynamic Island implementation.** Its notification observer targets `NotifCollection$1.onNotificationPosted(StatusBarNotification, RankingMap)` and removal callbacks. Its controller owns a type-2017 SystemUI window, media integration and lifecycle cleanup. Its view builds custom native layouts. More advanced code handles native heads-up takeover, privacy, foreground/freeform/PiP conditions, vendor apps and hidden APIs; extracting it would entail a sizable dependency and compatibility audit, not copying one hook.

SakuraIsland independently implements a narrower type-2017 SystemUI host and observes the same Android notification callback contract. No upstream source code/assets are copied into SakuraIsland. Class names and API facts are research findings. A future CustoMIUIzer-derived version must preserve copyright notices and meet GPL source/distribution requirements; MIT HyperIsland portions must retain their MIT notice. This distribution is itself GPL-3.0-only to support future compatible contributions, but does not claim to be a fork.

## Other projects and community search

GitHub search located https://github.com/D4vidDf/HyperBridge — its README describes converting standard notifications to native HyperIsland, with NotificationListenerService and WidgetOverlayService. This is useful adjacent work, not evidence that HyperOS 1 contains HyperOS 3's native renderer. Searches of XDA (`site:xdaforums.com HyperOS dynamic island`) did not produce a source-verified exact-ROM port. Community anecdotes were not treated as compatibility evidence.

## Exact ROM and provenance limits

Public catalogs list the target ROM:
- https://xmfirmwareupdater.com/hyperos/taoyao/
- https://mirom.ezbox.idv.tw/en/phone/TAOYAO/

The latter lists `miui_TAOYAOEEAGlobal_OS1.0.39.0.ULIEUXM_23371ecf31_14.0.zip`, MD5 `23371ecf3196cb678d927a12a79df633`, approximately 5 GB. Catalogs disagree on some release dates and sizes. These are discovery references, not a verified artifact hash from a downloaded image. The full image was not downloaded or extracted; no exact-ROM private class claims follow from these listings. Native HyperOS 3 classes above are verified in upstream hook source, **not verified as present or absent in the target firmware**.

The runtime diagnostic records SDK, device, ROM version, candidate class availability and method signatures on the actual device. It logs the signatures of installed notification hooks. Missing classes disable the associated integration. To complete compatibility verification, export that report plus the device's SystemUI APK (read-only extraction instructions in RECOVERY.md).

## Platform contract

https://developer.android.com/reference/android/media/session/MediaSessionManager documents that active-session access requires MEDIA_CONTENT_CONTROL or an enabled notification listener. SakuraIsland calls it inside SystemUI, which normally has privileged access; failures are caught and reported rather than assuming permission. No media-session privilege is requested for the settings app.
