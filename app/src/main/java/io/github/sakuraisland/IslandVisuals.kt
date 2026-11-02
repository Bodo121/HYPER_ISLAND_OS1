package io.github.sakuraisland

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.*
import android.text.TextUtils

/** Platform View renderer: avoids loading Compose's lifecycle/runtime into Xiaomi SystemUI. */
internal class IslandVisuals(private val context: Context, private val panel: LinearLayout) {
    private val sakura = Color.rgb(255, 117, 151)
    private val white = Color.rgb(243, 243, 245)
    private val secondary = Color.rgb(182, 179, 189)
    private var mediaProgress: ScrubTrack? = null
    private var timeLabel: TextView? = null
    private var durationLabel: TextView? = null
    private var player: MediaController? = null
    private var durationMs = 0L
    private var dragging = false

    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }

    private fun text(label: String, size: Float, color: Int = white, weight: Boolean = false) =
        TextView(context).apply {
            text = label; textSize = size; setTextColor(color)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (weight) setTypeface(typeface, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun mix(a: Int, b: Int, f: Float): Int {
        val t = f.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0,255)
        return Color.rgb(ch(Color.red(a), Color.red(b)), ch(Color.green(a), Color.green(b)), ch(Color.blue(a), Color.blue(b)))
    }

    private fun artColor(art: Bitmap?): Int {
        if (art == null || art.isRecycled || art.width < 1 || art.height < 1) return sakura
        return try {
            var red = 0L; var green = 0L; var blue = 0L; var count = 0
            // Sparse sampling avoids bitmap allocations and heavy Palette processing in SystemUI.
            for (y in 1..5) for (x in 1..5) {
                val pixel = art.getPixel(((art.width-1) * x / 6).coerceAtLeast(0),
                    ((art.height-1) * y / 6).coerceAtLeast(0))
                if (Color.alpha(pixel) > 180) {
                    red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel); count++
                }
            }
            if (count == 0) sakura else Color.rgb((red/count).toInt(), (green/count).toInt(), (blue/count).toInt())
        } catch (_: Exception) { sakura }
    }

    private fun image(event: IslandEvent, px: Int, radius: Int): View {
        return ImageView(context).apply {
            if (event.art != null && !event.art.isRecycled) setImageBitmap(event.art)
            else if (event.appIcon != null) setImageDrawable(event.appIcon)
            else setImageResource(when(event.kind) {
                "charging" -> android.R.drawable.ic_lock_idle_charging
                "call" -> android.R.drawable.sym_action_call
                "timer" -> android.R.drawable.ic_lock_idle_alarm
                else -> android.R.drawable.ic_media_play
            })
            scaleType = if (event.art != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            if (event.art == null && event.appIcon != null) setPadding(dp(5),dp(5),dp(5),dp(5))
            background = rounded(mix(artColor(event.art), Color.BLACK, .63f), radius)
            clipToOutline = true
            contentDescription = when(event.kind) { "media" -> "Album art"; else -> "Activity icon" }
            layoutParams = LinearLayout.LayoutParams(dp(px), dp(px))
        }
    }

    /** App should call this only with real progress from an active media session. */
    fun refreshProgress() {
        val controller = player ?: return
        val d = durationMs
        if (d <= 0L || dragging) return
        val playback = controller.playbackState ?: return
        var pos = playback.position.coerceAtLeast(0)
        if (playback.state == PlaybackState.STATE_PLAYING && playback.lastPositionUpdateTime > 0L) {
            val elapsed = (SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime).coerceAtLeast(0L)
            pos += (elapsed * playback.playbackSpeed).toLong()
        }
        pos = pos.coerceIn(0L, d)
        mediaProgress?.progress = (pos * 1000 / d).toInt()
        timeLabel?.text = fmt(pos)
        durationLabel?.text = fmt(d)
    }

    private fun fmt(ms: Long): String {
        val sec = (ms.coerceAtLeast(0L)/1000)
        return "${sec/60}:${(sec%60).toString().padStart(2,'0')}"
    }

    fun idle() {
        player = null; mediaProgress = null
        panel.removeAllViews()
        panel.setPadding(0,0,0,0)
        panel.background = rounded(Color.BLACK,24)
        panel.clipToOutline = true
        panel.contentDescription = "Idle camera capsule"
        panel.setOnClickListener(null)
        panel.setOnTouchListener(null)
    }

    /**
     * Visual shell is split when expanded: a narrow camera cap at the top, and a wider
     * card beginning below the status row. Outside the cap the top corners are transparent.
     */
    fun render(e: IslandEvent, expanded: Boolean, topClearancePx: Int, cameraCapPx: Int,
               compactHeightPx: Int, chroma: Boolean, motion: Boolean, action: (String) -> Unit) {
        player = e.media
        durationMs = e.media?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        mediaProgress = null; timeLabel = null; durationLabel = null; dragging = false
        panel.removeAllViews()
        panel.setPadding(dp(if (expanded) 15 else 10),
            if (expanded) maxOf(topClearancePx, dp(if(e.kind=="notification") 37 else 43)) else dp(2),
            dp(if (expanded) 15 else 10), dp(if (expanded) 9 else 3))
        panel.gravity = if (expanded) Gravity.TOP else Gravity.CENTER_VERTICAL
        panel.clipToOutline = false
        val accent = when(e.kind) {
            "charging" -> Color.rgb(0,230,118)
            "notification" -> {
                val iconBitmap = (e.appIcon as? android.graphics.drawable.BitmapDrawable)?.bitmap
                if (chroma && iconBitmap != null) artColor(iconBitmap) else Color.rgb(113,92,128)
            }
            else -> if(chroma) artColor(e.art) else sakura
        }
        panel.background = IslandShape(expanded, cameraCapPx, compactHeightPx.coerceAtLeast(dp(34)), accent, dp(28), dp(22))

        if (expanded && e.kind == "notification") {
            renderAlert(e, accent, action)
            panel.contentDescription = "${e.title}, notification preview. Open notification to read full message."
            return
        }

        if (!expanded) {
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(image(e,24,7))
            // Physical camera remains visually unobstructed in this reserved center gap.
            row.addView(Space(context), LinearLayout.LayoutParams(0,1,1f))
            row.addView(if (e.kind == "media") {
                EqualizerView(context).apply {
                    isPlaying = e.media?.playbackState?.state == PlaybackState.STATE_PLAYING
                    tint = if (chroma) mix(accent, white, .45f) else sakura
                    animateBars = motion
                }
            } else text(when(e.kind) {
                "charging" -> if (android.os.Build.DEVICE == "taoyao") "67W MAX" else e.text
                "call" -> "Call"
                "timer" -> "Timer"
                else -> "●"
            },12f,if(e.kind=="charging") Color.rgb(105,255,163) else white),
                LinearLayout.LayoutParams(dp(if(e.kind=="charging") 68 else 36), dp(24)))
            panel.addView(row, LinearLayout.LayoutParams(-1, dp(30)))
        } else {
            val eyebrow = text(when(e.kind) {
                "media" -> "NOW PLAYING"
                "charging" -> "CHARGING"
                "call" -> "CALL"
                "timer" -> "ALARM / TIMER"
                else -> "NOTIFICATION"
            },10f, mix(accent,white,.42f),true).apply { letterSpacing = .16f }
            val eyebrowRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            eyebrowRow.addView(eyebrow, LinearLayout.LayoutParams(0,dp(18),1f))
            // Hardware support is documented by Xiaomi. These are capability labels:
            // they do not claim Dolby processing is active or 67 W is being delivered.
            if (android.os.Build.DEVICE == "taoyao" && (e.kind == "media" || e.kind == "charging")) {
                val capability = text(if(e.kind == "media") "DOLBY ATMOS" else "UP TO 67W",10f,
                    if(e.kind == "charging") Color.rgb(116,255,169) else secondary,true).apply {
                    contentDescription = if(e.kind == "media") "Device supports Dolby Atmos; current audio effect state unknown"
                        else "Device supports charging up to 67 watts; actual charging power unknown"
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                }
                eyebrowRow.addView(capability,LinearLayout.LayoutParams(-2,dp(18)))
            }
            panel.addView(eyebrowRow,LinearLayout.LayoutParams(-1,dp(18)))
            val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(image(e,64,14))
            val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            names.addView(text(e.title,16f,white,true),LinearLayout.LayoutParams(-1,dp(25)))
            names.addView(text(e.text,12.5f,secondary),LinearLayout.LayoutParams(-1,dp(22)))
            row.addView(names,LinearLayout.LayoutParams(0,dp(65),1f).apply { marginStart = dp(12) })
            panel.addView(row,LinearLayout.LayoutParams(-1,dp(76)).apply { topMargin = dp(4) })

            if (e.kind == "media" && durationMs > 0L) {
                val accentColor = accent
                val seek = ScrubTrack(context).apply {
                    this.accent = mix(accentColor, Color.WHITE, .35f)
                    seekEnabled = (e.media?.playbackState?.actions ?: 0L) and PlaybackState.ACTION_SEEK_TO != 0L
                    onDragChanged = { dragging = it }
                    onSeek = { progress ->
                        val target = ((progress.toLong() * durationMs) / 1000L).coerceIn(0L,durationMs)
                        e.media?.transportControls?.seekTo(target)
                    }
                }
                mediaProgress = seek
                panel.addView(seek, LinearLayout.LayoutParams(-1,dp(28)).apply { topMargin = dp(2) })
                val timeline = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                timeLabel = text("0:00",10f,secondary)
                durationLabel = text(fmt(durationMs),10f,secondary)
                timeline.addView(timeLabel,LinearLayout.LayoutParams(0,dp(17),1f))
                timeline.addView(durationLabel,LinearLayout.LayoutParams(-2,dp(17)))
                panel.addView(timeline)
                refreshProgress()
            } else {
                panel.addView(View(context).apply {
                    background = rounded(mix(accent,Color.BLACK,.7f),2)
                },LinearLayout.LayoutParams(-1,dp(2)).apply { topMargin = dp(7); bottomMargin = dp(8) })
            }
            val controls = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
            }
            if (e.media != null) {
                val c = e.media
                val flags = c.playbackState?.actions ?: 0L
                if (flags and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) {
                    control(controls,android.R.drawable.ic_media_previous,"Previous",false) { action("previous") }
                }
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                if (flags and (PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE) != 0L) {
                    control(controls,if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                        if(playing)"Pause" else "Play",true) { action("playpause") }
                }
                if (flags and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) {
                    control(controls,android.R.drawable.ic_media_next,"Next",false) { action("next") }
                }
            }
            // Only real PendingIntents generate an Open button; no fake quick-reply or call action.
            if (e.intent != null) {
                control(controls, android.R.drawable.ic_menu_view, "Open",false) { action("open") }
            }
            control(controls, android.R.drawable.ic_menu_close_clear_cancel, "Collapse",false) { action("collapse") }
            panel.addView(controls,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin = dp(7) })
        }
        panel.contentDescription = "${e.title}, ${e.text}. ${if(expanded) "Expanded" else "Tap to expand"}."
    }

    /** Compact notification card: full multi-line preview, no media timeline or empty playback area. */
    private fun renderAlert(e: IslandEvent, accent: Int, action: (String) -> Unit) {
        val eyebrow = text("NEW NOTIFICATION", 10f, mix(accent,white,.50f), true).apply {
            letterSpacing = .12f
        }
        panel.addView(eyebrow, LinearLayout.LayoutParams(-1,dp(16)))
        val row = LinearLayout(context).apply { gravity = Gravity.TOP }
        row.addView(image(e,44,12),LinearLayout.LayoutParams(dp(44),dp(44)))
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        val sender = text(e.title,14.5f,white,true).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        content.addView(sender,LinearLayout.LayoutParams(-1,dp(22)))
        val preview = text(e.text,12.7f,secondary).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.TOP
            setLineSpacing(dp(2).toFloat(),1f)
        }
        content.addView(preview,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(2) })
        row.addView(content,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(12) })
        panel.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(9) })
        val actions = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        fun actionChip(title:String, primary:Boolean, click:()->Unit) {
            val chip = TextView(context).apply {
                text=title
                maxLines=1
                ellipsize=TextUtils.TruncateAt.END
                textSize=12.5f
                setTextColor(if(primary) white else secondary)
                setTypeface(typeface,android.graphics.Typeface.BOLD)
                gravity=Gravity.CENTER
                background=rounded(if(primary) mix(accent,Color.BLACK,.70f) else Color.rgb(35,32,40),17)
                isClickable=true; isFocusable=true
                contentDescription=title+" notification"
                setOnClickListener { click() }
            }
            val width = if(title.length > 9) 94 else if(primary) 74 else 65
            actions.addView(chip, LinearLayout.LayoutParams(dp(width),dp(34)).apply {
                marginStart=dp(6)
            })
        }
        // Preserve the most useful safe one-tap native action (e.g. Xiaomi's
        // "Stop" charging boost). RemoteInput actions remain in the shade;
        // do not fabricate a reply composer inside a non-focusable window.
        e.actions.firstOrNull()?.let { native ->
            actionChip(native.title, true) { action("notification_action_0") }
        }
        if(e.intent != null) actionChip("Open", e.actions.isEmpty()) { action("open") }
        actionChip("Close",false) { action("collapse") }
        panel.addView(actions,LinearLayout.LayoutParams(-1,dp(36)).apply { topMargin=dp(11) })
    }

    private fun control(row: LinearLayout, icon: Int, desc: String, primary: Boolean, click: () -> Unit) {
        val control = TransportButton(context, desc, primary).apply { setOnClickListener { click() } }
        row.addView(control,LinearLayout.LayoutParams(dp(44),dp(44)).apply {
            marginStart = dp(3); marginEnd = dp(3)
        })
    }

    /** Draw vector playback symbols, avoiding Android's pixelated legacy drawable icons. */
    private class TransportButton(context: Context, private val label: String,
                                  private val primary: Boolean) : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        init {
            contentDescription = label
            isClickable = true
            isFocusable = true
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width/2f; val cy = height/2f
            val d = resources.displayMetrics.density
            val rad = 19*d
            if(primary) {
                p.color = Color.rgb(244,244,246)
                canvas.drawCircle(cx,cy,rad,p)
            }
            p.color = if(primary) Color.BLACK else Color.rgb(241,238,245)
            stroke.color = p.color; stroke.strokeWidth = 2.1f*d
            val unit = 1*d
            when(label) {
                "Play" -> {
                    val path = android.graphics.Path().apply {
                        moveTo(cx-5*unit,cy-8*unit); lineTo(cx+8*unit,cy)
                        lineTo(cx-5*unit,cy+8*unit); close()
                    }
                    canvas.drawPath(path,p)
                }
                "Pause" -> {
                    canvas.drawRoundRect(RectF(cx-7*unit,cy-8*unit,cx-2*unit,cy+8*unit),1.2f*unit,1.2f*unit,p)
                    canvas.drawRoundRect(RectF(cx+2*unit,cy-8*unit,cx+7*unit,cy+8*unit),1.2f*unit,1.2f*unit,p)
                }
                "Previous","Next" -> {
                    val forward = label == "Next"
                    val sign = if(forward) 1f else -1f
                    val path = android.graphics.Path().apply {
                        moveTo(cx-7*unit*sign,cy-8*unit)
                        lineTo(cx+5*unit*sign,cy)
                        lineTo(cx-7*unit*sign,cy+8*unit)
                        close()
                    }
                    canvas.drawPath(path,p)
                    val lineX = cx+8*unit*sign
                    canvas.drawRoundRect(RectF(lineX-1.3f*unit,cy-8*unit,lineX+1.3f*unit,cy+8*unit),1.0f*unit,1.0f*unit,p)
                }
                "Open" -> {
                    canvas.drawLine(cx-7*unit,cy+7*unit,cx+7*unit,cy-7*unit,stroke)
                    canvas.drawLine(cx-1*unit,cy-7*unit,cx+7*unit,cy-7*unit,stroke)
                    canvas.drawLine(cx+7*unit,cy-7*unit,cx+7*unit,cy+1*unit,stroke)
                }
                "Collapse" -> {
                    canvas.drawLine(cx-6*unit,cy-6*unit,cx+6*unit,cy+6*unit,stroke)
                    canvas.drawLine(cx-6*unit,cy+6*unit,cx+6*unit,cy-6*unit,stroke)
                }
            }
        }
    }

    /** A 3dp progress track; thumb appears only while seeking, no oversized default SeekBar thumb. */
    private class ScrubTrack(context: Context): View(context) {
        var accent: Int = Color.rgb(255,117,151)
        var progress: Int = 0
            set(value) { field = value.coerceIn(0,1000); invalidate() }
        var seekEnabled = false
        var onDragChanged: ((Boolean) -> Unit)? = null
        var onSeek: ((Int) -> Unit)? = null
        private var seeking = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val d get() = resources.displayMetrics.density
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val y = height/2f
            val thick = 3*d
            paint.color = Color.rgb(61,59,66)
            canvas.drawRoundRect(RectF(0f,y-thick/2,width.toFloat(),y+thick/2),thick,thick,paint)
            paint.color = accent
            val end = width*progress/1000f
            canvas.drawRoundRect(RectF(0f,y-thick/2,end.coerceAtLeast(thick/2),y+thick/2),thick,thick,paint)
            if(seeking) canvas.drawCircle(end,y,5*d,paint)
        }
        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (!seekEnabled) return false
            when(event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    seeking = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onDragChanged?.invoke(true)
                    adjust(event.x)
                    return true
                }
                android.view.MotionEvent.ACTION_MOVE -> { adjust(event.x); return true }
                android.view.MotionEvent.ACTION_UP -> {
                    adjust(event.x)
                    onSeek?.invoke(progress)
                    seeking = false; onDragChanged?.invoke(false)
                    invalidate(); performClick(); return true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    seeking = false; onDragChanged?.invoke(false); invalidate(); return true
                }
            }
            return super.onTouchEvent(event)
        }
        private fun adjust(x: Float) {
            progress = (1000f*x/width.coerceAtLeast(1)).toInt().coerceIn(0,1000)
        }
        override fun performClick(): Boolean { super.performClick(); return true }
    }

    private class IslandShape(
        private val expanded: Boolean, private val capWidth: Int, private val headHeight: Int,
        private val accent: Int, private val radius: Int, private val capRadius: Int
    ): Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(canvas: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            if (w<=0 || h<=0) return
            val shade = Color.rgb(6,5,9)
            if (expanded) {
                val cardTop = (headHeight - 2).toFloat()
                paint.shader = LinearGradient(0f,cardTop,w,h,
                    intArrayOf(mixColor(accent,shade,.53f),mixColor(accent,shade,.83f),shade),
                    null, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(0f,cardTop,w,h),radius.toFloat(),radius.toFloat(),paint)
                paint.shader = null
                paint.color = Color.BLACK
                val left = ((w - capWidth) / 2).coerceAtLeast(0f)
                canvas.drawRoundRect(RectF(left,0f,w-left,(headHeight+capRadius/3).toFloat()),
                    capRadius.toFloat(),capRadius.toFloat(),paint)
            } else {
                paint.shader = null; paint.color = Color.BLACK
                canvas.drawRoundRect(RectF(0f,0f,w,h),capRadius.toFloat(),capRadius.toFloat(),paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Drawable opacity")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
        private fun mixColor(a:Int,b:Int,f:Float):Int {
            return Color.rgb(
                (Color.red(a)+(Color.red(b)-Color.red(a))*f).toInt().coerceIn(0,255),
                (Color.green(a)+(Color.green(b)-Color.green(a))*f).toInt().coerceIn(0,255),
                (Color.blue(a)+(Color.blue(b)-Color.blue(a))*f).toInt().coerceIn(0,255))
        }
    }

    private class EqualizerView(context: Context): View(context) {
        var tint = Color.rgb(255,117,151)
        var isPlaying = false
        var animateBars = true
        private val brush = Paint(Paint.ANTI_ALIAS_FLAG)
        private var phase = 0f
        private var animation: ValueAnimator? = null
        override fun onAttachedToWindow() { super.onAttachedToWindow(); startIfNeeded() }
        override fun onDetachedFromWindow() { animation?.cancel(); animation = null; super.onDetachedFromWindow() }
        private fun startIfNeeded() {
            if (!isPlaying || !animateBars || animation != null) return
            animation = ValueAnimator.ofFloat(0f,6.28318f).apply {
                duration = 1100L
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            brush.color = tint
            val center = height/2f
            val barW = dp(3).toFloat()
            for (i in 0..3) {
                val strength = if(isPlaying) (0.5f+0.5f*kotlin.math.sin(phase+i*1.5f)) else 0.2f
                val h = dp(5) + strength*dp(13)
                val x = i * dp(6).toFloat()
                canvas.drawRoundRect(RectF(x,center-h/2,x+barW,center+h/2),barW/2,barW/2,brush)
            }
        }
        private fun dp(i:Int) = (i * resources.displayMetrics.density).toInt()
    }
}
