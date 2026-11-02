package io.github.sakuraisland

import android.app.*
import android.content.*
import android.database.ContentObserver
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.*
import android.media.session.MediaController
import android.os.*
import android.service.notification.StatusBarNotification
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.widget.*
import android.animation.ValueAnimator

data class IslandEvent(val key: String, val title: String, val text: String, val priority: Int,
    val time: Long = SystemClock.elapsedRealtime(), val expires: Long = Long.MAX_VALUE,
    val intent: PendingIntent? = null, val art: Bitmap? = null, val media: MediaController? = null,
    val dismissible: Boolean = true)

class IslandRuntime(val host: View) {
    private val context = host.context
    private val main = Handler(Looper.getMainLooper())
    private val window = context.getSystemService(WindowManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var options = Config.defaults.toMap()
    private val events = linkedMapOf<String, IslandEvent>()
    private var expanded = false
    private var attached = false
    private var disposed = false
    private var failed = false
    private var errors = 0
    private var currentKey: String? = null
    private var animator: ValueAnimator? = null
    private val hiddenIcons = linkedMapOf<View, Float>()
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(28).toFloat() }
        elevation = dp(6).toFloat(); clipToOutline = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private val params = WindowManager.LayoutParams(dp(184), dp(36), 2017,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        setFitInsetsTypes(0)
        title = "SakuraIsland (SystemUI)"
    }
    private var media: MediaSource? = null
    private val expiry = Runnable { safe { redraw() } }
    private val collapse = Runnable { safe { expanded = false; redraw() } }
    private val observer = object : ContentObserver(main) { override fun onChange(selfChange: Boolean) { loadConfig() } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { safe {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { expanded = false; hide() }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_CONFIGURATION_CHANGED -> { expanded = false; redraw() }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    if (enabled("charging") && charging && !wasCharging) {
                        events["battery"] = IslandEvent("battery", "Charging", "${level * 100 / scale}% battery", 20,
                            expires = SystemClock.elapsedRealtime() + 6000); redraw()
                    }
                    wasCharging = charging
                }
            }
        } }
    }
    private var wasCharging = false
    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun enabled(k: String) = options[k] == "true"
    private fun value(k: String, min: Int, max: Int) = (options[k]?.toIntOrNull() ?: Config.defaults[k]!!.toInt()).coerceIn(min, max)
    private fun safe(block: () -> Unit) {
        if (disposed || failed) return
        try { block() } catch (t: Throwable) {
            if (t is VirtualMachineError || t is ThreadDeath) throw t
            Diagnostics.record("Runtime failure ${t.javaClass.simpleName}; feature disabled after three errors")
            if (++errors >= 3) { failed = true; hide(); media?.stop(); media = null }
            Diagnostics.publish(context)
        }
    }
    fun start() {
        // The supplied ROM's MIUIStrongToast uses this same SystemUI window type and trust marker.
        Diagnostics.guard("trusted SystemUI window") {
            WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").invoke(params)
        }
        context.contentResolver.registerContentObserver(Config.uri, false, observer)
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED); addAction(Intent.ACTION_BATTERY_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        host.addOnLayoutChangeListener(layoutListener)
        loadConfig()
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> safe { redraw() } }
    private fun loadConfig() { Diagnostics.io.execute {
        val next = Config.defaults.toMutableMap()
        try {
            context.contentResolver.query(Config.uri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) next[cursor.getString(0)] = cursor.getString(1)
            }
        } catch (_: Exception) { next["enabled"] = "false"; Diagnostics.record("Settings unavailable; rendering disabled") }
        main.post { safe {
            options = next
            events.clear(); expanded = false
            media?.stop(); media = null
            if (enabled("enabled") && enabled("media")) {
                media = MediaSource(context, main) { controller -> safe { updateMedia(controller) } }.also { it.start() }
            }
            redraw(); Diagnostics.publish(context)
        } }
    } }
    private fun updateMedia(c: MediaController?) {
        events.remove("media")
        if (c != null) {
            val m = c.metadata
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            events["media"] = IslandEvent("media", m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Media",
                m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: c.packageName, 40,
                expires = if (playing) Long.MAX_VALUE else SystemClock.elapsedRealtime() + 15000,
                intent = c.sessionActivity, art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART), media = c)
        }
        redraw()
    }
    fun posted(sbn: StatusBarNotification) { safe {
        if (!enabled("enabled") || sbn.user != UserHandle.getUserHandleForUid(Process.myUid())) return@safe
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.visibility == Notification.VISIBILITY_SECRET) return@safe
        val call = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL
        val timer = n.category == Notification.CATEGORY_ALARM || n.category == Notification.CATEGORY_STOPWATCH
        val allowed = options["apps"].orEmpty().split(',').map { it.trim() }.contains(sbn.packageName)
        if ((call && !enabled("calls")) || (timer && !enabled("timers")) || (!call && !timer && (!enabled("notifications") || !allowed))) return@safe
        val now = SystemClock.elapsedRealtime()
        events[sbn.key] = IslandEvent(sbn.key, n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(120) ?: sbn.packageName,
            n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.take(300) ?: "Notification",
            if (call) 100 else if (timer) 80 else 60, now, now + if (call || timer) 30000 else 8000,
            n.contentIntent, dismissible = sbn.isClearable && !call && !timer)
        while (events.size > 32) events.remove(events.keys.firstOrNull { it != "media" } ?: break)
        redraw()
    } }
    fun removed(key: String) { safe { events.remove(key); redraw() } }
    private fun eligible() = enabled("enabled") && power.isInteractive && !keyguard.isKeyguardLocked &&
        host.isAttachedToWindow && host.isShown && context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    private fun redraw() {
        main.removeCallbacks(expiry)
        val now = SystemClock.elapsedRealtime(); events.entries.removeAll { it.value.expires <= now }
        val key = Policy.select(events.values.map { EventRank(it.key, it.priority, it.time, it.expires) }, now)
        val event = events[key]
        if (!eligible() || event == null) { currentKey = null; expanded = false; hide(); return }
        if (currentKey != key) { expanded = false; currentKey = key }
        val nextExpiry = events.values.minOf { it.expires }
        if (nextExpiry != Long.MAX_VALUE) main.postDelayed(expiry, (nextExpiry - now).coerceAtLeast(1))
        buildContent(event)
        val cutout = host.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull { it.top == 0 }
        val screenWidth = context.resources.displayMetrics.widthPixels
        params.x = if (cutout != null) cutout.centerX() - screenWidth / 2 else 0
        val compactHeight = maxOf(dp(value("height", 28, 52)), (cutout?.height() ?: 0) + dp(4))
        params.y = ((cutout?.top ?: dp(6)) + dp(value("offset", -4, 24))).coerceAtLeast(0)
        val width = minOf(dp(if (expanded) 340 else value("width", 120, 240)), screenWidth - dp(24))
        val height = if (expanded) maxOf(dp(174), compactHeight + dp(120)) else compactHeight
        if (!attached) {
            params.width = width; params.height = height
            window.addView(panel, params); attached = true
            Diagnostics.record("SystemUI island window attached")
            Diagnostics.publish(context)
        } else animateSize(width, height)
        manageIcons()
    }
    private fun animateSize(w: Int, h: Int) {
        animator?.cancel()
        if (params.width == w && params.height == h) { window.updateViewLayout(panel, params); return }
        val fromW = params.width; val fromH = params.height
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = value("speed", 100, 700).toLong(); interpolator = DecelerateInterpolator()
            addUpdateListener { animation -> safe {
                val f = animation.animatedValue as Float
                params.width = (fromW + (w - fromW) * f).toInt(); params.height = (fromH + (h - fromH) * f).toInt()
                if (attached) window.updateViewLayout(panel, params)
            } }; start()
        }
    }
    private fun text(value: String, size: Float) = TextView(context).apply {
        text = value; textSize = size; setTextColor(Color.WHITE); maxLines = if (expanded) 2 else 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private fun buildContent(e: IslandEvent) {
        panel.removeAllViews(); panel.setPadding(dp(14), if (expanded) dp(42) else dp(2), dp(14), dp(4))
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        if (e.art != null) row.addView(ImageView(context).apply { setImageBitmap(e.art); scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = "Album artwork" }, LinearLayout.LayoutParams(dp(28), dp(28)))
        if (expanded) {
            row.addView(text(e.title, 16f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            panel.addView(row); panel.addView(text(e.text, 13f))
            val buttons = LinearLayout(context).apply { gravity = Gravity.CENTER }
            fun button(label: String, action: () -> Unit) {
                buttons.addView(Button(context).apply { text = label; textSize = 12f; minWidth = 0; setPadding(0, 0, 0, 0)
                    setOnClickListener { safe { if (eligible()) action() else hide() } }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            e.media?.let { c ->
                val actions = c.playbackState?.actions ?: 0L
                if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) button("Previous") { c.transportControls.skipToPrevious() }
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                if (actions and (PlaybackState.ACTION_PLAY_PAUSE or if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) != 0L)
                    button(if (playing) "Pause" else "Play") { if (playing) c.transportControls.pause() else c.transportControls.play() }
                if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) button("Next") { c.transportControls.skipToNext() }
            }
            e.intent?.let { intent -> button("Open") { intent.send(); expanded = false; redraw() } }
            button("Close") { expanded = false; redraw() }
            panel.addView(buttons)
        } else {
            // Keep the camera's central area empty; identity on the left, activity on the right.
            row.addView(text(if (e.media != null) "Music" else if (e.priority == 100) "Call" else "●", 12f), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Space(context), LinearLayout.LayoutParams(dp(40), 1))
            row.addView(text(if (e.media?.playbackState?.state == PlaybackState.STATE_PLAYING) "♫" else e.title, 12f), LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(row)
        }
        panel.contentDescription = "${e.title}. ${if (expanded) e.text else "Tap to expand"}"
        panel.setOnClickListener { safe { if (eligible()) { expanded = !expanded; redraw(); main.removeCallbacks(collapse); if (expanded) main.postDelayed(collapse, 10000) } else hide() } }
        var downX = 0f
        panel.setOnTouchListener { v, motion ->
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = motion.x; true }
                MotionEvent.ACTION_UP -> { safe {
                    if (kotlin.math.abs(motion.x - downX) > dp(48) && e.dismissible) { events.remove(e.key); expanded = false; redraw() }
                    else v.performClick()
                }; true }
                else -> true
            }
        }
    }
    private fun manageIcons() {
        restoreIcons()
        if (!enabled("icons")) return
        val island = Rect((context.resources.displayMetrics.widthPixels - params.width) / 2 + params.x, params.y,
            (context.resources.displayMetrics.widthPixels + params.width) / 2 + params.x, params.y + params.height)
        listOf("notificationIcons", "statusIcons", "clock").forEach { name ->
            val id = context.resources.getIdentifier(name, "id", "com.android.systemui")
            val v = host.rootView.findViewById<View>(id) ?: return@forEach
            val rect = Rect(); v.getGlobalVisibleRect(rect)
            if (Rect.intersects(rect, island)) { hiddenIcons[v] = v.alpha; v.alpha = 0f }
        }
    }
    private fun restoreIcons() { hiddenIcons.forEach { (v, alpha) -> if (v.alpha == 0f) v.alpha = alpha }; hiddenIcons.clear() }
    private fun hide() {
        animator?.cancel(); animator = null; restoreIcons()
        if (attached) { try { window.removeViewImmediate(panel) } catch (_: Exception) { }; attached = false }
    }
    fun dispose() {
        disposed = true; main.removeCallbacksAndMessages(null); hide(); media?.stop(); media = null
        host.removeOnLayoutChangeListener(layoutListener)
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        context.contentResolver.unregisterContentObserver(observer); events.clear()
    }
}
