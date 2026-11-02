package io.github.sakuraisland

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Settings run in our own APK process, where Compose has a valid lifecycle.
 * The SystemUI hook deliberately remains built from platform Views for stability.
 */
class SettingsActivity : ComponentActivity() {
    private val prefs by lazy { Config.prefs(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        window.decorView.systemUiVisibility = 0
        setContent {
            SakuraTheme {
                SakuraSettingsScreen(
                    prefs = prefs,
                    save = ::save,
                    onReport = ::report,
                    onTestNotification = {
                        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                            postTest()
                        } else requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 77)
                    },
                    onDisable = {
                        save("enabled", "false")
                        Toast.makeText(this, "Rendering disabled; restart SystemUI if needed", Toast.LENGTH_LONG).show()
                        recreate()
                    }
                )
            }
        }
    }

    private fun save(key: String, value: String) {
        contentResolver.update(Config.uri, ContentValues().apply { put(key, value) }, null, null)
    }

    private fun readReport(): String = contentResolver.query(Config.diagnostics, null, null, null, null)?.use {
        if (it.moveToFirst()) it.getString(1) else "No SystemUI diagnostics yet"
    } ?: "No SystemUI diagnostics yet"

    private fun report() {
        val reportText = readReport()
        val reportView = TextView(this).apply {
            text = reportText
            textSize = 12f
            setPadding(28, 24, 28, 24)
            setTextIsSelectable(true)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("SystemUI diagnostics")
            .setView(ScrollView(this).apply { addView(reportView) })
            .setNegativeButton("Close", null)
            .setPositiveButton("Save report") { _, _ ->
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "SakuraIsland-diagnostics.txt")
                }, 100)
            }
            .setNeutralButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, reportText)
                }, "Share diagnostics"))
            }
            .show()
    }

    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK) data?.data?.let { uri ->
            try {
                checkNotNull(contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use {
                    it.write(readReport())
                }
                Toast.makeText(this, "Report saved", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Could not save report", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) postTest()
    }

    private fun postTest() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel("test", "SakuraIsland test", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(1, Notification.Builder(this, "test")
            .setSmallIcon(R.drawable.ic_sakura)
            .setContentTitle("SakuraIsland test")
            .setContentText("SystemUI should render the notification if enabled and allowed.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
