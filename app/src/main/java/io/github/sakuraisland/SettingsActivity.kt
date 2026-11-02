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
    private fun section(name: String) { body.addView(label(name, 22f)) }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(32), dp(24), dp(32)) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        body.addView(label("SakuraIsland", 34f))
        body.addView(label("A small space for what matters.\nXiaomi 12 Lite · HyperOS 1", 15f))
        preview = TextView(this).apply {
            text = "♫        ●        Sakura"; gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 15f
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(28).toFloat() }
            contentDescription = "Interactive island preview. Tap to expand."
            setOnClickListener { previewExpanded = !previewExpanded; updatePreview() }
        }
        body.addView(preview, LinearLayout.LayoutParams(dp(184), dp(48)).apply { gravity = Gravity.CENTER; topMargin = dp(24); bottomMargin = dp(16) })
        body.addView(label("Tap the preview to expand. This preview stays inside this app.", 13f))
        section("Experimental build")
        body.addView(label("Not tested on physical HyperOS hardware. Rendering requires Android 14, taoyao and OS1.0.39.0.ULIEUXM. Enable SakuraIsland in LSPosed with System UI as its only scope, then restart the phone.", 14f))
        toggle("enabled", "Enable SystemUI island", false)
        section("Activities")
        toggle("media", "Music and media sessions")
        toggle("notifications", "Selected app notifications")
        toggle("calls", "Call and missed-call notifications")
        toggle("timers", "Alarm and timer notifications")
        toggle("charging", "Charging indicator")
        body.addView(label("Native notifications and call screens remain active. Swipe dismisses only the island card. Calls and timers depend on the app publishing a categorized notification.", 13f))
        section("Appearance")
        slider("width", "Compact width", 120f, 240f, "dp")
        slider("height", "Minimum height", 28f, 52f, "dp")
        slider("offset", "Vertical adjustment", -4f, 24f, "dp")
        slider("speed", "Animation duration", 100f, 700f, "ms")
        toggle("icons", "Hide overlapping status icons (experimental)", false)
        section("Notification apps")
        body.addView(label("Comma-separated package names. WhatsApp and Telegram are included. Call and alarm categories have separate switches.", 13f))
        val apps = EditText(this).apply { setText(prefs.getString("apps", Config.defaults["apps"])); hint = "com.whatsapp,org.telegram.messenger"; minLines = 2 }
        body.addView(apps)
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
        body.addView(label("0.1.0 · Open-source experimental implementation\nNo overlay permission · No telemetry", 12f))
    }
    private fun updatePreview() {
        preview.text = if (previewExpanded) "Sakura Sessions\nA moment of calm\nPrevious     Play     Next" else "♫        ●        Sakura"
        preview.animate().alpha(0.6f).setDuration(100).withEndAction {
            preview.layoutParams = (preview.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (previewExpanded) resources.displayMetrics.widthPixels - dp(48) else dp(prefs.getString("width", "184")!!.toInt())
                height = dp(if (previewExpanded) 150 else prefs.getString("height", "36")!!.toInt())
            }; preview.animate().alpha(1f).setDuration(180).start()
        }.start()
    }
    private fun toggle(key: String, title: String, default: Boolean = true) {
        body.addView(MaterialSwitch(this).apply {
            text = title; isChecked = prefs.getString(key, default.toString()) == "true"; setPadding(0, dp(8), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> save(key, checked.toString()) }
        })
    }
    private fun slider(key: String, title: String, min: Float, max: Float, unit: String) {
        val value = prefs.getString(key, Config.defaults[key])!!.toFloat().coerceIn(min, max)
        val caption = label("$title · ${value.toInt()} $unit", 14f); body.addView(caption)
        body.addView(Slider(this).apply {
            valueFrom = min; valueTo = max; stepSize = 1f; this.value = value
            contentDescription = title
            addOnChangeListener { _, v, _ -> caption.text = "$title · ${v.toInt()} $unit" }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) { }
                override fun onStopTrackingTouch(slider: Slider) { save(key, slider.value.toInt().toString()); updatePreview() }
            })
        })
    }
    private fun button(title: String, action: () -> Unit) { body.addView(MaterialButton(this).apply { text = title; setOnClickListener { action() } }) }
    private fun report() {
        val report = contentResolver.query(Config.diagnostics, null, null, null, null)?.use { if (it.moveToFirst()) it.getString(1) else "No report" } ?: "No report"
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Compatibility diagnostics").setMessage(report)
            .setPositiveButton("Close", null).setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Share diagnostics"))
            }.show()
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
