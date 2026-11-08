package pt.johnny.bump.ui.theme

import android.view.animation.PathInterpolator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Apple-style motion, in one place.
 *
 *  - Springs are specified like SwiftUI: `response` (seconds per oscillation) and `dampingFraction`
 *    (1 = no bounce). stiffness = (2π / response)², mass = 1. Springs carry velocity, so an
 *    interrupted or flung element continues with the finger's speed instead of restarting.
 *  - Fixed-time entrances use a long, soft deceleration: cubic-bezier(0.16, 1, 0.3, 1).
 *  - State changes use an ease-in-out that leaves fast and settles slowly: (0.32, 0.72, 0, 1).
 */
object Motion {
    val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val EaseInOut = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

    /** Same curves for classic Views / ValueAnimator. */
    fun easeOutInterpolator() = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    fun easeInOutInterpolator() = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    fun stiffness(response: Float): Float = ((2 * PI / response) * (2 * PI / response)).toFloat()

    /** SwiftUI `.spring(response:dampingFraction:)`. */
    fun <T> spring(response: Float = 0.5f, dampingFraction: Float = 0.86f): SpringSpec<T> =
        spring(dampingRatio = dampingFraction, stiffness = stiffness(response))

    /** `.snappy`: quick, the tiniest settle. Sheets, screens. */
    fun <T> snappy(): SpringSpec<T> = spring(0.42f, 0.86f)
    /** `.smooth`: no bounce. Opacity-like values, layout. */
    fun <T> smooth(): SpringSpec<T> = spring(0.5f, 1f)
    /** `.bouncy`: visible overshoot. Cards arriving, press release. */
    fun <T> bouncy(): SpringSpec<T> = spring(0.5f, 0.72f)

    /**
     * Closed-form step response of the same spring, for hand-driven timelines (Canvas views).
     * Returns position from 0 toward 1 at time [t] seconds; may overshoot 1 when damping < 1.
     */
    fun springStep(t: Float, response: Float = 0.5f, dampingFraction: Float = 0.75f): Float {
        if (t <= 0f) return 0f
        val w0 = 2 * PI / response
        val z = dampingFraction.toDouble()
        val x = if (z < 1.0) {
            val wd = w0 * sqrt(1 - z * z)
            1 - exp(-z * w0 * t) * (cos(wd * t) + (z * w0 / wd) * sin(wd * t))
        } else {
            1 - exp(-w0 * t) * (1 + w0 * t)
        }
        return x.toFloat()
    }

    /** Pure easeOut value (no Android types) for Canvas code. */
    fun easeOut(x: Float): Float {
        val p = x.coerceIn(0f, 1f)
        // Solve cubic-bezier(0.16, 1, 0.3, 1) numerically (x→t by Newton, then y(t)).
        var t = p
        repeat(6) {
            val cx = bez(t, 0.16f, 0.3f) - p
            val d = bezD(t, 0.16f, 0.3f)
            if (kotlin.math.abs(d) > 1e-5f) t -= cx / d
        }
        return bez(t.coerceIn(0f, 1f), 1f, 1f)
    }

    private fun bez(t: Float, p1: Float, p2: Float): Float {
        val u = 1 - t
        return 3 * u * u * t * p1 + 3 * u * t * t * p2 + t * t * t
    }

    private fun bezD(t: Float, p1: Float, p2: Float): Float {
        val u = 1 - t
        return 3 * u * u * p1 + 6 * u * t * (p2 - p1) + 3 * t * t * (1 - p2)
    }
}
