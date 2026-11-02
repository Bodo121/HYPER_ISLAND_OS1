# Recovery and read-only diagnostics

No commands in this document were run against a phone during development.

1. If settings are usable, turn off Enable SystemUI island. This removes the window and unregisters media callbacks.
2. Disable SakuraIsland in LSPosed and reboot to remove hooks entirely.
3. If ADB was already authorized, `adb shell pm disable-user --user 0 io.github.sakuraisland`, then reboot. Re-enable later with `adb shell pm enable io.github.sakuraisland`. If LSPosed still has the module loaded, disabling it in LSPosed/root-manager safe mode and rebooting is the authoritative recovery.
4. If SystemUI cannot start, use the safe-mode procedure documented for your installed root manager/LSPosed distribution. Do not guess hardware-key sequences, wipe data or flash partitions.

## Optional exact-firmware evidence

Run only when you choose to collect it:

```sh
adb shell getprop ro.product.device
adb shell getprop ro.build.version.sdk
adb shell getprop ro.mi.os.version.name
adb shell getprop ro.build.version.incremental
adb shell pm path com.android.systemui
```

Use the actual APK path returned by the last command with `adb pull <returned-path> SystemUI.apk`. This is read-only. Some ROMs require a separately authorized root-assisted copy if the APK path is unreadable; do not change its permissions or modify the installed APK. Hash the pulled file and record the exact build fingerprint before decompiling it locally. Avoid uploading proprietary firmware publicly without the necessary rights.

Share SakuraIsland's compatibility report from its app. LSPosed logs can contain other modules' sensitive output; review them before sharing.
