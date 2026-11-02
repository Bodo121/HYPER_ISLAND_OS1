package io.github.sakuraisland

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class SettingsActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private lateinit var preview: TextView
    private var previewExpanded = false
    private val prefs by lazy { Config.prefs(this) }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun save(key: String, value: String) { contentResolver.update(Config.uri, ContentValues().apply { put(key, value) }, null, null) }
    private fun label(text: String, size: Float = 16f) = TextView(this).apply { this.text = text; textSize = size; setPadding(0, dp(10), 0, dp(10)) }
    private fun section(name: String) {
        body.addView(label(name, 20f).apply {
            setTextColor(0xFFF1B7D0.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(12))
        })
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(12), dp(18), dp(12))
        background = GradientDrawable().apply {
            setColor(0xFF25212B.toInt())
            cornerRadius = dp(20).toFloat()
            setStroke(dp(1), 0xFF3D3443.toInt())
        }
        body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }
    private fun note(message: String) {
        card().addView(label(message, 13f).apply { setTextColor(0xFFD2C7D8.toInt()) })
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = 0xFF151218.toInt()
        window.navigationBarColor = 0xFF151218.toInt()
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(0xFF151218.toInt())
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        body.addView(label("Sakura Island", 34f).apply { setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD) })
        body.addView(label("Beautifully around the camera.\nXiaomi 12 Lite · HyperOS 1", 15f).apply { setTextColor(0xFFD2C7D8.toInt()) })
        preview = TextView(this).apply {
            text = "♫        ●        Sakura"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 15f
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(28).toFloat() }
            contentDescription = "Interactive island preview. Tap to expand."
            setOnClickListener { previewExpanded = !previewExpanded; updatePreview() }
        }
        card().addView(preview, LinearLayout.LayoutParams(dp(184), dp(48)).apply { gravity = Gravity.CENTER; topMargin = dp(22); bottomMargin = dp(22) })
        note("LIVE PREVIEW · Tap to expand. This preview stays inside the app.")
        section("Get started")
        note("Experimental hardware support: Android 14, taoyao, OS1.0.39.0.ULIEUXM. Enable in LSPosed with System UI as the only scope, then reboot.")
        toggle("enabled", "Enable SystemUI island", false)
        toggle("idle", "Keep a pill around the camera when idle")
        section("Activities")
        toggle("media", "Music and media sessions")
        toggle("notifications", "Selected app notifications")
        toggle("calls", "Call and missed-call notifications")
        toggle("timers", "Alarm and timer notifications")
        toggle("charging", "Charging indicator")
        note("Native notifications and calls remain available. Swiping dismisses only the island card.")
        section("Appearance")
        slider("width", "Compact width", 120f, 240f, "dp")
        slider("height", "Minimum height", 28f, 52f, "dp")
        slider("offset", "Vertical adjustment", -4f, 24f, "dp")
        slider("speed", "Animation duration", 100f, 700f, "ms")
        toggle("icons", "Hide overlapping status icons (experimental)", false)
        section("Notification apps")
        note("Comma-separated package names. WhatsApp and Telegram are included by default.")
        val apps = EditText(this).apply { setText(prefs.getString("apps", Config.defaults["apps"])); hint = "com.whatsapp,org.telegram.messenger"; minLines = 2 }
        card().addView(apps.apply { setTextColor(Color.WHITE); setHintTextColor(0xFFB7AABD.toInt()) })
        button("Save app selection") { save("apps", apps.text.toString()); Toast.makeText(this, "App selection saved", Toast.LENGTH_SHORT).show() }
        section("Diagnostics & recovery")
        button("View compatibility report") { report() }
        button("Post test notification") {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            } else postTest()
        }
        button("Disable all island features") { save("enabled", "false"); recreate() }
        body.addView(label("If SystemUI becomes unstable: disable SakuraIsland in LSPosed and restart. With an already authorized ADB connection, run: adb shell pm disable-user --user 0 io.github.sakuraisland, then reboot. If the UI cannot start, use your installed root manager's documented safe mode to disable LSPosed. No firmware flashing is required.", 13f))
        body.addView(label("0.1.1 · Open-source experimental implementation\nNo overlay permission · No telemetry", 12f))
    }
    private fun updatePreview() {
        preview.text = if (previewExpanded) "Sakura Sessions\nA moment of calm\nPrevious     Play     Next" else "♫        ●        Sakura"
        preview.animate().alpha(0.6f).setDuration(100).withEndAction {
            preview.layoutParams = (preview.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (previewExpanded) (resources.displayMetrics.widthPixels - dp(96)).coerceAtLeast(dp(180)) else dp(prefs.getString("width", "184")?.toIntOrNull()?.coerceIn(120, 240) ?: 184)
                height = dp(if (previewExpanded) 150 else prefs.getString("height", "36")?.toIntOrNull()?.coerceIn(28, 52) ?: 36)
            }; preview.animate().alpha(1f).setDuration(180).start()
        }.start()
    }
    private fun toggle(key: String, title: String, default: Boolean = true) {
        card().addView(MaterialSwitch(this).apply {
            text = title; setTextColor(Color.WHITE); isChecked = prefs.getString(key, Config.defaults[key] ?: default.toString()) == "true"; setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> save(key, checked.toString()) }
        })
    }
    private fun slider(key: String, title: String, min: Float, max: Float, unit: String) {
        val value = (prefs.getString(key, Config.defaults[key])?.toFloatOrNull() ?: min).coerceIn(min, max)
        val container = card()
        val caption = label("$title · ${value.toInt()} $unit", 14f).apply { setTextColor(Color.WHITE) }; container.addView(caption)
        container.addView(Slider(this).apply {
            valueFrom = min; valueTo = max; stepSize = 1f; this.value = value
            contentDescription = title
            addOnChangeListener { _, v, _ -> caption.text = "$title · ${v.toInt()} $unit" }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { }
                override fun onStopTrackingTouch(slider: Slider) { save(key, slider.value.toInt().toString()); updatePreview() }
            })
        })
    }
    private fun button(title: String, action: () -> Unit) { card().addView(MaterialButton(this).apply { text = title; isAllCaps = false; cornerRadius = dp(16); setOnClickListener { action() } }) }
    private fun report() {
        val report = readReport()
        val content = label(report, 12f).apply { setTextIsSelectable(true); setPadding(dp(20), dp(12), dp(20), dp(12)) }
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Compatibility diagnostics")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Close", null).setPositiveButton("Save full report") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "SakuraIsland-diagnostics.txt")
                }, 100)
            }.setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Share diagnostics"))
            }.show()
    }
    private fun readReport() = contentResolver.query(Config.diagnostics, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(1) else "No report"
    } ?: "No report"
    @Deprecated("Activity result compatibility API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) data?.data?.let { uri ->
            try {
                checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(readReport()) }
                Toast.makeText(this, "Full report saved", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) { Toast.makeText(this, "Could not save report", Toast.LENGTH_SHORT).show() }
        }
    }
    private fun postTest() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("test", "SakuraIsland test", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(1, Notification.Builder(this, "test").setSmallIcon(R.drawable.ic_sakura).setContentTitle("SakuraIsland test")
            .setContentText("A real Android notification. Tap the island to expand.")
            .setContentIntent(PendingIntent.getActivity(this, 1, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build())
    }
}
