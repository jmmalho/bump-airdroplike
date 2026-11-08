package pt.johnny.bump.ui.handoff

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.PathInterpolator
import pt.johnny.bump.data.Files
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun View.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dp(v: Float): Float = v * resources.displayMetrics.density

/**
 * The shared "handoff" choreography, drawn identically on both devices.
 *
 *  READY   – a light seam glows along the top edge, where the other device will touch.
 *  SEND    – the seam flares and a refractive ring sweeps away from the contact point.
 *  RECEIVE – the same ring arrives from the top and the file drops into place.
 *  DONE    – the file settles, a check draws itself.
 *  ERROR   – the seam cools to red and the reason is shown.
 *
 * On the sender the Compose sheet draws the file card, so [showCapsule] is false
 * and this view paints only light. The receiver overlay shows the full capsule.
 */
class HandoffView(context: Context, private val sender: Boolean) : View(context) {
    enum class Mode { READY, SEND, RECEIVE, DONE, ERROR }

    var mode: Mode = Mode.READY
        private set
    var showCapsule: Boolean = !sender

    private var fileName = "Ficheiro"
    private var mime = ""
    private var peer = ""
    private var detail = ""
    private var progress = -1f

    private var t = 0f          // 0..1 progress of the current mode's choreography
    private var breath = 0f     // 0..1 idle loop
    private var appear = 0f     // 0..1 fade-in of the whole view
    private var motion: ValueAnimator? = null
    private var idle: ValueAnimator? = null
    private var fade: ValueAnimator? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun setFile(name: String, mimeType: String) { fileName = name.ifBlank { "Ficheiro" }; mime = mimeType; invalidate() }
    fun setPeer(name: String) { peer = name; invalidate() }
    fun setDetail(value: String) { detail = value; invalidate() }
    fun setProgress(value: Float) { progress = value; invalidate() }

    fun play(next: Mode) {
        motion?.cancel()
        mode = next
        if (next == Mode.RECEIVE || next == Mode.SEND) progress = -1f
        t = 0f
        if (!ValueAnimator.areAnimatorsEnabled()) { t = 1f; appear = 1f; invalidate(); return }
        val duration = when (next) { Mode.READY -> 700L; Mode.SEND, Mode.RECEIVE -> 1450L; Mode.DONE -> 900L; Mode.ERROR -> 600L }
        motion = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = null
            addUpdateListener { t = it.animatedValue as Float; invalidate() }
            start()
        }
        if (appear < 1f) {
            fade?.cancel()
            fade = ValueAnimator.ofFloat(appear, 1f).apply {
                this.duration = 320; interpolator = emphasized
                addUpdateListener { appear = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (idle == null && ValueAnimator.areAnimatorsEnabled()) {
            idle = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2600; repeatCount = ValueAnimator.INFINITE; interpolator = null
                addUpdateListener { breath = it.animatedValue as Float; if (mode == Mode.READY) invalidate() }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        idle?.cancel(); idle = null
        motion?.cancel(); fade?.cancel()
        super.onDetachedFromWindow()
    }

    // ---------- math ----------

    private fun clamp(x: Float) = max(0f, min(1f, x))
    private fun smooth(x: Float) = clamp(x).let { it * it * (3 - 2 * it) }
    private fun ease(x: Float) = emphasized.getInterpolation(clamp(x))
    /** Rises a→b, falls c→d. */
    private fun window(x: Float, a: Float, b: Float, c: Float, d: Float) = smooth((x - a) / (b - a)) * (1 - smooth((x - c) / (d - c)))
    private fun alpha(a: Float) = (clamp(a) * 255).toInt()
    private fun argb(a: Float, c: Int) = Color.argb(alpha(a * appear), Color.red(c), Color.green(c), Color.blue(c))

    // ---------- drawing ----------

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 1 || h < 1) return
        val cx = w / 2f
        val seamY = 0f
        val flowing = mode == Mode.SEND || mode == Mode.RECEIVE

        drawScrim(canvas, w, h)

        val seam = when (mode) {
            Mode.READY -> 0.55f + 0.25f * sin(2 * PI * breath).toFloat()
            Mode.SEND, Mode.RECEIVE -> 0.6f + 0.4f * window(t, 0f, .12f, .55f, .95f)
            Mode.DONE -> 0.6f * (1 - smooth(t))
            Mode.ERROR -> 0.5f
        }
        drawSeam(canvas, w, cx, seamY, seam, if (mode == Mode.ERROR) ERROR_RED else ACCENT)
        if (flowing) drawRings(canvas, w, h, cx, seamY)
        if (mode == Mode.DONE) drawDonePulse(canvas, w, h, cx)
        if (showCapsule) drawCapsule(canvas, w, h, cx)
    }

    private fun drawScrim(c: Canvas, w: Float, h: Float) {
        val strength = when (mode) { Mode.READY -> .55f; Mode.SEND, Mode.RECEIVE -> .8f; Mode.DONE -> .8f * (1 - smooth((t - .6f) / .4f)) + .3f; Mode.ERROR -> .6f }
        paint.shader = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(argb(.42f * strength, INK), argb(.22f * strength, INK), argb(.34f * strength, INK)),
            floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }

    /** The light seam: where the other device touches. Elliptical, hugging the top edge. */
    private fun drawSeam(c: Canvas, w: Float, cx: Float, y: Float, k: Float, tint: Int) {
        val rx = max(dp(220f), w * .62f)
        c.save()
        c.scale(1f, .42f, cx, y)
        paint.shader = RadialGradient(cx, y, rx,
            intArrayOf(argb(.95f * k, Color.WHITE), argb(.75f * k, CYAN), argb(.45f * k, tint), argb(.12f * k, VIOLET), Color.TRANSPARENT),
            floatArrayOf(0f, .08f, .28f, .6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, y, rx, paint)
        c.restore()
        // A crisp bright line exactly on the edge.
        paint.shader = LinearGradient(cx - rx * .7f, 0f, cx + rx * .7f, 0f,
            intArrayOf(Color.TRANSPARENT, argb(.9f * k, Color.WHITE), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRect(cx - rx * .7f, 0f, cx + rx * .7f, dp(2.2f), paint)
        paint.shader = null
    }

    /** Refractive ring: bright core with slightly offset cyan/violet fringes (chromatic dispersion). */
    private fun drawRings(c: Canvas, w: Float, h: Float, cx: Float, y: Float) {
        val diag = hypot(w / 2f, h)
        val strength = window(t, .02f, .14f, .7f, .98f)
        paint.style = Paint.Style.STROKE
        for (i in 0 until 3) {
            val local = clamp((t - .06f - i * .07f) / .8f)
            if (local <= 0f) continue
            val r = dp(12f) + ease(local) * diag * 1.05f
            val k = strength * (1f - i * .28f) * (1 - smooth((local - .7f) / .3f))
            paint.strokeWidth = dp(if (i == 0) 26f else 14f)
            paint.color = argb(.10f * k, CYAN); c.drawCircle(cx, y, r + dp(5f), paint)
            paint.color = argb(.10f * k, VIOLET); c.drawCircle(cx, y, r - dp(5f), paint)
            paint.strokeWidth = dp(if (i == 0) 2.2f else 1.2f)
            paint.color = argb(.85f * k, Color.WHITE); c.drawCircle(cx, y, r, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun drawDonePulse(c: Canvas, w: Float, h: Float, cx: Float) {
        val cy = capsuleTop(h) + dp(48f)
        val r = dp(30f) + dp(140f) * ease(t)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        paint.color = argb(.55f * (1 - smooth(t)), SUCCESS)
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL
    }

    private fun capsuleTop(h: Float) = max(dp(120f), h * .16f)

    private fun drawCapsule(c: Canvas, w: Float, h: Float, cx: Float) {
        val cw = min(w - dp(32f), dp(420f))
        val ch = dp(96f)
        val top = capsuleTop(h)
        val visible = when (mode) {
            Mode.READY -> ease(t)
            Mode.RECEIVE -> 1f
            else -> 1f
        }
        if (visible <= .01f) return
        val dy = dp(14f) * (1 - visible)
        rect.set(cx - cw / 2, top + dy, cx + cw / 2, top + ch + dy)
        val radius = dp(28f)

        paint.setShadowLayer(dp(24f), 0f, dp(10f), argb(.28f * visible, INK))
        paint.color = argb(.97f * visible, SURFACE)
        c.drawRoundRect(rect, radius, radius, paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(1f)
        paint.color = argb(.9f * visible, Color.WHITE)
        c.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL

        // File token: on RECEIVE it falls from the seam into the capsule.
        val iconCx = rect.left + dp(50f)
        val iconCy = rect.centerY()
        var tx = iconCx; var ty = iconCy; var scale = 1f; var tokenAlpha = visible
        if (mode == Mode.RECEIVE) {
            val drop = ease((t - .18f) / .62f)
            ty = -dp(40f) + (iconCy + dp(40f)) * drop
            tx = cx + (iconCx - cx) * drop
            scale = .55f + .45f * drop
            tokenAlpha = smooth((t - .15f) / .2f)
        }
        drawToken(c, tx, ty, scale, tokenAlpha)

        val textX = rect.left + dp(92f)
        val maxW = rect.right - dp(20f) - textX
        val title = when (mode) {
            Mode.READY -> if (peer.isNotBlank()) "$peer quer partilhar" else "Pronto a receber"
            Mode.SEND -> "A enviar"
            Mode.RECEIVE -> if (peer.isNotBlank()) "A receber de $peer" else "A receber"
            Mode.DONE -> "Recebido"
            Mode.ERROR -> "Não foi possível receber"
        }
        val sub = when (mode) {
            Mode.READY -> "$fileName · encosta para receber"
            Mode.ERROR -> detail.ifBlank { "Tenta outra vez" }
            Mode.DONE -> if (detail.isNotBlank()) "$fileName · $detail" else fileName
            else -> if (detail.isNotBlank()) "$fileName · $detail" else fileName
        }
        label(c, title, textX, rect.top + dp(41f), 17f, argb(visible, INK), medium, maxW)
        label(c, sub, textX, rect.top + dp(66f), 13f, argb(visible, MUTED), regular, maxW)
    }

    private fun drawToken(c: Canvas, x: Float, y: Float, scale: Float, a: Float) {
        if (a <= .01f) return
        val kind = Files.kind(fileName, mime)
        val tint = kindColor(kind)
        val s = dp(48f) * scale
        rect.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
        val r = dp(14f) * scale
        val save = RectF(rect)
        paint.color = argb(.14f * a, tint)
        c.drawRoundRect(save, r, r, paint)
        // Progress ring around the token while bytes arrive.
        if (progress in 0f..1f && mode == Mode.RECEIVE) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3f); paint.strokeCap = Paint.Cap.ROUND
            paint.color = argb(.18f * a, tint)
            val ring = RectF(save.left - dp(5f), save.top - dp(5f), save.right + dp(5f), save.bottom + dp(5f))
            c.drawRoundRect(ring, r + dp(5f), r + dp(5f), paint)
            paint.color = argb(a, tint)
            c.drawArc(ring, -90f, 360f * progress, false, paint)
            paint.style = Paint.Style.FILL
        }
        if (mode == Mode.DONE) {
            val draw = smooth(t / .55f)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(3.4f) * scale; paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = argb(a, SUCCESS)
            path.reset()
            val ox = save.centerX(); val oy = save.centerY()
            val p1x = ox - dp(10f); val p1y = oy + dp(1f)
            val p2x = ox - dp(3f); val p2y = oy + dp(8f)
            val p3x = ox + dp(11f); val p3y = oy - dp(8f)
            path.moveTo(p1x, p1y)
            val first = min(1f, draw * 2f)
            path.lineTo(p1x + (p2x - p1x) * first, p1y + (p2y - p1y) * first)
            if (draw > .5f) { val s2 = (draw - .5f) * 2f; path.lineTo(p2x + (p3x - p2x) * s2, p2y + (p3y - p2y) * s2) }
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
        } else {
            label(c, kind.label, save.centerX(), save.centerY() + dp(4.5f) * scale, 11f * scale, argb(a, tint), medium, save.width() - dp(6f), Paint.Align.CENTER)
        }
    }

    private fun label(c: Canvas, value: String, x: Float, y: Float, sizeSp: Float, color: Int, face: Typeface, maxW: Float, align: Paint.Align = Paint.Align.LEFT) {
        text.typeface = face
        text.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
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

    companion object {
        val INK = Color.rgb(13, 22, 41)
        val MUTED = Color.rgb(96, 108, 130)
        val SURFACE = Color.rgb(250, 251, 255)
        val ACCENT = Color.rgb(31, 107, 255)
        val CYAN = Color.rgb(92, 225, 255)
        val VIOLET = Color.rgb(138, 124, 255)
        val SUCCESS = Color.rgb(22, 163, 106)
        val ERROR_RED = Color.rgb(239, 68, 68)

        fun kindColor(k: Files.Kind) = when (k) {
            Files.Kind.PDF -> Color.rgb(229, 57, 53)
            Files.Kind.IMAGE -> Color.rgb(124, 77, 255)
            Files.Kind.VIDEO -> Color.rgb(245, 124, 0)
            Files.Kind.AUDIO -> Color.rgb(0, 150, 136)
            Files.Kind.APK -> Color.rgb(46, 160, 67)
            Files.Kind.DOC -> ACCENT
        }
    }
}
