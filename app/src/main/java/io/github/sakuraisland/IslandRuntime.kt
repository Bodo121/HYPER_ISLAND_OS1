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
        if (!eligible() || (event == null && !enabled("idle"))) {
            currentKey = null; expanded = false; hide(); return
        }
        if (event == null) {
            currentKey = null
            expanded = false
            buildIdleContent()
        } else {
            if (currentKey != key) { expanded = false; currentKey = key }
            val nextExpiry = events.values.minOf { it.expires }
            if (nextExpiry != Long.MAX_VALUE) main.postDelayed(expiry, (nextExpiry - now).coerceAtLeast(1))
            buildContent(event)
        }
        // An idle pill is purely decorative and must not intercept status bar touches.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (event == null) params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val cutout = host.rootWindowInsets?.displayCutout?.boundingRectTop?.takeUnless { it.isEmpty }
        val screenWidth = context.resources.displayMetrics.widthPixels
        params.x = if (cutout != null) cutout.centerX() - screenWidth / 2 else 0
        val compactHeight = maxOf(dp(value("height", 28, 52)), (cutout?.bottom ?: 0) + dp(4))
        params.y = ((cutout?.top ?: dp(6)) + dp(value("offset", -4, 24))).coerceAtLeast(0)
        val cutoutWidth = (cutout?.width() ?: 0) + dp(24)
        val targetWidth = if (event == null) maxOf(dp(78), cutoutWidth) else maxOf(dp(if (expanded) 340 else value("width", 120, 240)), if (expanded) 0 else cutoutWidth)
        val width = minOf(targetWidth, screenWidth - dp(24))
        val height = if (expanded) maxOf(dp(164), compactHeight + dp(118)) else compactHeight
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
    private fun buildIdleContent() {
        panel.removeAllViews()
        panel.setPadding(0, 0, 0, 0)
        panel.setOnClickListener(null)
        panel.setOnTouchListener(null)
        panel.contentDescription = "Sakura Island idle"
    }
    private fun buildContent(e: IslandEvent) {
        panel.removeAllViews()
        val cutoutBottom = host.rootWindowInsets?.displayCutout?.boundingRectTop?.bottom ?: dp(26)
        val topPadding = if (expanded) maxOf(dp(30), cutoutBottom - params.y + dp(6)) else dp(2)
        panel.setPadding(dp(if (expanded) 18 else 10), topPadding, dp(if (expanded) 18 else 10), dp(6))

        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        if (expanded) {
            val art = ImageView(context).apply {
                if (e.art != null) setImageBitmap(e.art)
                else setImageResource(android.R.drawable.ic_media_play)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    setColor(0xFF302B35.toInt())
                    cornerRadius = dp(12).toFloat()
                }
                clipToOutline = true
                contentDescription = "Activity artwork"
            }
            row.addView(art, LinearLayout.LayoutParams(dp(52), dp(52)))
            val titles = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titles.addView(text(e.title, 16f).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
            })
            titles.addView(text(e.text, 13f).apply {
                setTextColor(0xFFB9B4C2.toInt())
                maxLines = 1
            })
            row.addView(titles, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(12)
            })
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))

            e.media?.let { controller ->
                val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (duration > 0L) {
                    val position = (controller.playbackState?.position ?: 0L).coerceIn(0L, duration)
                    panel.addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 1000
                        progress = ((position * 1000L) / duration).toInt()
                        progressTintList = ColorStateList.valueOf(0xFFE6A5CB.toInt())
                        progressBackgroundTintList = ColorStateList.valueOf(0xFF48404F.toInt())
                    }, LinearLayout.LayoutParams(-1, dp(3)).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(8)
                    })
                }
            }

            val controls = LinearLayout(context).apply {
                gravity = Gravity.CENTER
                orientation = LinearLayout.HORIZONTAL
            }
            fun actionButton(icon: Int, label: String, action: () -> Unit) {
                val control = ImageButton(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    contentDescription = label
                    background = GradientDrawable().apply {
                        setColor(0xFF302B35.toInt())
                        cornerRadius = dp(24).toFloat()
                    }
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(11), dp(11), dp(11), dp(11))
                    setOnClickListener { safe { if (eligible()) action() else hide() } }
                }
                controls.addView(control, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                })
            }
            e.media?.let { controller ->
                val actions = controller.playbackState?.actions ?: 0L
                if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L)
                    actionButton(android.R.drawable.ic_media_previous, "Previous track") { controller.transportControls.skipToPrevious() }
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                if (actions and (PlaybackState.ACTION_PLAY_PAUSE or if (playing) PlaybackState.ACTION_PAUSE else PlaybackState.ACTION_PLAY) != 0L)
                    actionButton(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        if (playing) "Pause" else "Play") {
                        if (playing) controller.transportControls.pause() else controller.transportControls.play()
                    }
                if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L)
                    actionButton(android.R.drawable.ic_media_next, "Next track") { controller.transportControls.skipToNext() }
            }
            e.intent?.let { destination ->
                actionButton(android.R.drawable.ic_menu_view, "Open activity") {
                    destination.send(); expanded = false; redraw()
                }
            }
            actionButton(android.R.drawable.ic_menu_close_clear_cancel, "Collapse island") {
                expanded = false; redraw()
            }
            panel.addView(controls, LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = if (e.media != null) dp(4) else dp(14)
            })
        } else {
            if (e.art != null) {
                row.addView(ImageView(context).apply {
                    setImageBitmap(e.art)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = GradientDrawable().apply {
                        setColor(0xFF302B35.toInt())
                        cornerRadius = dp(6).toFloat()
                    }
                    clipToOutline = true
                    contentDescription = "Album artwork"
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
            }
            row.addView(text(if (e.media != null) "Music" else if (e.priority == 100) "Call" else "●", 12f),
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    marginStart = if (e.art != null) dp(4) else 0
                })
            row.addView(Space(context), LinearLayout.LayoutParams(dp(40), 1))
            row.addView(text(if (e.media?.playbackState?.state == PlaybackState.STATE_PLAYING) "♫" else e.title, 12f),
                LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(row)
        }
        panel.contentDescription = "${e.title}. ${if (expanded) e.text else "Tap to expand"}"
        panel.setOnClickListener {
            safe {
                if (eligible()) {
                    expanded = !expanded
                    redraw()
                    main.removeCallbacks(collapse)
                    if (expanded) main.postDelayed(collapse, 10000)
                } else hide()
            }
        }
        var downX = 0f
        panel.setOnTouchListener { v, motion ->
            when (motion.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = motion.x; true }
                MotionEvent.ACTION_UP -> {
                    safe {
                        if (kotlin.math.abs(motion.x - downX) > dp(48) && e.dismissible) {
                            events.remove(e.key); expanded = false; redraw()
                        } else v.performClick()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
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

