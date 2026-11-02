package io.github.sakuraisland

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process

object Config {
    val uri: Uri = Uri.parse("content://io.github.sakuraisland.config/settings")
    val diagnostics: Uri = Uri.parse("content://io.github.sakuraisland.config/diagnostics")
    val defaults = linkedMapOf("enabled" to "false", "media" to "true", "notifications" to "true",
        "calls" to "true", "charging" to "true", "timers" to "true",
        "all_apps" to "true", "auto_expand_notifications" to "true", "replace_heads_up" to "true", "icons" to "false",
        "idle" to "true", "idle_width" to "104", "idle_height" to "34", "chroma" to "true", "motion" to "true",
        "width" to "184", "height" to "36", "offset" to "0", "speed" to "300",
        "apps" to "com.whatsapp,org.telegram.messenger,io.github.sakuraisland")
    fun prefs(c: Context) = c.createDeviceProtectedStorageContext().getSharedPreferences("settings", 0)
}

/** Read-only settings bridge for the SystemUI UID. No exported command execution. */
class ConfigProvider : ContentProvider() {
    override fun onCreate() = true
    private fun authorize(writeSettings: Boolean = false) {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (writeSettings || context!!.packageManager.getPackagesForUid(uid)?.contains("com.android.systemui") != true)
            throw SecurityException("Caller is not SystemUI")
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor {
        authorize()
        return MatrixCursor(arrayOf("key", "value")).apply {
            if (uri == Config.diagnostics) addRow(arrayOf("report", Config.prefs(context!!).getString("report", "No SystemUI report yet. Enable the module in LSPosed and restart the phone.")))
            else Config.defaults.forEach { (k, v) -> addRow(arrayOf(k, Config.prefs(context!!).getString(k, v))) }
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        authorize()
        require(uri == Config.diagnostics)
        Config.prefs(context!!).edit().putString("report", values?.getAsString("report")?.take(32000)).apply()
        return uri
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        authorize(true)
        val editor = Config.prefs(context!!).edit()
        values?.valueSet()?.forEach { if (it.key in Config.defaults) editor.putString(it.key, it.value.toString().take(4096)) }
        editor.apply(); context!!.contentResolver.notifyChange(Config.uri, null); return 1
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun getType(uri: Uri) = "vnd.android.cursor.dir/vnd.sakuraisland.settings"
}
