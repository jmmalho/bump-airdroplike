package pt.johnny.bump.ui.handoff

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import pt.johnny.bump.data.Files
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun View.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

object Palette {
    val INK = Color.rgb(13, 22, 41)
    val MUTED = Color.rgb(96, 108, 130)
    val ACCENT = Color.rgb(31, 107, 255)
    val SUCCESS = Color.rgb(22, 163, 106)
    val DANGER = Color.rgb(229, 72, 77)

    fun kindColor(k: Files.Kind) = when (k) {
        Files.Kind.PDF -> Color.rgb(229, 57, 53)
        Files.Kind.IMAGE -> Color.rgb(124, 77, 255)
        Files.Kind.VIDEO -> Color.rgb(245, 124, 0)
        Files.Kind.AUDIO -> Color.rgb(0, 150, 136)
        Files.Kind.APK -> Color.rgb(46, 160, 67)
        Files.Kind.LINK -> Color.rgb(0, 122, 255)
        Files.Kind.TEXT -> Color.rgb(96, 108, 130)
        Files.Kind.DOC -> ACCENT
    }
}

/**
 * Tunable parameters of the NameDrop-style handoff. Defaults match the
 * reference "NameDrop Lab" (Georgvm/iOS-NameDrop-Animation).
 */
data class RippleParams(
    val speed: Float = 0.45f,
    val amplitude: Float = 10f,
    val sharpness: Float = 0.8f,
    val width: Float = 0.10f,
    val distortion: Float = 1.0f,
    val highlight: Float = 1.0f,
    val chargeSeconds: Float = 1.2f,
    val glowSize: Float = 1.2f,
    val glowIntensity: Float = 1.0f,
) {
    val maxRadius get() = 1.3f + width
    val rippleSeconds get() = maxRadius / max(speed, 0.01f)
    val totalSeconds get() = chargeSeconds + rippleSeconds + 0.4f

    companion object {
        private const val PREFS = "bump_ripple"

        fun load(c: Context): RippleParams {
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = RippleParams()
            return RippleParams(
                p.getFloat("speed", d.speed), p.getFloat("amplitude", d.amplitude), p.getFloat("sharpness", d.sharpness),
                p.getFloat("width", d.width), p.getFloat("distortion", d.distortion), p.getFloat("highlight", d.highlight),
                p.getFloat("charge", d.chargeSeconds), p.getFloat("glowSize", d.glowSize), p.getFloat("glowIntensity", d.glowIntensity),
            )
        }

        fun save(c: Context, r: RippleParams) {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putFloat("speed", r.speed).putFloat("amplitude", r.amplitude).putFloat("sharpness", r.sharpness)
                .putFloat("width", r.width).putFloat("distortion", r.distortion).putFloat("highlight", r.highlight)
                .putFloat("charge", r.chargeSeconds).putFloat("glowSize", r.glowSize).putFloat("glowIntensity", r.glowIntensity)
                .apply()
        }
    }
}

/**
 * NameDrop-style handoff, full screen on BOTH devices, started at the same instant.
 *
 *  Sender   – the shared items wait in a small stack; while the glow charges at the notch they
 *             are pulled into it one after another, each with a pulse of light.
 *  Receiver – the same items come out of the top edge one by one, at exactly the moments they
 *             vanished on the sender. One item flies straight to full screen, already open;
 *             several land in a grid and the first then grows into the gallery.
 *
 * Ripple math: port of the shockwave in Georgvm/iOS-NameDrop-Animation and
 * cyrilzakka/NameDrop (MIT), itself based on Shadertoy "llj3Dz".
 */
class NameDropView(context: Context) : FrameLayout(context) {

    enum class Role { SENDER, RECEIVER }

    var params: RippleParams = RippleParams.load(context)
    var onChargeComplete: (() -> Unit)? = null
    /** Fires [releaseCueLead] seconds before the release, so a swelling sound peaks exactly on it. */
    var onReleaseCue: (() -> Unit)? = null
    var releaseCueLead: Float = pt.johnny.bump.feedback.Feedback.RELEASE_SOUND_LEAD_SECONDS
    var onFinished: (() -> Unit)? = null
    /** Receiver, direct-open: the item now fills the screen exactly like the viewer will show it. */
    var onOpenMoment: (() -> Unit)?
        get() = hero.onOpenMoment
        set(v) { hero.onOpenMoment = v }

    private val overscan = dp(60f)
    private val stage = StageView(context)
    private val glow = GlowView(context)
    private val hero = HeroLayer(context)
    private val action = TextView(context)
    private var timeline: ValueAnimator? = null
    private var running = false
    private var notch: PointF? = null

    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(RIPPLE_AGSL) }.getOrNull() else null

    init {
        clipChildren = true
        setBackgroundColor(Color.BLACK)
        addView(stage)
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hero, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        action.apply {
            visibility = GONE
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val h = dp(28f).toInt(); val v = dp(13f).toInt()
            setPadding(h, v, h, v)
            background = GradientDrawable().apply { cornerRadius = dp(28f); setColor(Palette.ACCENT) }
            elevation = dp(6f)
        }
        addView(action, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL or Gravity.TOP))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val o = (overscan * 2).toInt()
        stage.measure(MeasureSpec.makeMeasureSpec(measuredWidth + o, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredHeight + o, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val o = overscan.toInt()
        stage.layout(-o, -o, width + o, height + o)
        val p = origin()
        hero.origin.set(p)
        glow.centerX = p.x; glow.centerY = p.y
        stage.centerX = p.x + overscan; stage.centerY = p.y + overscan
        if (action.visibility == VISIBLE && action.animation == null) action.translationY = hero.actionTop() - action.top
    }

    /** Where the devices touch: the notch if there is one, otherwise just above the top-centre edge. */
    private fun origin(): PointF = notch ?: PointF(width / 2f, -0.05f * height)

    // ---------------------------------------------------------------- content

    fun configure(role: Role, notchPoint: PointF?, startRect: RectF?) {
        hero.role = role
        notch = notchPoint
        hero.startRect = startRect
        requestLayout()
    }

    /** The items, in transfer order. Only [Protocol.MAX_ITEMS] are animated; [extra] more show as "+N". */
    fun setEntries(entries: List<Pair<String, String>>, extra: Int = 0) {
        hero.setEntries(entries.ifEmpty { listOf("Ficheiro" to "") }, extra)
    }

    fun setEntryImage(index: Int, bitmap: Bitmap?) { if (bitmap != null) hero.setImage(index, bitmap) }
    fun markEntryDone(index: Int) = hero.markEntryDone(index)
    fun setFile(name: String, mime: String) = hero.setFirst(name, mime)
    fun setHeroImage(bitmap: Bitmap?) = setEntryImage(0, bitmap)
    fun setLinkHost(host: String) = hero.setHost(host)
    fun setDirectOpen(on: Boolean) { hero.directOpen = on; hero.invalidate() }
    fun setHeadline(title: String, subtitle: String) { hero.title = title; hero.subtitle = subtitle; hero.invalidate() }
    fun setProgress(value: Float) { hero.progress = value; hero.invalidate() }
    val stagger: Float get() = hero.stagger
    val entryCount: Int get() = hero.count

    fun showDone(subtitle: String, actionLabel: String?, onAction: (() -> Unit)?) {
        hero.subtitle = subtitle
        hero.markDone()
        if (actionLabel != null && onAction != null) {
            action.text = actionLabel
            action.setOnClickListener { onAction() }
            action.alpha = 0f
            action.visibility = VISIBLE
            val baseY = hero.actionTop() - action.top
            action.translationY = baseY + dp(14f)
            action.animate().alpha(1f).translationY(baseY).setDuration(520).setStartDelay(120)
                .setInterpolator(pt.johnny.bump.ui.theme.Motion.easeOutInterpolator()).start()
        }
    }

    fun showError(message: String) {
        hero.subtitle = message
        hero.markError()
        action.visibility = GONE
    }

    val isRunning get() = running

    // ---------------------------------------------------------------- timeline

    fun play() {
        timeline?.cancel()
        action.visibility = GONE
        val p = params
        running = true
        var released = false
        var cued = false
        alpha = 0f
        animate().alpha(1f).setDuration(220).setInterpolator(pt.johnny.bump.ui.theme.Motion.easeOutInterpolator()).start()
        hero.begin(p.chargeSeconds)
        val total = max(p.totalSeconds, p.chargeSeconds + (hero.count - 1) * hero.stagger + 1.6f)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            onReleaseCue?.invoke(); onChargeComplete?.invoke(); hero.release(); running = false; onFinished?.invoke(); return
        }
        timeline = ValueAnimator.ofFloat(0f, total).apply {
            duration = (total * 1000).toLong()
            interpolator = null
            addUpdateListener {
                val t = it.animatedValue as Float
                frame(t, p)
                if (!cued && t >= max(0f, p.chargeSeconds - releaseCueLead)) { cued = true; onReleaseCue?.invoke() }
                if (!released && t >= p.chargeSeconds) { released = true; hero.release(); onChargeComplete?.invoke() }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    running = false
                    applyRipple(0f, p)
                    glow.intensity = 0f; glow.invalidate()
                    if (!released) { released = true; hero.release() }
                    onFinished?.invoke()
                }
            })
            start()
        }
    }

    private fun frame(t: Float, p: RippleParams) {
        var radius = 0f
        var g: Float
        var scale: Float
        if (t < p.chargeSeconds) {
            val q = t / max(p.chargeSeconds, 0.001f)
            val eased = q * q * (3 - 2 * q)
            g = eased * (1 + 0.05f * sin(t * 16f) * q)
            scale = 0.35f + 0.65f * eased
        } else {
            val u = t - p.chargeSeconds
            radius = min(u * p.speed, p.maxRadius)
            val fade = max(0f, 1 - u / GLOW_RELEASE)
            g = fade * fade
            scale = 1 + 0.8f * min(1f, u / GLOW_RELEASE)
            // One small pulse of light per following item: "sending… sending…".
            for (i in 1 until hero.count) {
                val d = u - i * hero.stagger
                if (d in 0f..0.35f) {
                    val k = (1 - d / 0.35f)
                    val pulse = 0.55f * k * k
                    if (pulse > g) { g = pulse; scale = 0.55f + 0.25f * (1 - k) }
                }
            }
        }
        glow.intensity = g * p.glowIntensity
        glow.diameter = width * 1.1f * p.glowSize * scale
        glow.invalidate()
        applyRipple(radius, p)
        if (shader == null) { stage.fallbackRadius = radius; stage.params = p; stage.invalidate() }
    }

    private fun applyRipple(radius: Float, p: RippleParams) {
        val s = shader ?: return
        if (Build.VERSION.SDK_INT < 33) return
        if (radius <= 0f) { stage.setRenderEffect(null); return }
        val sw = max(stage.width.toFloat(), 1f); val sh = max(stage.height.toFloat(), 1f)
        val o = origin()
        s.setFloatUniform("size", sw, sh)
        s.setFloatUniform("center", (o.x + overscan) / sw, (o.y + overscan) / sh)
        s.setFloatUniform("time", radius)
        s.setFloatUniform("amplitude", p.amplitude)
        s.setFloatUniform("sharpness", p.sharpness)
        s.setFloatUniform("width", p.width)
        s.setFloatUniform("distortion", p.distortion)
        s.setFloatUniform("highlight", p.highlight)
        s.setFloatUniform("maxOffset", overscan)
        stage.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "layer"))
    }

    override fun onDetachedFromWindow() {
        timeline?.cancel()
        super.onDetachedFromWindow()
    }

    // ---------------------------------------------------------------- stage

    /** The "wallpaper" the ripple bends: deep aurora gradient with a fine dot field so refraction reads. */
    private class StageView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var fallbackRadius = 0f
        var params = RippleParams()
        var centerX = 0f
        var centerY = 0f
        private var dots: Bitmap? = null

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w < 1) return
            paint.shader = LinearGradient(0f, 0f, 0f, h, Color.rgb(10, 18, 44), Color.rgb(24, 12, 48), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            blob(c, w * .18f, h * .22f, w * .85f, Color.argb(210, 31, 107, 255))
            blob(c, w * .88f, h * .34f, w * .75f, Color.argb(190, 138, 124, 255))
            blob(c, w * .50f, h * .78f, w * .90f, Color.argb(150, 92, 225, 255))
            blob(c, w * .08f, h * .95f, w * .60f, Color.argb(130, 255, 111, 181))
            // Dot field (tiled bitmap): the ripple visibly bends it, like a wallpaper under water.
            val tile = dots ?: makeDots().also { dots = it }
            paint.shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
            if (fallbackRadius > 0f) drawFallbackRing(c, w, h)
        }

        private fun blob(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
            paint.shader = RadialGradient(x, y, r, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, r, paint)
        }

        private fun makeDots(): Bitmap {
            val s = (resources.displayMetrics.density * 22).toInt().coerceAtLeast(8)
            val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255) }
            Canvas(b).drawCircle(s / 2f, s / 2f, resources.displayMetrics.density * 1.3f, p)
            return b
        }

        /** Pre-Android-13: same wavefront, drawn as a bright ring instead of refracted. */
        private fun drawFallbackRing(c: Canvas, w: Float, h: Float) {
            val aspect = w / h
            val rPx = fallbackRadius * h * hypot(1f, 1f / aspect) / 1.2f
            val k = (1f - fallbackRadius / params.maxRadius).coerceIn(0f, 1f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = params.width * h * .5f
            paint.color = Color.argb((60 * k).toInt(), 255, 255, 255)
            c.drawCircle(centerX, centerY, rPx, paint)
            paint.strokeWidth = resources.displayMetrics.density * 2
            paint.color = Color.argb((200 * k).toInt(), 255, 255, 255)
            c.drawCircle(centerX, centerY, rPx, paint)
            paint.style = Paint.Style.FILL
        }
    }

    // ---------------------------------------------------------------- charge glow

    /** White core + iridescent rim, additive ("plus lighter"), centred just above the top edge. */
    private class GlowView(context: Context) : View(context) {
        var intensity = 0f
        var diameter = 0f
        var centerX = 0f
        var centerY = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            val layerPaint = Paint()
            if (Build.VERSION.SDK_INT >= 29) layerPaint.blendMode = BlendMode.PLUS
            setLayerType(LAYER_TYPE_HARDWARE, layerPaint)
        }

        override fun onDraw(c: Canvas) {
            val a = intensity.coerceIn(0f, 1f)
            if (a < 0.001f || diameter < 1f) return
            val cx = centerX
            val cy = centerY
            val d = diameter
            fun white(alpha: Float) = Color.argb((alpha * a * 255).toInt().coerceIn(0, 255), 255, 255, 255)

            // Outer halo
            paint.shader = RadialGradient(cx, cy, d * .72f, intArrayOf(white(.85f), white(.3f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, d * .725f, paint)
            // Core
            paint.shader = RadialGradient(cx, cy, d / 2f, intArrayOf(white(1f), white(1f), white(.5f), Color.TRANSPARENT), floatArrayOf(0f, .45f, .75f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, d / 2f, paint)
            // Iridescent rim: sweep gradient masked to a ring.
            val sweep = SweepGradient(cx, cy, intArrayOf(CYAN, PURPLE, PINK, BLUE, MINT, CYAN), null)
            val mask = RadialGradient(cx, cy, d / 2f,
                intArrayOf(Color.TRANSPARENT, Color.argb(204, 255, 255, 255), Color.TRANSPARENT), floatArrayOf(.40f, .68f, 1f), Shader.TileMode.CLAMP)
            paint.shader = ComposeShader(sweep, mask, PorterDuff.Mode.DST_IN)
            paint.alpha = (0.45f * a * 255).toInt()
            c.drawCircle(cx, cy, d / 2f, paint)
            paint.alpha = 255
            paint.shader = null
        }

        companion object {
            val CYAN = Color.rgb(50, 173, 230)
            val PURPLE = Color.rgb(175, 82, 222)
            val PINK = Color.rgb(255, 45, 85)
            val BLUE = Color.rgb(0, 122, 255)
            val MINT = Color.rgb(0, 199, 190)
        }
    }

    // ---------------------------------------------------------------- hero layer (the shared items)

    private class HeroLayer(context: Context) : View(context) {
        class Entry(var name: String, var mime: String, var image: Bitmap? = null, var host: String = "", var done: Boolean = false)

        var role = Role.RECEIVER
        val origin = PointF()
        var startRect: RectF? = null
        var directOpen = false
        var title = ""
        var subtitle = ""
        var progress = -1f
        var onOpenMoment: (() -> Unit)? = null

        private var entries = mutableListOf(Entry("Ficheiro", ""))
        private var extra = 0
        val count: Int get() = entries.size
        /** Same rule on both devices, so items vanish and appear at matching instants. */
        val stagger: Float get() = if (count <= 1) 0f else (1.6f / count).coerceIn(0.16f, 0.30f)

        private var chargeS = 1.2f
        private var beganAt = 0L
        private var releasedAt = 0L
        private var doneAt = 0L
        private var done = false
        private var error = false
        private var openFired = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val bold = Typeface.create("sans-serif", Typeface.BOLD)
        private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

        fun setEntries(list: List<Pair<String, String>>, more: Int) {
            val old = entries
            entries = list.take(pt.johnny.bump.link.Protocol.MAX_ITEMS).mapIndexed { i, (n, m) ->
                Entry(n, m, old.getOrNull(i)?.image, old.getOrNull(i)?.host.orEmpty())
            }.toMutableList()
            extra = more.coerceAtLeast(0)
            invalidate()
        }
        fun setImage(i: Int, b: Bitmap) { entries.getOrNull(i)?.image = b; invalidate() }
        fun setFirst(name: String, mime: String) { entries[0].name = name; entries[0].mime = mime; invalidate() }
        fun setHost(host: String) { entries[0].host = host; invalidate() }
        fun markEntryDone(i: Int) { entries.getOrNull(i)?.done = true; invalidate() }

        fun begin(charge: Float) {
            chargeS = charge; beganAt = System.nanoTime(); releasedAt = 0L; doneAt = 0L
            done = false; error = false; openFired = false; progress = -1f
            invalidate()
        }
        fun release() { if (releasedAt == 0L) releasedAt = System.nanoTime(); invalidate() }
        fun markDone() { done = true; doneAt = System.nanoTime(); invalidate() }
        fun markError() { error = true; release(); invalidate() }

        private fun secs(from: Long) = if (from == 0L) -1f else (System.nanoTime() - from) / 1e9f
        private fun kindOf(e: Entry) = Files.kind(e.name, e.mime)
        private fun picture(e: Entry) = e.image != null || kindOf(e) == Files.Kind.LINK || kindOf(e) == Files.Kind.TEXT
        private fun aspectOf(e: Entry): Float = e.image?.let { it.width.toFloat() / it.height }
            ?: if (kindOf(e) == Files.Kind.LINK) 1.55f else 1f

        // ---- geometry

        private fun restRect(e: Entry): RectF {
            val w = width.toFloat(); val h = height.toFloat()
            val a = aspectOf(e).coerceIn(0.56f, 1.8f)
            var rw = if (picture(e)) min(w * 0.74f, dp(440f)) else dp(132f)
            var rh = rw / a
            if (rh > h * 0.46f) { rh = h * 0.46f; rw = rh * a }
            val cy = h * 0.40f
            return RectF(w / 2 - rw / 2, cy - rh / 2, w / 2 + rw / 2, cy + rh / 2)
        }

        /** Fit to the whole window — the viewer uses the same rule, so the handover is invisible. */
        private fun fullRect(e: Entry): RectF {
            val w = width.toFloat(); val h = height.toFloat()
            if (!picture(e)) return RectF(0f, 0f, w, h)
            val a = aspectOf(e)
            var rw = w; var rh = w / a
            if (rh > h) { rh = h; rw = h * a }
            return RectF(w / 2 - rw / 2, h / 2 - rh / 2, w / 2 + rw / 2, h / 2 + rh / 2)
        }

        private fun gridCells(): List<RectF> {
            val total = count + if (extra > 0) 1 else 0
            val cols = when { total <= 1 -> 1; total <= 4 -> 2; total <= 9 -> 3; else -> 4 }
            val rows = (total + cols - 1) / cols
            val gap = dp(10f)
            val w = width.toFloat(); val h = height.toFloat()
            val cell = min((min(w - dp(48f), dp(620f)) - gap * (cols - 1)) / cols, (h * 0.58f - gap * (rows - 1)) / rows)
            val gw = cols * cell + (cols - 1) * gap
            val gh = rows * cell + (rows - 1) * gap
            val left = (w - gw) / 2; val top = h * 0.42f - gh / 2
            return (0 until total).map { i ->
                val r = i / cols; val c = i % cols
                // Centre a short last row.
                val inRow = if (r == rows - 1) total - r * cols else cols
                val rowLeft = left + (gw - (inRow * cell + (inRow - 1) * gap)) / 2
                RectF(rowLeft + c * (cell + gap), top + r * (cell + gap), rowLeft + c * (cell + gap) + cell, top + r * (cell + gap) + cell)
            }
        }

        private fun gridBottom(): Float = if (count == 1) restRect(entries[0]).bottom else gridCells().maxOf { it.bottom }
        fun actionTop(): Float = gridBottom() + dp(108f)

        override fun onDraw(c: Canvas) {
            if (width == 0 || beganAt == 0L) return
            val more = if (role == Role.SENDER) drawSender(c) else drawReceiver(c)
            if (more) postInvalidateOnAnimation()
        }

        // ---- sender: a small stack, pulled into the notch one item at a time

        private fun drawSender(c: Canvas): Boolean {
            val tb = secs(beganAt)
            val start = startRect ?: restRect(entries[0])
            val dot = dp(20f)
            var anyLeft = false
            for (i in count - 1 downTo 0) { // item 0 on top
                val arrive = chargeS + i * stagger            // vanishes into the notch now
                val dur = if (i == 0) chargeS * 0.7f else 0.6f
                val begin = arrive - dur
                if (tb >= arrive) continue
                anyLeft = true
                val k0 = ((tb - begin) / dur).coerceIn(0f, 1f)
                val k = k0 * k0 * k0 // things leaving accelerate
                val fan = if (count > 1) i.coerceAtMost(2) else 0
                val rot0 = floatArrayOf(0f, -6f, 6f)[fan]
                val dx0 = floatArrayOf(0f, -dp(10f), dp(10f))[fan]
                val swell = if (i == 0) 1f + 0.035f * sin(min((tb / chargeS) / 0.3f, 1f) * PI.toFloat()) else 1f
                val sx = start.centerX() + dx0; val sy = start.centerY()
                val cx = lerp(sx, origin.x, k); val cy = lerp(sy, origin.y, k)
                val stretch = 1f + 0.18f * sin(k * PI.toFloat())
                val rw = lerp(start.width() * swell, dot, k) / stretch
                val rh = lerp(start.height() * swell, dot, k) * stretch
                val rect = RectF(cx - rw / 2, cy - rh / 2, cx + rw / 2, cy + rh / 2)
                val a = 1f - smooth((k - 0.82f) / 0.18f)
                c.save()
                c.rotate(rot0 * (1 - k), cx, cy)
                drawItem(c, entries[i], rect, lerp(dp(24f), min(rw, rh) / 2, k), a, crop = count > 1)
                c.restore()
            }
            // Total count badge on the stack while it waits.
            if (count + extra > 1 && tb < chargeS * 0.3f) {
                val n = (count + extra).toString()
                paint.color = Palette.ACCENT
                c.drawCircle(start.right - dp(4f), start.top + dp(4f), dp(16f), paint)
                label(c, n, start.right - dp(4f), start.top + dp(9f), 14f, Color.WHITE, bold, dp(30f))
            }
            if (releasedAt == 0L) return true
            // After the last item has gone: a quiet status in the middle.
            val e = secs(releasedAt) - (count - 1) * stagger
            val a = pt.johnny.bump.ui.theme.Motion.easeOut((e - 0.5f) / 0.6f)
            if (a > 0f) {
                val w = width.toFloat(); val cy = height * 0.46f
                if (done || error) {
                    val k = pt.johnny.bump.ui.theme.Motion.springStep(secs(doneAt).coerceAtLeast(0f), 0.45f, 0.7f)
                    drawBadge(c, w / 2, cy - dp(64f), dp(34f) * k.coerceAtLeast(0f), a, if (error) Palette.DANGER else Palette.SUCCESS, !error, secs(doneAt))
                }
                label(c, title, w / 2, cy, 22f, white(a), bold, w - dp(48f))
                label(c, subtitle, w / 2, cy + dp(28f), 15f, white(.75f * a), regular, w - dp(48f))
                if (!done && !error) {
                    val bw = dp(180f); val y = cy + dp(54f)
                    paint.color = white(.22f * a); c.drawRoundRect(w / 2 - bw / 2, y, w / 2 + bw / 2, y + dp(4f), dp(2f), dp(2f), paint)
                    if (progress > 0f) { paint.color = white(a); c.drawRoundRect(w / 2 - bw / 2, y, w / 2 - bw / 2 + bw * progress.coerceIn(0f, 1f), y + dp(4f), dp(2f), dp(2f), paint) }
                }
            }
            return anyLeft || e < 1.2f || (done && secs(doneAt) < 0.8f) || !done
        }

        // ---- receiver: out of the top, one by one

        private fun drawReceiver(c: Canvas): Boolean {
            if (releasedAt == 0L) return false
            val e = secs(releasedAt)
            val dot = dp(20f)
            val tiny = RectF(origin.x - dot / 2, origin.y - dot / 2, origin.x + dot / 2, origin.y + dot / 2)
            val single = count == 1 && extra == 0
            val cells = if (single) emptyList() else gridCells()
            val landedAll = (count - 1) * stagger + 0.55f
            // Several items + direct open: after they land, the first grows into the gallery.
            val expandAt = landedAll + 0.35f
            val q = if (!single && directOpen && e > expandAt) pt.johnny.bump.ui.theme.Motion.springStep(e - expandAt, 0.5f, 0.92f) else 0f

            for (i in 0 until count) {
                val ei = e - i * stagger
                if (ei < 0f) continue
                val entry = entries[i]
                val target = when {
                    single && directOpen -> fullRect(entry)
                    single -> restRect(entry)
                    else -> cells[i]
                }
                val m = pt.johnny.bump.ui.theme.Motion.springStep(ei, 0.55f, if (single && directOpen) 0.86f else 0.74f)
                val endRadius = if (single && directOpen) 0f else if (single) dp(26f) else dp(16f)
                var rect = lerpRect(tiny, target, m)
                var radius = lerp(dot / 2, endRadius, m.coerceIn(0f, 1f))
                var a = pt.johnny.bump.ui.theme.Motion.easeOut(ei / 0.12f)
                if (q > 0f) {
                    if (i == 0) continue // drawn last, on top
                    a *= 1f - q.coerceIn(0f, 1f)
                }
                drawItem(c, entry, rect, radius, a, crop = !single)
                if (!single && entry.done && q == 0f) drawBadge(c, rect.right - dp(4f), rect.bottom - dp(4f), dp(12f), a, Palette.SUCCESS, true, 1f)
            }
            if (extra > 0 && !single) {
                val ei = e - count * stagger
                if (ei > 0f) {
                    val r = lerpRect(tiny, cells[count], pt.johnny.bump.ui.theme.Motion.springStep(ei, 0.55f, 0.74f))
                    paint.color = white(.16f * (1f - q)); c.drawRoundRect(r, dp(16f), dp(16f), paint)
                    label(c, "+$extra", r.centerX(), r.centerY() + dp(8f), 22f, white(1f - q), bold, r.width())
                }
            }
            if (q > 0f) {
                val r = lerpRect(cells[0], fullRect(entries[0]), q)
                drawItem(c, entries[0], r, lerp(dp(16f), 0f, q.coerceIn(0f, 1f)), 1f, crop = q < 0.6f)
                if (!openFired && e - expandAt > 0.42f) { openFired = true; post { onOpenMoment?.invoke() } }
            }
            if (single && directOpen && !openFired && e > 0.55f) { openFired = true; post { onOpenMoment?.invoke() } }

            // Progress / labels
            if (directOpen) {
                if (progress in 0f..0.999f) { paint.color = white(.85f); c.drawRect(0f, 0f, width * progress, dp(3f), paint) }
            } else {
                val labels = pt.johnny.bump.ui.theme.Motion.easeOut((e - landedAll + 0.25f) / 0.45f)
                if (labels > 0f) {
                    if (single) {
                        val rect = restRect(entries[0])
                        when {
                            error -> ring(c, rect, dp(26f), 1f, Palette.DANGER, labels)
                            done -> drawBadge(c, rect.right - dp(6f), rect.bottom - dp(6f), dp(19f), labels, Palette.SUCCESS, true, secs(doneAt))
                            progress >= 0f -> { ring(c, rect, dp(26f), 1f, Color.WHITE, .22f * labels); ring(c, rect, dp(26f), progress, Color.WHITE, labels) }
                        }
                    } else if (!done && progress in 0f..0.999f) {
                        val bw = dp(200f); val y = gridBottom() + dp(16f); val w = width.toFloat()
                        paint.color = white(.22f * labels); c.drawRoundRect(w / 2 - bw / 2, y, w / 2 + bw / 2, y + dp(4f), dp(2f), dp(2f), paint)
                        paint.color = white(labels); c.drawRoundRect(w / 2 - bw / 2, y, w / 2 - bw / 2 + bw * progress, y + dp(4f), dp(2f), dp(2f), paint)
                    }
                    val w = width.toFloat(); val maxW = w - dp(48f); val base = gridBottom()
                    label(c, title, w / 2, base + dp(48f), 21f, white(labels), bold, maxW)
                    label(c, if (single) entries[0].name else "${count + extra} itens", w / 2, base + dp(74f), 15f, white(.9f * labels), regular, maxW)
                    label(c, subtitle, w / 2, base + dp(97f), 13f, if (error) withAlpha(Palette.DANGER, labels) else white(.65f * labels), regular, maxW)
                }
            }
            return e < landedAll + 1.6f || (done && secs(doneAt) < 0.8f)
        }

        // ---- pieces

        private fun drawItem(c: Canvas, e: Entry, rect: RectF, radius: Float, a: Float, crop: Boolean) {
            if (a <= 0.003f || rect.width() < 1f) return
            val alpha = (a.coerceIn(0f, 1f) * 255).toInt()
            paint.setShadowLayer(dp(22f), 0f, dp(10f), Color.argb((alpha * .38f).toInt(), 0, 0, 0))
            paint.color = Color.argb(alpha, 250, 251, 255)
            c.drawRoundRect(rect, radius, radius, paint)
            paint.clearShadowLayer()
            val b = e.image
            val kind = kindOf(e)
            when {
                b != null -> {
                    val m = Matrix()
                    val s = if (crop) max(rect.width() / b.width, rect.height() / b.height) else max(rect.width() / b.width, rect.height() / b.height)
                    m.setScale(s, s)
                    m.postTranslate(rect.centerX() - b.width * s / 2, rect.centerY() - b.height * s / 2)
                    paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
                    paint.alpha = alpha
                    c.drawRoundRect(rect, radius, radius, paint)
                    paint.shader = null; paint.alpha = 255
                }
                kind == Files.Kind.LINK -> {
                    val host = e.host.ifBlank { "link" }
                    val k = rect.width() / dp(300f)
                    if (k > 0.25f) {
                        paint.color = withAlpha(Palette.ACCENT, a)
                        c.drawCircle(rect.centerX(), rect.centerY() - rect.height() * .14f, rect.height() * .16f, paint)
                        label(c, host.take(1).uppercase(), rect.centerX(), rect.centerY() - rect.height() * .14f + rect.height() * .06f, 26f * k, withAlpha(Color.WHITE, a), bold, rect.width())
                        label(c, host, rect.centerX(), rect.centerY() + rect.height() * .2f, 17f * k, withAlpha(Palette.INK, a), medium, rect.width() - dp(20f) * k)
                        label(c, e.name, rect.centerX(), rect.centerY() + rect.height() * .33f, 12f * k, withAlpha(Palette.MUTED, a), regular, rect.width() - dp(24f) * k)
                    }
                }
                else -> {
                    val tint = Palette.kindColor(kind)
                    paint.color = withAlpha(tint, .14f * a)
                    c.drawRoundRect(rect, radius, radius, paint)
                    val k = rect.width() / dp(132f)
                    if (k > 0.3f) label(c, kind.label, rect.centerX(), rect.centerY() + dp(8f) * k, 22f * k, withAlpha(tint, a), bold, rect.width())
                }
            }
        }

        private fun ring(c: Canvas, rect: RectF, radius: Float, fraction: Float, color: Int, a: Float) {
            if (fraction <= 0f) return
            val r = RectF(rect.left - dp(7f), rect.top - dp(7f), rect.right + dp(7f), rect.bottom + dp(7f))
            val rr = radius + dp(7f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3.5f); paint.strokeCap = Paint.Cap.ROUND
            paint.color = withAlpha(color, a)
            if (fraction >= 1f) c.drawRoundRect(r, rr, rr, paint)
            else {
                path.reset(); path.addRoundRect(r, rr, rr, Path.Direction.CW)
                val pm = android.graphics.PathMeasure(path, false)
                val seg = Path(); pm.getSegment(0f, pm.length * fraction, seg, true)
                c.drawPath(seg, paint)
            }
            paint.style = Paint.Style.FILL
        }

        private fun drawBadge(c: Canvas, cx: Float, cy: Float, r: Float, a: Float, color: Int, check: Boolean, since: Float) {
            if (r <= 0f) return
            paint.color = withAlpha(color, a)
            c.drawCircle(cx, cy, r, paint)
            val k = pt.johnny.bump.ui.theme.Motion.easeOut((since / 0.55f).coerceIn(0f, 1f))
            paint.style = Paint.Style.STROKE; paint.color = withAlpha(Color.WHITE, a)
            paint.strokeWidth = r * 0.17f; paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
            path.reset()
            val s = r / 17f
            if (check) {
                path.moveTo(cx - 7 * s, cy)
                val a1 = min(1f, k * 2); path.lineTo(cx - 7 * s + 5 * s * a1, cy + 5 * s * a1)
                if (k > .5f) { val a2 = (k - .5f) * 2; path.lineTo(cx - 2 * s + 10 * s * a2, cy + 5 * s - 11 * s * a2) }
            } else {
                path.moveTo(cx - 6 * s, cy - 6 * s); path.lineTo(cx + 6 * s, cy + 6 * s)
                path.moveTo(cx + 6 * s, cy - 6 * s); path.lineTo(cx - 6 * s, cy + 6 * s)
            }
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            if (since in 0f..0.6f) postInvalidateOnAnimation()
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        private fun lerpRect(a: RectF, b: RectF, t: Float) =
            RectF(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))
        private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
        private fun white(a: Float) = withAlpha(Color.WHITE, a)
        private fun withAlpha(color: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        private fun label(c: Canvas, value: String, x: Float, y: Float, sp: Float, color: Int, face: Typeface, maxW: Float) {
            if (value.isEmpty() || maxW <= 0f) return
            text.typeface = face
            text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
            text.color = color
            text.textAlign = Paint.Align.CENTER
            var s = value
            if (text.measureText(s) > maxW) {
                var end = s.length
                while (end > 1 && text.measureText(s.substring(0, end) + "…") > maxW) end--
                s = s.substring(0, end) + "…"
            }
            c.drawText(s, x, y, text)
        }
    }

    companion object {
        private const val GLOW_RELEASE = 0.35f

        /** AGSL port of the NameDrop ripple (Metal → AGSL). `time` is the ring radius in normalised units. */
        private const val RIPPLE_AGSL = """
uniform shader layer;
uniform float2 size;
uniform float2 center;
uniform float time;
uniform float amplitude;
uniform float sharpness;
uniform float width;
uniform float distortion;
uniform float highlight;
uniform float maxOffset;

half4 main(float2 position) {
    half4 color = layer.eval(position);
    if (time <= 0.0) { return color; }
    float2 uv = position / size;
    float aspect = size.x / size.y;
    float2 p = float2(uv.x * aspect, uv.y);
    float2 c = float2(center.x * aspect, center.y);
    float dist = distance(p, c);
    if (dist < time - width || dist > time + width) { return color; }
    float diff = dist - time;
    float falloff = 1.0 - pow(abs(diff * amplitude), sharpness);
    if (falloff <= 0.0 || dist < 0.0001) { return color; }
    float2 dir = normalize(p - c);
    float denom = max(time * dist, 0.001);
    float2 offsetUV = dir * (diff * falloff) / (denom * 10.0) * distortion;
    float2 offsetPx = float2(offsetUV.x / aspect, offsetUV.y) * size;
    offsetPx = clamp(offsetPx, float2(-maxOffset), float2(maxOffset));
    color = layer.eval(position + offsetPx);
    half glow = half(falloff / (denom * 40.0) * highlight);
    color.rgb += color.rgb * glow;
    return color;
}
"""
    }
}
