package local.watchremote

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.SystemClock
import android.text.TextUtils
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import kotlin.math.sin
import kotlin.math.cos

internal const val INK = Color.BLACK
internal val WHITE = Color.WHITE
internal val MUTED = Color.rgb(179, 179, 179)
internal val GREEN = Color.rgb(30, 215, 96)
internal val PANEL = Color.rgb(24, 24, 24)
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun shape(color: Int, radius: Float = 20f) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
internal fun Context.motionEnabled() = ValueAnimator.areAnimatorsEnabled() && getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("animations", true)
internal fun touchMotion(view: View) {
    view.setOnTouchListener { v, event ->
        if (v.context.motionEnabled() && v.isEnabled) when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(.93f).scaleY(.93f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setInterpolator(OvershootInterpolator(1.4f)).setDuration(190).start()
        }
        false
    }
}
internal fun watchText(context: Context, text: String, size: Float = 14f) = TextView(context).apply {
    this.text = text; textSize = size; setTextColor(WHITE); gravity = Gravity.CENTER; includeFontPadding = false
}
internal fun watchButton(context: Context, text: String, action: () -> Unit) = Button(context).apply {
    this.text = text; textSize = 14f; isAllCaps = false; setTextColor(WHITE)
    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    minHeight = context.dp(48); minimumWidth = 0
    setPadding(context.dp(12), context.dp(10), context.dp(12), context.dp(10))
    background = RippleDrawable(ColorStateList.valueOf(0x301ed760), shape(PANEL, context.dp(8).toFloat()), null)
    stateListAnimator = null
    setOnClickListener { action() }; touchMotion(this)
}

internal class Symbol(private val type: String, private val color: Int) : Drawable() {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.9f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    override fun draw(c: Canvas) {
        val save = c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat()); c.scale(bounds.width() / 24f, bounds.height() / 24f)
        pen.color = color; pen.style = Paint.Style.STROKE; path.reset()
        when(type) {
            "play" -> { pen.style = Paint.Style.FILL; path.moveTo(8f, 5f); path.lineTo(20f, 12f); path.lineTo(8f, 19f); path.close(); c.drawPath(path, pen) }
            "pause" -> { pen.style = Paint.Style.FILL; c.drawRoundRect(7f, 5f, 10f, 19f, .8f, .8f, pen); c.drawRoundRect(14f, 5f, 17f, 19f, .8f, .8f, pen) }
            "previous", "next" -> {
                if (type == "previous") { c.translate(24f, 0f); c.scale(-1f, 1f) }
                pen.style = Paint.Style.FILL; path.moveTo(5f, 5f); path.lineTo(16f, 12f); path.lineTo(5f, 19f); path.close(); c.drawPath(path, pen)
                c.drawRoundRect(17f, 5f, 19f, 19f, .8f, .8f, pen)
            }
            "library" -> { c.drawRoundRect(4f, 6f, 9f, 20f, 1.4f, 1.4f, pen); c.drawLine(13f, 5f, 13f, 20f, pen); c.drawLine(17f, 5f, 20f, 19f, pen) }
            "adjust" -> { for (i in 0..2) { val x = 6f + i * 6; c.drawLine(x, 4f, x, 20f, pen); pen.style = Paint.Style.FILL; c.drawCircle(x, if (i == 1) 15f else 9f, 2.5f, pen); pen.style = Paint.Style.STROKE } }
            "back" -> { path.moveTo(15f, 5f); path.lineTo(8f, 12f); path.lineTo(15f, 19f); c.drawPath(path, pen) }
            "more" -> { pen.style = Paint.Style.FILL; for (i in 0..2) c.drawCircle(5f + 7 * i, 12f, 1.8f, pen) }
        }
        c.restoreToCount(save)
    }
    override fun setAlpha(alpha: Int) { pen.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { pen.colorFilter = filter }
    @Deprecated("Deprecated in Android") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
internal class IconButton(context: Context, icon: String, label: String, private val primary: Boolean = false, action: () -> Unit) : ImageButton(context) {
    private var current = icon
    init {
        contentDescription = label; setPadding(context.dp(13), context.dp(13), context.dp(13), context.dp(13))
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = RippleDrawable(ColorStateList.valueOf(0x301ed760), shape(if (primary) GREEN else 0x40282828, 200f), null)
        setImageDrawable(Symbol(icon, if (primary) INK else WHITE)); setOnClickListener { action() }; touchMotion(this)
    }
    fun symbol(value: String) { if (current != value) { current = value; setImageDrawable(Symbol(value, if (primary) INK else WHITE)) } }
}
internal class NavButton(context: Context, icon: String, label: String, action: () -> Unit) : LinearLayout(context) {
    init {
        orientation = VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true; contentDescription = label
        background = RippleDrawable(ColorStateList.valueOf(0x301ed760), null, shape(Color.WHITE, 100f))
        addView(ImageView(context).apply { setImageDrawable(Symbol(icon, WHITE)) }, LayoutParams(context.dp(18), context.dp(18)))
        addView(watchText(context, label, 9f).apply { setTextColor(MUTED); setPadding(0, context.dp(3), 0, 0) })
        setOnClickListener { action() }; touchMotion(this)
    }
}

/** Small filtered artwork, slow drift. Animation stops on pause and detach. */
internal class ArtworkBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint()
    private val target = RectF()
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var blend = 1f
    private var phase = 0f
    private var lightField: Bitmap? = null
    private var previousField: Bitmap? = null
    private var fade: ValueAnimator? = null
    private var drift: ValueAnimator? = null
    private var lastFrame = 0L
    fun artwork(bitmap: Bitmap?) {
        fade?.cancel(); previous = current
        current = bitmap?.let { Bitmap.createScaledBitmap(it, 18, 18, true) }
        previousField = lightField
        lightField = if (bitmap == null) null else {
            val hsv = FloatArray(3)
            val samples = mutableListOf<Pair<Float, Int>>()
            for (y in 1..5) for (x in 1..5) {
                val color = bitmap.getPixel((bitmap.width * x / 6).coerceAtMost(bitmap.width - 1), (bitmap.height * y / 6).coerceAtMost(bitmap.height - 1))
                Color.colorToHSV(color, hsv)
                if (hsv[2] > .15f) samples.add((hsv[1] + hsv[2] * .2f) to color)
            }
            val colors = samples.sortedByDescending { it.first }.map { it.second }.ifEmpty { listOf(GREEN) }
            val glows = (0..2).map { i ->
                Color.colorToHSV(colors[(i * colors.size / 3).coerceAtMost(colors.lastIndex)], hsv)
                hsv[1] = (hsv[1] * .85f + .12f).coerceAtMost(.8f); hsv[2] = hsv[2].coerceIn(.6f, .92f)
                val color = Color.HSVToColor(hsv)
                Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { glow ->
                    val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        shader = RadialGradient(64f, 64f, 64f, intArrayOf(color, (color and 0x00ffffff) or 0x70000000, color and 0x00ffffff), floatArrayOf(0f, .4f, 1f), Shader.TileMode.CLAMP)
                    }
                    Canvas(glow).drawCircle(64f, 64f, 64f, brush)
                }
            }
            Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888).also { field ->
                val c = Canvas(field)
                val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                val positions = arrayOf(RectF(-32f, -30f, 165f, 168f), RectF(55f, -20f, 245f, 170f), RectF(-12f, 62f, 205f, 278f))
                glows.forEachIndexed { i, glow -> c.drawBitmap(glow, null, positions[i], p) }
            }
        }
        if (context.motionEnabled() && isAttachedToWindow) {
            blend = 0f
            fade = ValueAnimator.ofFloat(0f, 1f).apply { duration = 350; addUpdateListener { blend = it.animatedValue as Float; invalidate() }; start() }
        } else { blend = 1f; previous = null; invalidate() }
    }
    fun motion(play: Boolean) {
        val enabled = play && current != null && context.motionEnabled() && context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("dynamicBackground", true)
        if (!enabled) { drift?.cancel(); drift = null; return }
        if (drift != null) return
        drift = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 11000; repeatMode = ValueAnimator.RESTART; repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                if (SystemClock.elapsedRealtime() - lastFrame >= 80) {
                    phase = it.animatedValue as Float; lastFrame = SystemClock.elapsedRealtime(); invalidate()
                }
            }; start()
        }
    }
    fun suspendMotion() {
        drift?.cancel(); drift = null; fade?.cancel(); fade = null
        blend = 1f; previousField = null; previous = null
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shade.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(0x70000000, 0xa0000000.toInt(), 0xf0000000.toInt()), floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP)
    }
    override fun onDraw(canvas: Canvas) {
        val angle = phase * (Math.PI * 2).toFloat()
        fun drawField(field: Bitmap?, alpha: Float) {
            if (field == null || alpha <= 0) return
            val save = canvas.save()
            canvas.rotate(sin(angle) * 30f, width * .5f, height * .4f)
            val cx = width * (.5f + .14f * sin(angle + 1f))
            val cy = height * (.4f + .12f * cos(angle))
            val radius = width * .78f
            target.set(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.alpha = (alpha * (205 + 35 * sin(angle))).toInt()
            canvas.drawBitmap(field, null, target, paint)
            canvas.restoreToCount(save)
        }
        drawField(previousField, 1f - blend)
        drawField(lightField, blend)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        if (blend >= 1f) { previous = null; previousField = null }
    }
    override fun onDetachedFromWindow() { fade?.cancel(); drift?.cancel(); drift = null; super.onDetachedFromWindow() }
}
internal class ProgressRing(context: Context) : View(context) {
    private val playhead = SmoothProgress()
    private var sampleAt = Long.MIN_VALUE
    private var identity = ""
    private var active = true
    private var scheduled = false
    private var lastFrame = 0L
    private val choreographer = Choreographer.getInstance()
    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            if (!active || !isAttachedToWindow) return
            if (frameTimeNanos - lastFrame >= 32000000L) { lastFrame = frameTimeNanos; invalidate() }
            scheduleFrame()
        }
    }
    fun sample(state: Playback) {
        val key = "${state.deviceId}|${state.uri.ifBlank { state.title }}"
        if (state.sampled != sampleAt || key != identity) {
            val now = SystemClock.elapsedRealtime()
            playhead.update(key, state.positionNow(now).toDouble(), state.duration.toDouble(), state.playing, now)
            sampleAt = state.sampled; identity = key
        }
        invalidate(); scheduleFrame()
    }
    fun visible(value: Boolean) {
        active = value
        if (!value) { choreographer.removeFrameCallback(frame); scheduled = false }
        else { invalidate(); scheduleFrame() }
    }
    private fun scheduleFrame() {
        if (!scheduled && active && isAttachedToWindow && context.motionEnabled() && playhead.needsFrames(SystemClock.elapsedRealtime())) {
            scheduled = true; choreographer.postFrameCallback(frame)
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); scheduleFrame() }
    override fun onDetachedFromWindow() { choreographer.removeFrameCallback(frame); scheduled = false; super.onDetachedFromWindow() }
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bounds = RectF()
    override fun onDraw(canvas: Canvas) {
        val inset = context.dp(6).toFloat(); bounds.set(inset, inset, width - inset, height - inset)
        pen.strokeWidth = context.dp(4).toFloat(); pen.color = Color.rgb(48, 48, 48)
        canvas.drawArc(bounds, 128f, 284f, false, pen); pen.color = GREEN
        val fraction = if (playhead.duration > 0) (playhead.position(SystemClock.elapsedRealtime()) / playhead.duration).toFloat() else 0f
        canvas.drawArc(bounds, 128f, 284f * fraction, false, pen)
    }
}
internal class HomePanel(context: Context, val navigate: (String) -> Unit, val action: (String) -> Unit) : FrameLayout(context) {
    private val backdrop = ArtworkBackdrop(context)
    private val ring = ProgressRing(context)
    val device = watchButton(context, "播放设备") { navigate("devices") }
    val cover = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER; background = shape(PANEL, context.dp(2).toFloat()); clipToOutline = true; contentDescription = "专辑封面"
        setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14)); setImageDrawable(Symbol("library", MUTED))
    }
    val title = watchText(context, "声环", 16f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val artist = watchText(context, "连接你的 Spotify", 10f).apply { setTextColor(MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    val previous = IconButton(context, "previous", "上一首") { action("previous") }
    val play = IconButton(context, "play", "播放", true) { action("toggle") }
    val next = IconButton(context, "next", "下一首") { action("next") }
    val library = NavButton(context, "library", "歌单") { navigate("playlists") }
    val adjust = NavButton(context, "adjust", "调节") { navigate("adjust") }
    val more = NavButton(context, "more", "更多") { navigate("settings") }
    private var running = false
    private var foreground = true
    init {
        setBackgroundColor(INK)
        listOf(backdrop, ring, device, cover, title, artist, previous, play, next, library, adjust, more).forEach { addView(it) }
        device.textSize = 9f; device.background = null; device.setTextColor(MUTED)
        device.maxLines = 1; device.ellipsize = TextUtils.TruncateAt.END; device.setPadding(0,0,0,0)
    }
    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w); val height = MeasureSpec.getSize(h); setMeasuredDimension(width, height)
        fun measure(v: View, x: Int, y: Int) = v.measure(MeasureSpec.makeMeasureSpec(context.dp(x), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(context.dp(y), MeasureSpec.EXACTLY))
        listOf(backdrop, ring).forEach { it.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)) }
        measure(device, 148, 48); measure(cover, 54, 54); measure(title, 181, 22); measure(artist, 171, 16)
        listOf(previous, play, next, library, adjust, more).forEach { measure(it, 48, 48) }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val center = width / 2; val top = ((height - context.dp(233)) / 2).coerceAtLeast(0)
        fun place(v: View, offset: Int, y: Int) { val x = center + context.dp(offset) - v.measuredWidth / 2; val y0 = top + context.dp(y); v.layout(x, y0, x + v.measuredWidth, y0 + v.measuredHeight) }
        backdrop.layout(0,0,width,height); ring.layout(0,0,width,height)
        place(device, 0, -3); place(cover, 0, 36); place(title, 0, 92); place(artist, 0, 114)
        place(previous, -55, 132); place(play, 0, 132); place(next, 55, 132)
        place(library, -50, 180); place(adjust, 0, 180); place(more, 50, 180)
    }
    fun artwork(image: Bitmap?) {
        if (image == null) {
            cover.setPadding(context.dp(14), context.dp(14), context.dp(14), context.dp(14)); cover.setImageDrawable(Symbol("library", MUTED))
        } else { cover.setPadding(0,0,0,0); cover.setImageBitmap(image) }
        backdrop.artwork(image); backdrop.motion(running && foreground)
    }
    fun visible(value: Boolean) { foreground = value; ring.visible(value); if (!value) backdrop.suspendMotion() else backdrop.motion(running) }
    fun update(state: Playback, busy: Boolean, authorized: Boolean) {
        device.text = state.deviceName; title.text = if (authorized) state.title else "连接 Spotify"; title.contentDescription = title.text
        artist.text = if (authorized) state.artist else "在更多中完成电脑配对"
        play.symbol(if (state.playing) "pause" else "play"); play.contentDescription = if (state.playing) "暂停" else "播放"
        play.isEnabled = !busy && state.allows(if (state.playing) "pausing" else "resuming")
        previous.isEnabled = !busy && state.allows("skipping_prev"); next.isEnabled = !busy && state.allows("skipping_next")
        for (button in listOf(previous, next, play)) button.alpha = if (button.isEnabled) 1f else .4f
        running = state.playing; backdrop.motion(running && foreground); progress(state)
    }
    fun progress(state: Playback) { ring.sample(state) }
}

/** Round-screen modal shared by mode selection and confirmations. No platform grey list. */
internal data class WatchChoice(val title: String, val highlighted: Boolean = false, val selected: Boolean = false, val action: () -> Unit)
internal class WatchMenu(context: Context, title: String, message: String? = null, choices: List<WatchChoice>) : android.app.Dialog(context, android.R.style.Theme_Material_NoActionBar) {
    private val list = ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; setBackgroundColor(INK) }
    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(30), context.dp(8), context.dp(30), context.dp(30))
        }
        val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(IconButton(context, "back", "关闭$title") { dismiss() }, LinearLayout.LayoutParams(context.dp(48), context.dp(48)))
        heading.addView(watchText(context, title, 16f).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(heading, LinearLayout.LayoutParams(-1, context.dp(48)))
        if (message != null) column.addView(watchText(context, message, 13f).apply {
            setTextColor(MUTED); setPadding(0, context.dp(8), 0, context.dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
        choices.forEach { choice ->
            column.addView(watchButton(context, choice.title + if (choice.selected) "  ✓" else "") {
                dismiss(); choice.action()
            }.apply {
                minHeight = context.dp(48); gravity = Gravity.CENTER
                setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
                isSelected = choice.selected
                contentDescription = choice.title + if (choice.selected) "，当前选中" else ""
                background = RippleDrawable(ColorStateList.valueOf(0x401ed760), shape(if (choice.highlighted) GREEN else PANEL, context.dp(28).toFloat()), null)
                setTextColor(if (choice.highlighted) INK else WHITE)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(6) })
        }
        list.addView(column); setContentView(list)
        setCanceledOnTouchOutside(false)
        window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(INK))
            setDimAmount(0f); setLayout(-1, -1); setWindowAnimations(0)
        }
    }
    override fun onStart() {
        super.onStart(); window?.setLayout(-1, -1)
        if (context.motionEnabled()) { list.alpha = 0f; list.animate().alpha(1f).setDuration(150).start() }
    }
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val delta = event.getAxisValue(MotionEvent.AXIS_SCROLL).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (delta != 0f) { list.smoothScrollBy(0, (-delta * context.dp(24)).toInt()); return true }
        }
        return super.onGenericMotionEvent(event)
    }
}
