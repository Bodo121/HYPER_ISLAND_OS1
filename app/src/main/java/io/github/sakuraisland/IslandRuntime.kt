package io.github.sakuraisland

import android.app.*
import android.content.*
import android.database.ContentObserver
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
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
    val dismissible: Boolean = true, val kind: String = "notification",
    val appIcon: android.graphics.drawable.Drawable? = null)

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
    private val visuals = IslandVisuals(context, panel)
    private val progressTick = object : Runnable {
        override fun run() {
            if (attached && expanded && eligible()) {
                safe { visuals.refreshProgress() }
                main.postDelayed(this, 1000L)
            }
        }
    }
    private val expiry = Runnable { safe { redraw() } }
    private val collapse = Runnable { safe { expanded = false; redraw() } }
    private val observer = object : ContentObserver(main) { override fun onChange(selfChange: Boolean) { loadConfig() } }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { safe {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { expanded = false; hide() }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_SCREEN_ON -> { expanded = false; redraw() }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    if (enabled("charging") && charging && !wasCharging) {
                        events["battery"] = IslandEvent("battery", "Charging", "${level * 100 / scale}% battery", 85,
                            expires = SystemClock.elapsedRealtime() + 6000,
                            kind = "charging"); redraw()
                    }
                    if (!charging && wasCharging) {
                        events.remove("battery")
                        redraw()
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
            addAction(Intent.ACTION_SCREEN_ON)
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
            val hadMedia = enabled("enabled") && enabled("media")
            options = next
            val needsMedia = enabled("enabled") && enabled("media")
            if (!needsMedia && media != null) {
                media?.stop(); media = null; events.remove("media")
            }
            if (needsMedia && (media == null || !hadMedia)) {
                media = MediaSource(context, main) { controller -> safe { updateMedia(controller) } }.also { it.start() }
            }
            if (!enabled("enabled")) {
                events.clear()
                expanded = false
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
                    ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART), media = c, kind = "media")
        }
        redraw()
    }
    private val recentDelivery = LinkedHashMap<String, Long>()
    /** Called from native SystemUI's incoming notification stream; never leaks alert text to logs. */
    fun posted(sbn: StatusBarNotification) { safe {
        val now = SystemClock.elapsedRealtime()
        if (!enabled("enabled") || !enabled("notifications") && sbn.notification.category != Notification.CATEGORY_CALL
            && sbn.notification.category != Notification.CATEGORY_ALARM) return@safe
        if (sbn.user != UserHandle.getUserHandleForUid(Process.myUid()) &&
            sbn.user.identifier != -1) {
            Diagnostics.record("Notif ignored: different Android user (package=${sbn.packageName})")
            return@safe
        }
        if (sbn.packageName == "com.android.systemui") return@safe
        val id = sbn.key
        // NotifCollection and upstream coalescer may both deliver an update.
        if ((recentDelivery[id] ?: 0L) > now - 280L) return@safe
        recentDelivery[id] = now
        if (recentDelivery.size > 64) {
            val old = recentDelivery.keys.firstOrNull()
            if (old != null) recentDelivery.remove(old)
        }
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.visibility == Notification.VISIBILITY_SECRET) {
            Diagnostics.record("Notif ignored: summary or secret, package=${sbn.packageName}")
            Diagnostics.publish(context)
            return@safe
        }
        val call = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL
        val timer = n.category == Notification.CATEGORY_ALARM || n.category == Notification.CATEGORY_STOPWATCH
        if (n.category == Notification.CATEGORY_TRANSPORT) return@safe
        if (!call && !timer && n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return@safe
        val allowed = Policy.allowNotificationPackage(sbn.packageName, enabled("all_apps"),
            options["apps"].orEmpty())
        if ((call && !enabled("calls")) || (timer && !enabled("timers")) ||
            (!call && !timer && (!enabled("notifications") || !allowed))) {
            Diagnostics.record("Notif filtered by settings: ${sbn.packageName}")
            Diagnostics.publish(context)
            return@safe
        }
        val heading = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.take(120)
            ?.takeUnless { it.isBlank() } ?: sbn.packageName
        val preview = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: "Open notification"
        // No raw message content is recorded in diagnostics.
        events[id] = IslandEvent(id, heading, preview.take(300), if (call) 100 else if (timer) 80 else 65,
            now, now + if (call || timer) 30000 else 8500,
            n.contentIntent, dismissible = sbn.isClearable && !call && !timer,
            kind = if (call) "call" else if (timer) "timer" else "notification",
            appIcon = runCatching { n.smallIcon?.loadDrawable(context) }.getOrNull())
        while (events.size > 32) events.remove(events.keys.firstOrNull { it != "media" } ?: break)
        Diagnostics.record("Notif accepted: package=${sbn.packageName} type=${if(call) "call" else if(timer) "timer" else "alert"} eligible=${eligible()}")
        Diagnostics.publish(context)
        redraw()
    } }
    fun removed(key: String) { safe {
        recentDelivery.remove(key)
        events.remove(key)
        redraw()
    } }
    private fun eligible() = enabled("enabled") && power.isInteractive && !keyguard.isKeyguardLocked &&
        host.isAttachedToWindow && host.isShown && context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    private fun redraw() {
        main.removeCallbacks(expiry)
        main.removeCallbacks(progressTick)
        val now = SystemClock.elapsedRealtime()
        events.entries.removeAll { it.value.expires <= now }
        val key = Policy.select(events.values.map { EventRank(it.key, it.priority, it.time, it.expires) }, now)
        val event = events[key]
        if (!eligible() || (event == null && !enabled("idle"))) {
            currentKey = null; expanded = false; hide(); return
        }
        if (currentKey != key) { expanded = false; currentKey = key }
        if (event != null) {
            val nextExpiry = events.values.minOfOrNull { it.expires } ?: Long.MAX_VALUE
            if (nextExpiry != Long.MAX_VALUE) main.postDelayed(expiry, (nextExpiry - now).coerceAtLeast(1))
        } else {
            expanded = false
        }
        val cutout = host.rootWindowInsets?.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        val screenWidth = context.resources.displayMetrics.widthPixels
        val centerX = cutout?.centerX() ?: screenWidth / 2
        params.x = centerX - screenWidth / 2
        params.y = ((cutout?.top ?: dp(5)) + dp(value("offset", -4, 24))).coerceAtLeast(0)
        val cutoutWidth = (cutout?.width() ?: dp(12)) + dp(12)
        val compactHeight = maxOf(dp(value("height", 28, 52)), (cutout?.bottom ?: 0) - params.y + dp(5))
        // Distinct idle capsule sizing: never reuse the physical cutout diameter as pill width.
        // The image reference is a horizontal ~3:1 capsule, not a circular notch mask.
        val idleHeight = maxOf(dp(value("idle_height", 28, 44)), (cutout?.bottom ?: 0) - params.y + dp(4))
        val safeWidth = safeCompactWidth(centerX, screenWidth).coerceAtLeast(cutoutWidth)
        val idleWidth = minOf(maxOf(dp(value("idle_width", 72, 132)), cutoutWidth + dp(8)), safeWidth)
        val compactWidth = minOf(maxOf(dp(value("width", 120, 240)), cutoutWidth + dp(58)), safeWidth)
        val cameraOnly = compactWidth < cutoutWidth + dp(58)
        val width = when {
            expanded -> minOf(dp(352), screenWidth - dp(18))
            event == null || cameraOnly -> idleWidth
            else -> compactWidth
        }
        val height = when {
            expanded -> maxOf(dp(268), compactHeight + dp(215))
            event == null || cameraOnly -> idleHeight
            else -> compactHeight
        }

        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (event == null) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            visuals.idle()
        } else {
            val clearance = ((cutout?.bottom ?: dp(25)) - params.y + dp(8)).coerceAtLeast(dp(30))
            // The camera occupies a real hole in the display; render an unobstructed
            // fallback capsule rather than squeezing album artwork over that hole.
            if (!expanded && cameraOnly) {
                visuals.idle()
            } else visuals.render(event, expanded, clearance, cutoutWidth + dp(8),
                compactHeight, enabled("chroma"), enabled("motion")) { which ->
                safe {
                    when (which) {
                        "previous" -> event.media?.transportControls?.skipToPrevious()
                        "next" -> event.media?.transportControls?.skipToNext()
                        "playpause" -> event.media?.let { c ->
                            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                            else c.transportControls.play()
                        }
                        "open" -> {
                            event.intent?.send()
                            expanded = false; redraw()
                        }
                        "collapse" -> { expanded = false; redraw() }
                    }
                }
            }
            panel.setOnClickListener {
                safe {
                    if (eligible()) {
                        expanded = !expanded
                        redraw()
                        main.removeCallbacks(collapse)
                        if (expanded) main.postDelayed(collapse, 12000)
                    } else hide()
                }
            }
            // Do not swallow ACTION_DOWN on the root panel: doing so prevents
            // descendants (transport controls and scrubbing) from getting touches.
            // The parent click handler expands/collapses only on empty content.
            panel.setOnTouchListener(null)
            if (expanded && event.media != null) main.postDelayed(progressTick,1000L)
        }

        if (!attached) {
            params.width = width; params.height = height
            window.addView(panel, params); attached = true
            Diagnostics.record("Sakura island window attached")
            // Read-only dimensions collected on the device, never hardcoded from APK mockups.
            Diagnostics.record("Geometry displayWidthPx=$screenWidth density=${context.resources.displayMetrics.density} cutoutTop=${cutout?.flattenToString() ?: "none"} windowX=${params.x} windowY=${params.y} idle=${value("idle_width",72,132)}x${value("idle_height",28,44)}dp")
            Diagnostics.publish(context)
        } else animateSize(width, height)
        manageIcons()
    }

    /** Ignore wide SystemUI parent containers: they include the camera but are not icons. */
    private fun safeCompactWidth(centerX: Int, screenWidth: Int): Int {
        var left = dp(8)
        var right = screenWidth - dp(8)
        val rect = Rect()
        val names = listOf("clock", "notificationIcons", "statusIcons",
            "notification_icon_area_inner", "status_bar_clock", "system_icons")
        for (name in names) {
            val id = context.resources.getIdentifier(name, "id", "com.android.systemui")
            if (id == 0) continue
            val view = host.rootView.findViewById<View>(id) ?: continue
            if (!view.isShown || !view.getGlobalVisibleRect(rect)) continue
            // Containers spanning the cutout were erroneously reducing the music pill
            // to ~40dp and pushing album art directly onto the physical lens.
            if (rect.width() > screenWidth / 3 || rect.contains(centerX, rect.centerY())) continue
            if (rect.right < centerX) left = maxOf(left, rect.right + dp(6))
            if (rect.left > centerX) right = minOf(right, rect.left - dp(6))
        }
        return (minOf(centerX - left, right - centerX).coerceAtLeast(dp(20)) * 2)
    }
    private fun animateSize(w: Int, h: Int) {
        animator?.cancel()
        if (!enabled("motion")) {
            params.width = w; params.height = h
            if (attached) window.updateViewLayout(panel, params)
            return
        }
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
        animator?.cancel(); animator = null; main.removeCallbacks(progressTick); restoreIcons()
        if (attached) { try { window.removeViewImmediate(panel) } catch (_: Exception) { }; attached = false }
    }
    fun dispose() {
        disposed = true; main.removeCallbacksAndMessages(null); hide(); media?.stop(); media = null
        host.removeOnLayoutChangeListener(layoutListener)
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        context.contentResolver.unregisterContentObserver(observer); events.clear()
    }
}

