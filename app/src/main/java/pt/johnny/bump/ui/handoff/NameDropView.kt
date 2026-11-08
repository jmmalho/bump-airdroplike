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
import android.view.animation.PathInterpolator
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
 * NameDrop-style handoff, shown full screen on BOTH devices at the moment of the bump.
 *
 *  1. Charge  – a white glow with an iridescent rim swells at the top edge (where the devices touch).
 *  2. Release – the glow bursts and a refractive ring travels down the screen, bending and
 *               brightening everything it crosses (AGSL RuntimeShader, Android 13+).
 *  3. Card    – the file card settles in the middle: progress, then done.
 *
 * The ripple math is a port of the shockwave used by Georgvm/iOS-NameDrop-Animation and
 * cyrilzakka/NameDrop (MIT), itself based on Shadertoy "llj3Dz".
 */
class NameDropView(context: Context) : FrameLayout(context) {

    var params: RippleParams = RippleParams.load(context)
    var onChargeComplete: (() -> Unit)? = null
    var onFinished: (() -> Unit)? = null

    private val overscan = dp(60f)
    private val stage = StageView(context)
    private val glow = GlowView(context)
    private val card = CardView(context)
    private val action = TextView(context)
    private var timeline: ValueAnimator? = null
    private var cardRevealAt = 0f
    private var running = false

    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= 33) runCatching { RuntimeShader(RIPPLE_AGSL) }.getOrNull() else null

    init {
        clipChildren = true
        setBackgroundColor(Color.BLACK)
        addView(stage)
        addView(glow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // The stage is larger than the screen so the distortion never samples past an edge.
        val o = overscan.toInt()
        stage.layout(-o, -o, width + o, height + o)
        action.translationY = card.actionTop() - action.top
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val o = (overscan * 2).toInt()
        stage.measure(MeasureSpec.makeMeasureSpec(measuredWidth + o, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(measuredHeight + o, MeasureSpec.EXACTLY))
    }

    // ---------------------------------------------------------------- content

    fun setFile(name: String, mime: String) { card.name = name; card.mime = mime; card.invalidate() }
    fun setHeadline(title: String, subtitle: String) { card.title = title; card.subtitle = subtitle; card.invalidate() }
    fun setThumbnail(bitmap: Bitmap?) { card.thumb = bitmap; card.invalidate() }
    fun setProgress(value: Float) { card.progress = value; card.invalidate() }

    fun showDone(subtitle: String, actionLabel: String?, onAction: (() -> Unit)?) {
        card.subtitle = subtitle
        card.state = CardView.State.DONE
        card.doneAt = System.nanoTime()
        card.invalidate()
        if (actionLabel != null && onAction != null) {
            action.text = actionLabel
            action.setOnClickListener { onAction() }
            action.alpha = 0f
            action.visibility = VISIBLE
            action.animate().alpha(1f).setDuration(260).setStartDelay(180).start()
        }
    }

    fun showError(message: String) {
        card.subtitle = message
        card.state = CardView.State.ERROR
        card.reveal = max(card.reveal, 1f)
        card.invalidate()
        action.visibility = GONE
    }

    val isRunning get() = running

    // ---------------------------------------------------------------- timeline

    /** Charge → release → ripple → card. [onChargeComplete] fires at the release instant. */
    fun play() {
        timeline?.cancel()
        card.state = CardView.State.PROGRESS
        card.reveal = 0f
        card.progress = -1f
        action.visibility = GONE
        val p = params
        cardRevealAt = p.chargeSeconds + p.rippleSeconds * 0.30f
        running = true
        var released = false
        if (!ValueAnimator.areAnimatorsEnabled()) {
            onChargeComplete?.invoke(); card.reveal = 1f; card.invalidate(); running = false; onFinished?.invoke(); return
        }
        timeline = ValueAnimator.ofFloat(0f, p.totalSeconds).apply {
            duration = (p.totalSeconds * 1000).toLong()
            interpolator = null
            addUpdateListener {
                val t = it.animatedValue as Float
                frame(t, p)
                if (!released && t >= p.chargeSeconds) { released = true; onChargeComplete?.invoke() }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    running = false
                    applyRipple(0f, p)
                    glow.intensity = 0f; glow.invalidate()
                    card.reveal = 1f; card.invalidate()
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
        }
        glow.intensity = g * p.glowIntensity
        glow.diameter = width * 1.1f * p.glowSize * scale
        glow.invalidate()
        applyRipple(radius, p)
        // Card rises in behind the wavefront.
        val r = ((t - cardRevealAt) / 0.55f).coerceIn(0f, 1f)
        if (r != card.reveal) { card.reveal = r; card.invalidate() }
        // Fallback for devices without RuntimeShader: draw the wavefront as light.
        if (shader == null) { stage.fallbackRadius = radius; stage.params = p; stage.invalidate() }
    }

    private fun applyRipple(radius: Float, p: RippleParams) {
        val s = shader ?: return
        if (Build.VERSION.SDK_INT < 33) return
        if (radius <= 0f) { stage.setRenderEffect(null); return }
        s.setFloatUniform("size", stage.width.toFloat(), stage.height.toFloat())
        // NameDrop centre: horizontally centred, just above the top edge (normalised to the stage).
        s.setFloatUniform("center", 0.5f, (overscan - 0.05f * height) / max(stage.height.toFloat(), 1f))
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
            c.drawCircle(w / 2, -0.05f * h, rPx, paint)
            paint.strokeWidth = resources.displayMetrics.density * 2
            paint.color = Color.argb((200 * k).toInt(), 255, 255, 255)
            c.drawCircle(w / 2, -0.05f * h, rPx, paint)
            paint.style = Paint.Style.FILL
        }
    }

    // ---------------------------------------------------------------- charge glow

    /** White core + iridescent rim, additive ("plus lighter"), centred just above the top edge. */
    private class GlowView(context: Context) : View(context) {
        var intensity = 0f
        var diameter = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            val layerPaint = Paint()
            if (Build.VERSION.SDK_INT >= 29) layerPaint.blendMode = BlendMode.PLUS
            setLayerType(LAYER_TYPE_HARDWARE, layerPaint)
        }

        override fun onDraw(c: Canvas) {
            val a = intensity.coerceIn(0f, 1f)
            if (a < 0.001f || diameter < 1f) return
            val cx = width / 2f
            val cy = -0.05f * height
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

    // ---------------------------------------------------------------- file card

    private class CardView(context: Context) : View(context) {
        enum class State { PROGRESS, DONE, ERROR }
        var state = State.PROGRESS
        var reveal = 0f
        var title = ""
        var subtitle = ""
        var name = "Ficheiro"
        var mime = ""
        var progress = -1f
        var thumb: Bitmap? = null
        var doneAt = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private val path = Path()
        private val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
        private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

        private fun cardRect(): RectF {
            val w = width.toFloat(); val h = height.toFloat()
            val cw = min(w - dp(48f), dp(360f))
            val ch = dp(292f)
            val top = h * .5f - ch * .58f
            return RectF(w / 2 - cw / 2, top, w / 2 + cw / 2, top + ch)
        }

        fun actionTop(): Float = cardRect().bottom + dp(20f)

        override fun onDraw(c: Canvas) {
            val r = ease.getInterpolation(reveal.coerceIn(0f, 1f))
            if (r <= 0.001f) return
            val box = cardRect()
            c.save()
            c.translate(0f, (1 - r) * dp(36f))
            c.scale(.92f + .08f * r, .92f + .08f * r, box.centerX(), box.centerY())
            val alpha = (r * 255).toInt()

            paint.setShadowLayer(dp(30f), 0f, dp(14f), Color.argb((alpha * .35f).toInt(), 0, 0, 0))
            paint.color = Color.argb((alpha * .96f).toInt(), 250, 251, 255)
            c.drawRoundRect(box, dp(34f), dp(34f), paint)
            paint.clearShadowLayer()

            // Thumbnail or kind tile
            val s = dp(96f)
            rect.set(box.centerX() - s / 2, box.top + dp(30f), box.centerX() + s / 2, box.top + dp(30f) + s)
            val kind = Files.kind(name, mime)
            val tint = Palette.kindColor(kind)
            val b = thumb
            if (b != null) {
                val m = Matrix()
                val scale = max(rect.width() / b.width, rect.height() / b.height)
                m.setScale(scale, scale)
                m.postTranslate(rect.centerX() - b.width * scale / 2, rect.centerY() - b.height * scale / 2)
                paint.shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
                paint.alpha = alpha
                c.drawRoundRect(rect, dp(26f), dp(26f), paint)
                paint.shader = null; paint.alpha = 255
            } else {
                paint.color = withAlpha(tint, .13f * r)
                c.drawRoundRect(rect, dp(26f), dp(26f), paint)
                label(c, kind.label, rect.centerX(), rect.centerY() + dp(8f), 22f, withAlpha(tint, r), medium, rect.width(), Paint.Align.CENTER)
            }

            // Progress ring / done check / error mark around the tile
            val ring = RectF(rect.left - dp(7f), rect.top - dp(7f), rect.right + dp(7f), rect.bottom + dp(7f))
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = dp(3.5f)
            when (state) {
                State.PROGRESS -> if (progress >= 0f) {
                    paint.color = withAlpha(Palette.ACCENT, .15f * r); c.drawRoundRect(ring, dp(32f), dp(32f), paint)
                    path.reset(); path.addRoundRect(ring, dp(32f), dp(32f), Path.Direction.CW)
                    val pm = android.graphics.PathMeasure(path, false)
                    val seg = Path(); pm.getSegment(0f, pm.length * progress.coerceIn(0f, 1f), seg, true)
                    paint.color = withAlpha(Palette.ACCENT, r); c.drawPath(seg, paint)
                }
                State.DONE -> {
                    val k = ((System.nanoTime() - doneAt) / 450_000_000f).coerceIn(0f, 1f)
                    paint.color = withAlpha(Palette.SUCCESS, r); c.drawRoundRect(ring, dp(32f), dp(32f), paint)
                    val cx = rect.right - dp(4f); val cy = rect.bottom - dp(4f)
                    paint.style = Paint.Style.FILL; c.drawCircle(cx, cy, dp(17f), paint)
                    paint.style = Paint.Style.STROKE; paint.color = Color.WHITE; paint.strokeWidth = dp(3f); paint.strokeJoin = Paint.Join.ROUND
                    path.reset(); path.moveTo(cx - dp(7f), cy)
                    val a1 = min(1f, k * 2); path.lineTo(cx - dp(7f) + dp(5f) * a1, cy + dp(5f) * a1)
                    if (k > .5f) { val a2 = (k - .5f) * 2; path.lineTo(cx - dp(2f) + dp(10f) * a2, cy + dp(5f) - dp(11f) * a2) }
                    c.drawPath(path, paint)
                    if (k < 1f) postInvalidateOnAnimation()
                }
                State.ERROR -> { paint.color = withAlpha(Palette.DANGER, r); c.drawRoundRect(ring, dp(32f), dp(32f), paint) }
            }
            paint.style = Paint.Style.FILL

            val maxW = box.width() - dp(40f)
            label(c, title, box.centerX(), rect.bottom + dp(44f), 19f, withAlpha(Palette.INK, r), medium, maxW, Paint.Align.CENTER)
            label(c, name, box.centerX(), rect.bottom + dp(70f), 14f, withAlpha(Palette.INK, .85f * r), regular, maxW, Paint.Align.CENTER)
            label(c, subtitle, box.centerX(), rect.bottom + dp(93f), 13f, withAlpha(if (state == State.ERROR) Palette.DANGER else Palette.MUTED, r), regular, maxW, Paint.Align.CENTER)
            c.restore()
        }

        private fun withAlpha(color: Int, a: Float) = Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

        private fun label(c: Canvas, value: String, x: Float, y: Float, sp: Float, color: Int, face: Typeface, maxW: Float, align: Paint.Align) {
            text.typeface = face
            text.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)
            text.color = color
            text.textAlign = align
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
