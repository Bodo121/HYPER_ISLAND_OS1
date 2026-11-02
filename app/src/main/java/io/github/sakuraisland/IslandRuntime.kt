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

data class IslandAction(val title: String, val intent: PendingIntent)
data class IslandReply(val intent: PendingIntent, val inputs: Array<android.app.RemoteInput>)

data class IslandEvent(val key: String, val title: String, val text: String, val priority: Int,
    val time: Long = SystemClock.elapsedRealtime(), val expires: Long = Long.MAX_VALUE,
    val intent: PendingIntent? = null, val art: Bitmap? = null, val media: MediaController? = null,
    val dismissible: Boolean = true, val kind: String = "notification",
    val appIcon: android.graphics.drawable.Drawable? = null,
    val actions: List<IslandAction> = emptyList(), val appLabel: String? = null,
    val chargingBoost: Boolean = false, val reply: IslandReply? = null)

class IslandRuntime(val host: View) {
    private val context = host.context
    private val main = Handler(Looper.getMainLooper())
    private val window = context.getSystemService(WindowManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private var options = Config.defaults.toMap()
    private val events = linkedMapOf<String, IslandEvent>()
    // Cache small launcher-icon ConstantStates, never store private notification text or Bitmaps.
    private val appIconCache = object : LinkedHashMap<String, android.graphics.drawable.Drawable.ConstantState?>(20, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.drawable.Drawable.ConstantState?>) =
            size > 20
    }
    private fun applicationIcon(packageName: String): android.graphics.drawable.Drawable? {
        val cached = appIconCache[packageName]
        if (cached != null) return cached.newDrawable(context.resources)
        val icon = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        appIconCache[packageName] = icon?.constantState
        return icon
    }
    private var expanded = false
    private var attached = false
    private var disposed = false
    private var failed = false
    private var errors = 0
    private var currentKey: String? = null
    private var autoExpandKey: String? = null
    private val repliedKeys = mutableMapOf<String, Pair<Long, String>>()
    private var replyDialog: AlertDialog? = null
    private var selectedNotificationKey: String? = null
    private var animator: ValueAnimator? = null
    private var targetWidth = -1
    private var targetHeight = -1
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
                NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> refreshDndState()
                Intent.ACTION_SCREEN_OFF -> { expanded = false; hide() }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_CONFIGURATION_CHANGED, Intent.ACTION_SCREEN_ON -> { expanded = false; redraw() }
                Intent.ACTION_BATTERY_CHANGED -> {
                    val charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    lastBatteryLevel = if(level in 0..scale) level * 100 / scale else -1
                    if (enabled("charging") && charging && !wasCharging) {
                        val batteryLabel = if(level in 0..scale) "${level * 100 / scale}% battery" else "Charging connected"
                        events["battery"] = IslandEvent("battery", "Charging", batteryLabel, 85,
                            expires = SystemClock.elapsedRealtime() + 6000,
                            kind = "charging")
                        if (enabled("charging_expand")) autoExpandKey = "battery"
                        redraw()
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
    private var lastBatteryLevel = -1
    private val notificationManager by lazy { context.getSystemService(NotificationManager::class.java) }
    private var lastZenMode: Int? = null
    /** DND state change is a system status event, not an ordinary app notification. */
    fun refreshDndState() { safe {
        val mode = notificationManager?.currentInterruptionFilter ?: return@safe
        val previous = lastZenMode
        lastZenMode = mode
        if(previous == null || previous == mode || !enabled("enabled")) return@safe
        val active = mode != NotificationManager.INTERRUPTION_FILTER_ALL
        val label = if(active) "Do Not Disturb is on" else "Do Not Disturb is off"
        events["zen"] = IslandEvent("zen", label,
            if(active) "Notifications are silenced by your system settings" else "Normal alerts restored",
            95, expires=SystemClock.elapsedRealtime()+3500L,kind="system")
        autoExpandKey="zen"
        redraw()
        Diagnostics.record("DND status reflected in island (active=$active)")
    } }
    /** Only replace an identified DND visual when our matching event is on-screen. */
    fun replaceDndVisual(message: String): Boolean {
        if (disposed || failed || !enabled("enabled") || !eligible()) return false
        val msg=message.lowercase(java.util.Locale.ROOT).trim()
        if (!(msg.contains("dnd") || msg.contains("do not disturb"))) return false
        refreshDndState()
        val item=events["zen"] ?: return false
        return item.expires > SystemClock.elapsedRealtime() &&
            currentKey == "zen" && attached && panel.isAttachedToWindow
    }

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
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        host.addOnLayoutChangeListener(layoutListener)
        loadConfig()
        refreshDndState()
    }
    private val relayout = Runnable { safe { redraw() } }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        main.removeCallbacks(relayout)
        main.postDelayed(relayout, 48L)
    }
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
        if (!enabled("enabled")) return@safe
        if (sbn.user != UserHandle.getUserHandleForUid(Process.myUid())) {
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
        repliedKeys.entries.removeAll { it.value.first < now }
        // Only ignore a notification reflecting the user's actual sent text.
        // A new incoming message in the same chat must still appear promptly.
        val lastReply = repliedKeys[id]
        if (lastReply != null && lastReply.first > now) {
            val current = listOfNotNull(
                n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                runCatching {
                    (n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull()
                        as? Bundle)?.getCharSequence("text")?.toString()
                }.getOrNull()
            )
            if (current.any { it.trim() == lastReply.second }) {
                Diagnostics.record("Notif skipped: echoed outgoing reply package=${sbn.packageName}")
                return@safe
            }
        }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0 || n.visibility == Notification.VISIBILITY_SECRET) {
            Diagnostics.record("Notif ignored: summary or secret, package=${sbn.packageName}")
            Diagnostics.publish(context)
            return@safe
        }
        val call = n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_MISSED_CALL
        val timer = n.category == Notification.CATEGORY_ALARM || n.category == Notification.CATEGORY_STOPWATCH
        val boost = Policy.isXiaomiChargeBoost(sbn.packageName,
            n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            n.actions?.any { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() } == true)
        if (n.category == Notification.CATEGORY_TRANSPORT) return@safe
        if (!call && !timer && !boost && n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return@safe
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
        // A number of apps publish a short ticker in EXTRA_TEXT and a full
        // preview in EXTRA_BIG_TEXT. Prefer the longest permitted public text.
        val lastChatText = runCatching {
            (n.extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull()
                as? Bundle)?.getCharSequence("text")?.toString()
        }.getOrNull()
        val preview = (lastChatText?.takeUnless { it.isBlank() }
            ?: listOfNotNull(
                n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ).maxByOrNull { it.length })?.trim()?.takeUnless { it.isBlank() }
            ?: "Open notification"
        // Action PendingIntents are kept in memory only; never serialized/logged.
        val actions = n.actions?.asSequence()?.filter {
            it.actionIntent != null && it.remoteInputs.isNullOrEmpty()
        }?.take(2)?.map {
            IslandAction(it.title?.toString()?.take(32) ?: "Action", it.actionIntent)
        }?.toList().orEmpty()
        val reply = n.actions?.firstOrNull { a ->
            a.actionIntent != null &&
                a.remoteInputs?.any { it.allowFreeFormInput && !it.resultKey.isNullOrBlank() } == true
        }?.let { a ->
            val inputs = a.remoteInputs?.filter { it.allowFreeFormInput && !it.resultKey.isNullOrBlank() }?.toTypedArray()
            if (inputs.isNullOrEmpty()) null else IslandReply(a.actionIntent, inputs)
        }
        // No raw message content is recorded in diagnostics.
        events[id] = IslandEvent(id, heading, preview.take(750), if (call) 100 else if (boost) 92 else if (timer) 80 else 65,
            now, now + if (call || timer) 30000 else 8500,
            n.contentIntent, dismissible = sbn.isClearable && !call && !timer,
            kind = if (call) "call" else if (timer) "timer" else "notification",
            appIcon = applicationIcon(sbn.packageName)
                ?: runCatching { n.smallIcon?.loadDrawable(context) }.getOrNull(), actions = actions,
            appLabel = runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString().take(30)
            }.getOrNull(), chargingBoost = boost, reply = reply)
        while (events.size > 32) events.remove(events.keys.firstOrNull { it != "media" } ?: break)
        if (enabled("auto_expand_notifications") && !call && !timer) autoExpandKey = id
        selectedNotificationKey = null
        Diagnostics.record("Notif accepted: package=${sbn.packageName} type=${if(call) "call" else if(timer) "timer" else "alert"} eligible=${eligible()}")
        Diagnostics.publish(context)
        redraw()
    } }
    fun removed(key: String) { safe {
        recentDelivery.remove(key)
        repliedKeys.remove(key)
        events.remove(key)
        if (selectedNotificationKey == key) selectedNotificationKey = null
        redraw()
    } }

    /**
     * Called synchronously from the privileged SystemUI heads-up renderer.
     * Skip the OEM *visual banner only* when this exact standard notification
     * has already been rendered as a visible expanded SakuraIsland card.
     * Native notification records, sounds, vibrations, shade entries and
     * full-screen/call/alarm behavior remain untouched.
     */
    /**
     * Suppress only the duplicate standard heads-up *presentation*, never the
     * Android notification record or its shade/sound/vibration. A strict
     * SystemUI-host gate means the OEM UI remains the fallback if the island
     * module is disabled, crashed, off-screen, or not attached.
     */
    fun showInsteadOfHeadsUp(sbn: StatusBarNotification): Boolean {
        if (disposed || failed || !enabled("enabled") || !enabled("notifications") ||
            !enabled("replace_heads_up") || !enabled("auto_expand_notifications") ||
            Looper.myLooper() != Looper.getMainLooper() || !eligible()) return false
        val n = sbn.notification
        val hasSimpleAction = n.actions?.any { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() } == true
        val hasReply = n.actions?.any { a -> a.remoteInputs?.isNotEmpty() == true } == true
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val boost = Policy.isXiaomiChargeBoost(sbn.packageName,title,hasSimpleAction)
        if (sbn.packageName == "com.android.systemui" || n.fullScreenIntent != null ||
            n.category in listOf(Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL,
                Notification.CATEGORY_ALARM, Notification.CATEGORY_REMINDER, Notification.CATEGORY_ERROR) ||
            (n.category == Notification.CATEGORY_SYSTEM && !boost) ||
            n.visibility == Notification.VISIBILITY_SECRET ||
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            ((n.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
                !sbn.isClearable) && !boost) ||
            (n.contentIntent == null && !hasSimpleAction && !hasReply) ||
            !Policy.allowNotificationPackage(sbn.packageName,enabled("all_apps"),options["apps"].orEmpty()))
            return false
        // The notification may be at the heads-up decision stage *before*
        // the collection listener posts it. Create its island event now.
        // Reentrancy is bounded by recentDelivery, and a failure never blocks OEM UI.
        if ((events[sbn.key]?.expires ?: 0L) <= SystemClock.elapsedRealtime()) posted(sbn)
        val candidate = events[sbn.key] ?: return false
        if (candidate.kind != "notification" || candidate.expires <= SystemClock.elapsedRealtime() ||
            !attached || !panel.isAttachedToWindow) return false
        // Give new notifications an opportunity to expand, but don't depend
        // on their animation finishing before the OEM HUN decision executes.
        if (currentKey == sbn.key && !expanded) {
            autoExpandKey = sbn.key
            redraw()
        }
        if (currentKey != sbn.key) return false
        Diagnostics.record("OEM heads-up visual suppressed: package=${sbn.packageName}")
        return true
    }
    /**
     * OEM charging hook calls this with a Xiaomi-supported charging label.
     * Read-only: neither writes to power supply nor guesses live charger watts.
     */
    fun nativeChargingMode(raw: String) { safe {
        if (!wasCharging || !enabled("charging")) return@safe
        val label = raw.take(64).trim()
        if (label.isEmpty()) return@safe
        val fast = listOf("turbo", "hyper", "super", "quick", "67w").any {
            label.contains(it, ignoreCase = true)
        }
        Diagnostics.record("Xiaomi charging API observed, fastModeHint=$fast")
        Diagnostics.publish(context)
        if (fast) {
            val e = events["battery"] ?: return@safe
            if (!e.text.contains("Turbo charging")) {
                events["battery"] = e.copy(text = "${e.text} · Turbo charging")
                redraw()
            }
        }
    } }

    /**
     * Called from the installed Xiaomi MIUIStrongToast charging model.
     * The OEM's floating charge toast is replaced only after a real SakuraIsland
     * charging event was successfully attached; we do not hide other toast types.
     * The model's fractional battery level is accepted only when it matches the
     * ordinary system battery broadcast closely (within 3 percentage points).
     */
    fun replaceNativeChargeToast(chargeLabel: String, oemLevel: Float): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper() || disposed || failed ||
            !enabled("replace_charge_toast") || !enabled("charging") || !wasCharging ||
            chargeLabel.isBlank() || !eligible()) return false
        val original = events["battery"] ?: return false
        val calibrated = if(Policy.validOemBatteryLevel(oemLevel, lastBatteryLevel)) {
            java.lang.String.format(java.util.Locale.US, "%.2f", oemLevel) + "%"
        } else null
        val label = calibrated ?: if(lastBatteryLevel >= 0) "$lastBatteryLevel%" else "Charging"
        val hint = if(listOf("turbo","super","67w","fast").any {
            chargeLabel.contains(it, ignoreCase=true)
        }) " · Turbo charging" else " battery"
        events["battery"] = original.copy(text=label + hint,
            expires=SystemClock.elapsedRealtime() + 6500L)
        if (enabled("charging_expand")) autoExpandKey = "battery"
        redraw()
        val replaced = attached && panel.isAttachedToWindow &&
            (currentKey == "battery" || (events[currentKey]?.chargingBoost == true && expanded))
        if (replaced) {
            Diagnostics.record("Replaced Xiaomi charging toast with native data; fractionalLevel=${calibrated != null}")
            Diagnostics.publish(context)
        }
        return replaced
    }

    /** Focusable SystemUI-owned prompt; never inject text into the non-focusable island window. */
    private fun openReplyDialog(event: IslandEvent, reply: IslandReply) {
        if (!eligible() || replyDialog?.isShowing == true) return
        val field = EditText(context).apply {
            hint = "Type a reply"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 1; maxLines = 4
            setPadding(dp(18),dp(12),dp(18),dp(12))
        }
        val container = FrameLayout(context).apply {
            setPadding(dp(12),dp(8),dp(12),dp(8))
            addView(field, FrameLayout.LayoutParams(-1,-2))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Reply · ${event.appLabel ?: event.title}")
            .setView(container)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Send",null)
            .create()
        // SystemUI is privileged to present this trusted overlay.
        dialog.window?.setType(2017)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val message = field.text.toString().trim()
                if(message.isEmpty()) {
                    field.error = "Write a message"
                    return@setOnClickListener
                }
                try {
                    val results = Bundle()
                    reply.inputs.forEach { results.putCharSequence(it.resultKey,message) }
                    val fillIn = Intent().apply {
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    }
                    android.app.RemoteInput.addResultsToIntent(reply.inputs, fillIn, results)
                    reply.intent.send(context, 0, fillIn)
                    repliedKeys[event.key] = (SystemClock.elapsedRealtime() + 6000L) to message
                    events.remove(event.key)
                    expanded = false
                    dialog.dismiss()
                    redraw()
                    Diagnostics.record("Inline reply sent; package not logged, content never stored")
                } catch (e: PendingIntent.CanceledException) {
                    field.error = "Reply action expired; open the app"
                } catch (e: Exception) {
                    field.error = "Couldn't send; open the app"
                }
            }
            field.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.setOnDismissListener { if(replyDialog === dialog) replyDialog = null }
        replyDialog = dialog
        try { dialog.show() } catch (t: Throwable) {
            replyDialog = null
            Diagnostics.record("Reply dialog unavailable: ${t.javaClass.simpleName}")
        }
    }

    private fun eligible() = enabled("enabled") && power.isInteractive && !keyguard.isKeyguardLocked &&
        host.isAttachedToWindow && host.isShown && context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    private fun pendingNotifications(now: Long = SystemClock.elapsedRealtime()) =
        events.values.filter { it.kind == "notification" && it.expires > now }.sortedByDescending { it.time }

    private fun nextNotification() {
        val items = pendingNotifications()
        if (items.size < 2) return
        val current = selectedNotificationKey ?: currentKey
        selectedNotificationKey = Policy.nextAlertKey(items.map { it.key }, current)
        autoExpandKey = selectedNotificationKey
        redraw()
    }
    private fun redraw() {
        main.removeCallbacks(expiry)
        main.removeCallbacks(progressTick)
        val now = SystemClock.elapsedRealtime()
        events.entries.removeAll { it.value.expires <= now }
        val selected = Policy.select(events.values.map { EventRank(it.key, it.priority, it.time, it.expires) }, now)
        val manual = selectedNotificationKey?.let { events[it] }?.takeIf {
            it.expires > now && it.kind == "notification" && (events[selected]?.priority ?: 0) <= 65
        }
        val key = manual?.key ?: selected
        val event = events[key]
        if (!eligible() || (event == null && !enabled("idle"))) {
            currentKey = null; expanded = false; hide(); return
        }
        if (currentKey != key) {
            expanded = false
            currentKey = key
        }
        // Updating the same app notification key with a new message should
        // re-open the preview as well, rather than leave the island collapsed.
        if (key != null && key == autoExpandKey && eligible()) {
            expanded = true
            autoExpandKey = null
            main.removeCallbacks(collapse)
            main.postDelayed(collapse, value("alert_duration", 3000, 12000).toLong())
        }
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
            expanded && event?.kind == "notification" -> minOf(dp(310), screenWidth - dp(32))
            expanded && event?.kind == "system" -> maxOf(dp(144),compactHeight + dp(104))
            expanded && event?.kind == "charging" -> minOf(dp(292), screenWidth - dp(32))
            expanded && (event?.kind == "call" || event?.kind == "timer") -> minOf(dp(310), screenWidth - dp(32))
            expanded -> minOf(dp(330), screenWidth - dp(24))
            event == null || cameraOnly -> idleWidth
            else -> compactWidth
        }
        val height = when {
            expanded && event?.kind == "notification" -> {
                val cameraPx = ((cutout?.bottom ?: dp(25)) - params.y + dp(9)).coerceAtLeast(dp(38))
                Policy.alertCardHeightDp(Policy.alertPreviewLines(event.text).coerceAtMost(value("alert_lines",2,6)),
                    (cameraPx / context.resources.displayMetrics.density).toInt(), event.actions.isNotEmpty() || event.intent != null).let(::dp)
            }
            expanded && event?.kind == "charging" -> maxOf(dp(156), compactHeight + dp(116))
            expanded && (event?.kind == "call" || event?.kind == "timer") -> maxOf(dp(190), compactHeight + dp(140))
            expanded && event?.kind == "media" -> maxOf(dp(244), compactHeight + dp(203))
            expanded -> maxOf(dp(232), compactHeight + dp(185))
            event == null || cameraOnly -> idleHeight
            else -> compactHeight
        }

        // Only request a refresh rate the physical panel actually advertises.
        // This cannot force 120Hz when HyperOS/thermal/display mode does not allow it.
        val supportedHz = host.display?.supportedModes?.map { it.refreshRate }?.filter { it > 0f }.orEmpty()
        val chosenHz = supportedHz.filter { it <= 121f }.maxOrNull() ?: 0f
        val requestedHz = if(enabled("high_refresh") && chosenHz > 60f) chosenHz else 0f
        if (params.preferredRefreshRate != requestedHz) params.preferredRefreshRate = requestedHz
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
                compactHeight, enabled("chroma"), enabled("motion"),
                value("alert_lines", 2, 6), if (enabled("notification_queue")) pendingNotifications(now).size else 0) { which ->
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
                        "reply" -> event.reply?.let { openReplyDialog(event, it) }
                        "notification_action_0", "notification_action_1" -> {
                            val index = if(which.endsWith("1")) 1 else 0
                            event.actions.getOrNull(index)?.intent?.send()
                            events.remove(event.key)
                            expanded = false; redraw()
                        }
                        "collapse" -> { expanded = false; redraw() }
                        "queue_next" -> nextNotification()
                        "dismiss_card" -> {
                            events.remove(event.key)
                            selectedNotificationKey = null
                            expanded = false
                            redraw()
                        }
                    }
                }
            }
            panel.setOnClickListener {
                safe {
                    if (eligible()) {
                        if (enabled("haptics")) panel.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
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
            // Compact gestures do not contain any actionable child controls.
            // Expanded views release all pointer events to their real buttons/seekbar.
            if (!expanded) {
                val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true
                    override fun onSingleTapUp(e: MotionEvent): Boolean {
                        panel.performClick()
                        return true
                    }
                    override fun onLongPress(e: MotionEvent) {
                        if (enabled("haptics")) panel.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        expanded = true
                        redraw()
                    }
                    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                        val horizontal = kotlin.math.abs(velocityX) > kotlin.math.abs(velocityY)
                        if(horizontal && event.kind == "notification" && enabled("notification_queue")) {
                            nextNotification()
                            return true
                        }
                        return false
                    }
                })
                panel.setOnTouchListener { _, motion -> gestures.onTouchEvent(motion) }
            } else panel.setOnTouchListener(null)
            if (expanded && event.media != null) main.postDelayed(progressTick,1000L)
        }

        if (!attached) {
            params.width = width; params.height = height
            targetWidth = width; targetHeight = height
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
        // SystemUI frequently emits duplicate layout callbacks, including once
        // per animation frame. Do not restart an in-flight size animation.
        if (targetWidth == w && targetHeight == h && animator?.isRunning == true) return
        targetWidth = w; targetHeight = h
        animator?.cancel()
        if (!enabled("motion")) {
            params.width = w; params.height = h
            if (attached) window.updateViewLayout(panel, params)
            return
        }
        if (params.width == w && params.height == h) {
            if (attached) window.updateViewLayout(panel, params)
            return
        }
        val fromW = params.width; val fromH = params.height
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = value("speed", 100, 700).toLong()
            interpolator = android.view.animation.PathInterpolator(.22f, 0f, .12f, 1f)
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
        animator?.cancel(); animator = null
        targetWidth = -1; targetHeight = -1
        main.removeCallbacks(progressTick); restoreIcons()
        if (attached) { try { window.removeViewImmediate(panel) } catch (_: Exception) { }; attached = false }
    }
    fun dispose() {
        disposed = true; replyDialog?.dismiss(); replyDialog = null; main.removeCallbacksAndMessages(null); hide(); media?.stop(); media = null
        host.removeOnLayoutChangeListener(layoutListener)
        appIconCache.clear()
        try { context.unregisterReceiver(receiver) } catch (_: Exception) { }
        context.contentResolver.unregisterContentObserver(observer); events.clear()
    }
}

