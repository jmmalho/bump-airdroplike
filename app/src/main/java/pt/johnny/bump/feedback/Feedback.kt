package pt.johnny.bump.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import pt.johnny.bump.R
import pt.johnny.bump.data.AppSettings

enum class Cue { READY, SEND, INCOMING, SENT, RECEIVED, ERROR, BUMP }

/**
 * Low-latency sound + haptics. SoundPool keeps the six cues decoded in memory,
 * so the "toque" sound lands on the same frame as the impact (MediaPlayer took
 * 80–200 ms to prepare each time). Haptics use rich primitives where available.
 */
class Feedback(private val context: Context, private val settings: AppSettings) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = mapOf(
        Cue.READY to pool.load(context, R.raw.bump_ready, 1),
        Cue.SEND to pool.load(context, R.raw.bump_send, 1),
        Cue.BUMP to pool.load(context, R.raw.bump_send, 1),
        Cue.INCOMING to pool.load(context, R.raw.bump_incoming, 1),
        Cue.SENT to pool.load(context, R.raw.bump_sent, 1),
        Cue.RECEIVED to pool.load(context, R.raw.bump_received, 1),
        Cue.ERROR to pool.load(context, R.raw.bump_error, 1),
    )

    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun play(cue: Cue) {
        val s = settings.current
        if (s.sounds) {
            val audio = context.getSystemService(AudioManager::class.java)
            // Respect silent/vibrate mode even if media volume is up.
            if (audio == null || audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                val volume = if (cue == Cue.RECEIVED) .7f else .55f
                ids[cue]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
            }
        }
        if (s.haptics) vibrate(cue)
    }

    /**
     * NameDrop-style haptics: a hum that ramps up over the charge, a sharp tick at the
     * release, then a short decaying tail. Uses amplitude control where available.
     */
    fun playCharge(chargeSeconds: Float) {
        if (!settings.current.haptics) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            val chargeMs = (chargeSeconds * 1000).toLong().coerceAtLeast(50)
            if (v.hasAmplitudeControl()) {
                val steps = 12
                val timings = ArrayList<Long>(); val amps = ArrayList<Int>()
                for (i in 0 until steps) {
                    val q = (i + 1f) / steps
                    timings += chargeMs / steps
                    amps += (20 + 200 * q * q).toInt().coerceIn(1, 255)
                }
                timings += 20; amps += 0          // gap before the tick
                timings += 28; amps += 255        // tick
                timings += 90; amps += 110        // tail
                timings += 110; amps += 55
                timings += 100; amps += 20
                v.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), amps.toIntArray(), -1))
            } else {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 15, chargeMs - 15, 30, 60, 20), -1))
            }
        } catch (_: Exception) { }
    }

    private fun vibrate(cue: Cue) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= 31 && richSupported(v)) {
                val c = VibrationEffect.startComposition()
                when (cue) {
                    Cue.READY -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, .5f)
                    Cue.BUMP, Cue.SEND -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                    Cue.INCOMING -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, .6f)
                    Cue.SENT, Cue.RECEIVED -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, .7f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1f, 70)
                    Cue.ERROR -> c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, .8f)
                }
                v.vibrate(c.compose())
                return
            }
            val pattern = when (cue) {
                Cue.READY -> longArrayOf(0, 12)
                Cue.BUMP, Cue.SEND -> longArrayOf(0, 22)
                Cue.INCOMING -> longArrayOf(0, 14)
                Cue.SENT, Cue.RECEIVED -> longArrayOf(0, 15, 70, 20)
                Cue.ERROR -> longArrayOf(0, 30, 60, 30)
            }
            v.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (_: Exception) { }
    }

    private fun richSupported(v: Vibrator): Boolean = Build.VERSION.SDK_INT >= 31 && v.areAllPrimitivesSupported(
        VibrationEffect.Composition.PRIMITIVE_CLICK,
        VibrationEffect.Composition.PRIMITIVE_TICK,
        VibrationEffect.Composition.PRIMITIVE_THUD,
        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
    )
}
