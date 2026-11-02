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
        val version = property("ro.mi.os.version.name").ifBlank { Build.VERSION.INCREMENTAL }
        val compatible = Policy.target(Build.VERSION.SDK_INT, Build.DEVICE, version)
        Diagnostics.record("SakuraIsland 0.1 experimental; SDK=${Build.VERSION.SDK_INT}; device=${Build.DEVICE}; ROM=$version; exactTarget=$compatible")
        val candidates = listOf("com.android.systemui.statusbar.phone.PhoneStatusBarView",
            "com.android.systemui.statusbar.window.StatusBarWindowController",
            "com.android.systemui.statusbar.phone.StatusBar", "com.android.systemui.statusbar.phone.CentralSurfacesImpl",
            "com.android.systemui.statusbar.notification.collection.NotifPipeline",
            "com.android.systemui.statusbar.notification.collection.NotifCollection\$1",
            "com.android.systemui.statusbar.policy.HeadsUpManager",
            "com.android.systemui.statusbar.NotificationMediaManager",
            "miui.systemui.dynamicisland.window.content.DynamicIslandContentView")
        candidates.forEach { name -> Diagnostics.guard("probe $name") {
            val cls = XposedHelpers.findClassIfExists(name, loader)
            if (cls == null) Diagnostics.record("MISSING $name") else {
                Diagnostics.record("FOUND $name")
                cls.declaredMethods.sortedBy { it.name }.take(12).forEach { Diagnostics.record(it.toGenericString()) }
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
                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { Diagnostics.guard("host attach") {
                        if (v.display?.displayId != 0) return@guard
                        runtime?.dispose()
                        runtime = IslandRuntime(v).also { it.start() }
                        Diagnostics.record("Status bar attached; SystemUI window owner captured")
                        Diagnostics.publish(v.context)
                    } }
                    override fun onViewDetachedFromWindow(v: View) { Diagnostics.guard("host detach") {
                        if (runtime?.host === v) { runtime?.dispose(); runtime = null }
                    } }
                })
            } }
        })
        // Observe native collection callbacks; never suppress or replace notification behavior.
        val collection = XposedHelpers.findClassIfExists(candidates[5], loader)
        var hooks = 0
        collection?.declaredMethods?.filter {
            it.name in setOf("onNotificationPosted", "onNotificationRemoved") &&
                it.parameterTypes.firstOrNull() == StatusBarNotification::class.java && it.returnType == Void.TYPE
        }?.forEach { method ->
            Diagnostics.guard("notification hook") {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.hasThrowable()) return
                        val sbn = param.args.firstOrNull() as? StatusBarNotification ?: return
                        Handler(Looper.getMainLooper()).post { Diagnostics.guard("notification event") {
                            if (method.name == "onNotificationPosted") runtime?.posted(sbn) else runtime?.removed(sbn.key)
                        } }
                    }
                }); hooks++; Diagnostics.record("HOOK ${method.toGenericString()}")
            }
        }
        Diagnostics.record("Notification callbacks installed=$hooks; native heads-up retained")
    }
}
