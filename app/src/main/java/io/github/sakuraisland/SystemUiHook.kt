package io.github.sakuraisland

import android.app.Application
import android.content.ContentValues
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import de.robv.android.xposed.*
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.Executors

object Diagnostics {
    private val lines = ArrayDeque<String>()
    val io = Executors.newSingleThreadExecutor()
    @Synchronized fun record(message: String) {
        if (lines.size >= 200) lines.removeFirst()
        lines.addLast(message.take(500)); XposedBridge.log("SakuraIsland: $message")
    }
    @Synchronized fun report() = lines.joinToString("\n")
    fun publish(context: android.content.Context) { io.execute {
        try { context.contentResolver.insert(Config.diagnostics, ContentValues().apply { put("report", report()) }) }
        catch (_: Exception) { /* Provider may be unavailable before first unlock. */ }
    } }
    inline fun guard(label: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            record("$label: ${t.javaClass.simpleName}")
        }
    }
}

class SystemUiHook : IXposedHookLoadPackage {
    companion object { var runtime: IslandRuntime? = null }
    override fun handleLoadPackage(p: XC_LoadPackage.LoadPackageParam) {
        if (p.packageName != "com.android.systemui" || p.processName != "com.android.systemui") return
        Diagnostics.guard("hook installation") { install(p.classLoader) }
    }
    private fun property(name: String): String = try {
        XposedHelpers.callStaticMethod(Class.forName("android.os.SystemProperties"), "get", name, "") as String
    } catch (_: Throwable) { "" }
    private fun install(loader: ClassLoader) {
        val name = property("ro.mi.os.version.name")
        val incremental = property("ro.mi.os.version.incremental")
        val version = Policy.romVersion(incremental, Build.VERSION.INCREMENTAL, name)
        val compatible = Policy.target(Build.VERSION.SDK_INT, Build.DEVICE, version)
        Diagnostics.record("SakuraIsland 0.4 experimental; SDK=${Build.VERSION.SDK_INT}; device=${Build.DEVICE}; ROM=$version; exactTarget=$compatible")
        Diagnostics.record("ro.mi.os.version.name=$name")
        Diagnostics.record("ro.mi.os.version.incremental=$incremental")
        Diagnostics.record("ro.build.version.incremental=${Build.VERSION.INCREMENTAL}")
        Diagnostics.record("Dolby DAX hardware property=${property("ro.vendor.audio.dolby.dax.support").ifBlank { "unavailable" }}")
        val candidates = listOf("com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.statusbar.window.StatusBarWindowController",
            "com.android.systemui.statusbar.phone.StatusBar", "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
            "com.android.systemui.statusbar.notification.collection.NotifPipeline",
            "com.android.systemui.statusbar.notification.collection.NotifCollection\$1",
            "com.android.systemui.statusbar.policy.HeadsUpManager",
            "com.android.systemui.statusbar.NotificationMediaManager",
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView",
            // Confirmed by examining the user's installed Xiaomi SystemUI APK.
            // Read-only detection; no blind constructor/method hooks.
            "com.android.systemui.toast.MIUIStrongToast",
            "com.android.systemui.toast.MIUIStrongToastControl",
            "com.miui.charge.container.MiuiChargeAnimationView",
            "com.miui.charge.container.MiuiChargeContainerView")
        candidates.forEach { name -> Diagnostics.guard("probe $name") {
            val cls = XposedHelpers.findClassIfExists(name, loader)
            if (cls == null) Diagnostics.record("MISSING $name") else {
                Diagnostics.record("FOUND $name")
                cls.declaredMethods.sortedBy { it.name }.take(3).forEach { Diagnostics.record(it.toGenericString()) }
            }
        } }
        XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { Diagnostics.publish(param.thisObject as Application) }
        })
        if (!compatible) { Diagnostics.record("Rendering blocked: exact target mismatch. Diagnostics only."); return }
        val host = XposedHelpers.findClassIfExists(candidates[0], loader) ?: return
        XposedBridge.hookAllConstructors(host, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) { Diagnostics.guard("host capture") {
                val view = param.thisObject as? View ?: return@guard
                if (XposedHelpers.getAdditionalInstanceField(view, "sakura.lifecycle") != null) return@guard
                XposedHelpers.setAdditionalInstanceField(view, "sakura.lifecycle", true)
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { Diagnostics.guard("host attach") {
                        if (v.display?.displayId != 0) return@guard
                        runtime?.dispose()
                        val next = IslandRuntime(v)
                        runtime = next
                        try { next.start() } catch (t: Throwable) {
                            runtime = null
                            Diagnostics.guard("partial attach cleanup") { next.dispose() }
                            throw t
                        }
                        Diagnostics.record("Status bar attached; SystemUI window owner captured")
                        Diagnostics.publish(v.context)
                    } }
                    override fun onViewDetachedFromWindow(v: View) { Diagnostics.guard("host detach") {
                        if (runtime?.host === v) { runtime?.dispose(); runtime = null }
                    } }
                })
            } }
        })
        // Xiaomi's installed native strong-toast manager exposes getSupportCharge(MiuiBatteryStatus).
        // Observe its *return value only*; never instantiate or replace the OEM manager.
        // Hooking is best-effort and safely skipped on other ROMs/class loaders.
        Diagnostics.guard("OEM charging observer setup") {
            XposedHelpers.findClassIfExists("com.android.systemui.toast.MIUIStrongToastControl", loader)
                ?.declaredMethods?.filter { method ->
                    method.name == "getSupportCharge" &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].name == "com.miui.charge.MiuiBatteryStatus" &&
                        method.returnType == String::class.java
                }?.forEach { method ->
                    Diagnostics.guard("native charge mode observer") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.hasThrowable()) return
                                val hint = param.result as? String ?: return
                                Handler(Looper.getMainLooper()).post {
                                    Diagnostics.guard("native charge mode") {
                                        runtime?.nativeChargingMode(hint)
                                    }
                                }
                            }
                        })
                        Diagnostics.record("Observed Xiaomi charging mode getter (read-only)")
                    }
                }
        }
        // The exact taoyao MiuiSystemUI.apk includes StrongToastModel#getCharge()
        // and #getChargeLevel(). Filter by the typed charging field: unrelated OEM
        // strong toasts (privacy/calls/security) continue through Xiaomi unchanged.
        Diagnostics.guard("charging strong-toast replacement setup") {
            var installed = 0
            val classNames = listOf(
                "com.android.systemui.toast.MIUIStrongToastControl",
                "com.android.systemui.toast.MIUIStrongToast"
            )
            classNames.forEach { name ->
                val cls = XposedHelpers.findClassIfExists(name, loader) ?: return@forEach
                cls.declaredMethods.filter { method ->
                    method.name == "showCustomStrongToast" &&
                        method.returnType == Void.TYPE && method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].name == "com.android.systemui.toast.bean.StrongToastModel"
                }.forEach { method ->
                    Diagnostics.guard("charging strong-toast hook") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val model = param.args.firstOrNull() ?: return
                                val charge = runCatching {
                                    XposedHelpers.callMethod(model, "getCharge") as? String
                                }.getOrNull().orEmpty()
                                if(charge.isBlank()) return
                                val level = runCatching {
                                    (XposedHelpers.callMethod(model, "getChargeLevel") as? Number)?.toFloat()
                                }.getOrNull() ?: Float.NaN
                                val shouldReplace = runCatching {
                                    runtime?.replaceNativeChargeToast(charge, level) == true
                                }.getOrDefault(false)
                                if (shouldReplace) param.result = null
                            }
                        })
                        installed++
                        Diagnostics.record("HOOK typed Xiaomi charging strong-toast: ${method.toGenericString()}")
                    }
                }
            }
            Diagnostics.record("Xiaomi charging strong-toast hooks installed=$installed")
        }
        // Observe both native collection delivery and its coalescer. Their callbacks
        // may be used on different Xiaomi build paths. Runtime deduplicates by sbn.key.
        // Native collection delivery is never suppressed. Only later visual
        // heads-up decisions may be replaced after a matching island is visible.
        var hooks = 0
        val listenerNames = listOf(
            "com.android.systemui.statusbar.notification.collection.NotifCollection\$1",
            "com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer\$1"
        )
        val postingSignature = listOf(
            StatusBarNotification::class.java,
            android.service.notification.NotificationListenerService.RankingMap::class.java
        )
        listenerNames.forEach { listenerName ->
            val listener = XposedHelpers.findClassIfExists(listenerName, loader)
            if (listener == null) {
                Diagnostics.record("Notification listener unavailable: ${listenerName.substringAfterLast('.')}")
            } else {
                listener.declaredMethods.filter {
                    it.returnType == Void.TYPE &&
                        ((it.name == "onNotificationPosted" && it.parameterTypes.toList() == postingSignature) ||
                        (it.name == "onNotificationRemoved" &&
                            it.parameterTypes.toList() == postingSignature + Integer.TYPE))
                }.forEach { method ->
                    Diagnostics.guard("notification callback") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.hasThrowable()) return
                                val sbn = param.args.firstOrNull() as? StatusBarNotification ?: return
                                Handler(Looper.getMainLooper()).post { Diagnostics.guard("notification delivery") {
                                    if (method.name == "onNotificationPosted") runtime?.posted(sbn)
                                    else runtime?.removed(sbn.key)
                                } }
                            }
                        })
                        hooks++
                        Diagnostics.record("HOOK ${method.toGenericString()}")
                    }
                }
            }
        }
        // Fallback for internally coalesced notifications that bypass the public
        // listener callbacks on some Xiaomi builds. Signature validated against
        // the installed MiuiSystemUI.apk method table.
        val collectionImpl = XposedHelpers.findClassIfExists(
            "com.android.systemui.statusbar.notification.collection.NotifCollection", loader)
        collectionImpl?.declaredMethods?.filter {
            it.name == "postNotification" && it.returnType == Void.TYPE &&
                it.parameterTypes.toList() == listOf(StatusBarNotification::class.java,
                    android.service.notification.NotificationListenerService.Ranking::class.java)
        }?.forEach { method ->
            Diagnostics.guard("notification fallback") {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val sbn = param.args[0] as? StatusBarNotification ?: return
                        Handler(Looper.getMainLooper()).post { Diagnostics.guard("notification fallback delivery") {
                            runtime?.posted(sbn)
                        } }
                    }
                })
                hooks++
                Diagnostics.record("HOOK fallback ${method.toGenericString()}")
            }
        }
        // Replace only the native visual heads-up banner *after* the matching
        // island event has actually attached and expanded. We never hook
        // NotificationManagerService or cancel an Android notification.
        // Because we are inside SystemUI via LSPosed, no root shell daemon
        // or global heads_up_notifications_enabled setting is needed.
        Diagnostics.guard("heads-up replacement setup") {
            val candidateTypes = listOf(
                "com.android.systemui.statusbar.AlertingNotificationManager",
                "com.android.systemui.statusbar.policy.HeadsUpManager",
                "com.android.systemui.statusbar.phone.HeadsUpManagerPhone"
            )
            var replacements = 0
            candidateTypes.forEach { className ->
                val cls = XposedHelpers.findClassIfExists(className, loader) ?: return@forEach
                cls.declaredMethods.filter { method ->
                    method.name == "showNotification" &&
                        method.returnType == Void.TYPE &&
                        method.parameterTypes.size == 1 &&
                        method.parameterTypes[0].name ==
                            "com.android.systemui.statusbar.notification.collection.NotificationEntry"
                }.forEach { method ->
                    Diagnostics.guard("heads-up replacement hook") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                val entry = param.args.firstOrNull() ?: return
                                val sbn = try {
                                    XposedHelpers.callMethod(entry, "getSbn") as? StatusBarNotification
                                } catch (_: Throwable) { null } ?: return
                                // A failure in the island must always leave Xiaomi's
                                // original heads-up pipeline working as a fallback.
                                val replace = try {
                                    runtime?.showInsteadOfHeadsUp(sbn) == true
                                } catch (t: Throwable) {
                                    Diagnostics.record("Heads-up fallback: ${t.javaClass.simpleName}")
                                    false
                                }
                                if (replace) param.result = null
                            }
                        })
                        replacements++
                        Diagnostics.record("HOOK guarded native heads-up ${method.toGenericString()}")
                    }
                }
            }
            Diagnostics.record("Guarded heads-up replacements installed=$replacements")
        }
        // Xiaomi may decide whether to display the banner before HeadsUpManager
        // receives showNotification(). Hook the public decision path as a fallback.
        // We only turn TRUE -> FALSE if the matching alert is *already drawn*.
        // Full-screen calls, alarms and system exceptions are protected by runtime.
        Diagnostics.guard("heads-up interrupt decision setup") {
            val names = listOf(
                "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl",
                "com.android.systemui.statusbar.notification.interruption.MiuiNotificationInterruptStateProviderImpl",
                "com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderWrapper"
            )
            var installed = 0
            names.forEach { name ->
                val cls = XposedHelpers.findClassIfExists(name, loader) ?: return@forEach
                cls.declaredMethods.filter { method ->
                    method.name in setOf("shouldHeadsUp", "shouldHeadsUpEver", "shouldHeadsUpAgain", "checkHeadsUp") &&
                        method.returnType == Boolean::class.javaPrimitiveType &&
                        method.parameterTypes.isNotEmpty() &&
                        method.parameterTypes.first().name ==
                            "com.android.systemui.statusbar.notification.collection.NotificationEntry" &&
                        method.parameterTypes.drop(1).all { it == Boolean::class.javaPrimitiveType }
                }.forEach { method ->
                    Diagnostics.guard("heads-up decision hook") {
                        XposedBridge.hookMethod(method, object : XC_MethodHook() {
                            override fun afterHookedMethod(param: MethodHookParam) {
                                if (param.hasThrowable() || param.result != true) return
                                val entry = param.args.firstOrNull() ?: return
                                val sbn = runCatching {
                                    XposedHelpers.callMethod(entry, "getSbn") as? StatusBarNotification
                                }.getOrNull() ?: return
                                if (runtime?.showInsteadOfHeadsUp(sbn) == true) {
                                    param.result = false
                                }
                            }
                        })
                        installed++
                        Diagnostics.record("HOOK heads-up policy ${method.toGenericString()}")
                    }
                }
            }
            Diagnostics.record("Heads-up decision hooks installed=$installed")
        }
        Diagnostics.record("Notification observation callbacks installed=$hooks; native shade and critical alerts retained")
    }
}
