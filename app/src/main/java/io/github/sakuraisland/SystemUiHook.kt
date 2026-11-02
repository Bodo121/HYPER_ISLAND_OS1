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
        Diagnostics.record("SakuraIsland 0.3 experimental; SDK=${Build.VERSION.SDK_INT}; device=${Build.DEVICE}; ROM=$version; exactTarget=$compatible")
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
        // Observe both native collection delivery and its coalescer. Their callbacks
        // may be used on different Xiaomi build paths. Runtime deduplicates by sbn.key.
        // No native notification is suppressed: preserve system delivery until the
        // island has been validated on-device for each notification source.
        var hooks = 0
        val listenerNames = listOf(
            "com.android.systemui.statusbar.notification.collection.NotifCollection\\$1",
            "com.android.systemui.statusbar.notification.collection.coalescer.GroupCoalescer\\$1"
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
        Diagnostics.record("Notification observation callbacks installed=$hooks; native heads-up preserved")
    }
}
