package pt.johnny.bump.motion

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Pure, Android-free logic behind "encostar os dispositivos".
 *
 * Neither the Galaxy Tab S7 FE (no NFC, no UWB) nor most tablets can sense a
 * physical touch by radio. What both CAN sense is the impact itself: when two
 * devices meet, each accelerometer records a sharp jolt at the same instant.
 * The devices are already connected and mutually authenticated, so each side
 * reports its jolt timestamps and the sender correlates them. Two jolts within
 * a few tens of milliseconds, while a file is armed, is a bump. A strong BLE
 * signal is used as an extra plausibility check, never as the trigger alone.
 */

/** Detects short, sharp impacts in raw accelerometer data (gravity removed by a high-pass). */
class JoltDetector(
    /** Minimum linear acceleration in m/s². Phones in hand ~2.5–4, a tablet on a table ~0.8–1.5. */
    var threshold: Float,
    /** Minimum change between consecutive samples (m/s²) — impacts are steep, hand motion is not. */
    var minJerk: Float = threshold * 0.55f,
    private val refractoryNanos: Long = 450_000_000L,
) {
    private val gravity = FloatArray(3)
    private var samples = 0
    private var lastMagnitude = 0f
    private var lastFiredAt = Long.MIN_VALUE / 2

    data class Jolt(val atNanos: Long, val strength: Float)

    fun reset() {
        samples = 0
        lastMagnitude = 0f
        lastFiredAt = Long.MIN_VALUE / 2
    }

    /** Feed one sample; returns a jolt when an impact is detected. */
    fun onSample(x: Float, y: Float, z: Float, atNanos: Long): Jolt? {
        val alpha = 0.86f
        if (samples == 0) {
            gravity[0] = x; gravity[1] = y; gravity[2] = z
        } else {
            gravity[0] = alpha * gravity[0] + (1 - alpha) * x
            gravity[1] = alpha * gravity[1] + (1 - alpha) * y
            gravity[2] = alpha * gravity[2] + (1 - alpha) * z
        }
        samples++
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val magnitude = sqrt(lx * lx + ly * ly + lz * lz)
        val jerk = abs(magnitude - lastMagnitude)
        lastMagnitude = magnitude
        if (samples < 12) return null // let the gravity estimate settle
        if (atNanos - lastFiredAt < refractoryNanos) return null
        if (magnitude >= threshold && jerk >= minJerk) {
            lastFiredAt = atNanos
            return Jolt(atNanos, magnitude)
        }
        return null
    }
}

/**
 * NTP-style offset estimate between our clock and the peer's.
 * offset = peerClock − localClock. The sample with the smallest round trip
 * is the most trustworthy, so we keep the best of the recent window.
 */
class ClockSync(private val window: Int = 12) {
    data class Sample(val offsetNanos: Long, val rttNanos: Long)
    private val samples = ArrayDeque<Sample>()

    fun add(t0: Long, t1Peer: Long, t2: Long) {
        val rtt = t2 - t0
        if (rtt <= 0 || rtt > 2_000_000_000L) return
        samples.addLast(Sample(t1Peer - (t0 + rtt / 2), rtt))
        while (samples.size > window) samples.removeFirst()
    }

    fun best(): Sample? = samples.minByOrNull { it.rttNanos }
    val count: Int get() = samples.size
    fun clear() = samples.clear()
}

/** Correlates local and peer jolts. Thread-confined: call from one thread only. */
class BumpMatcher(
    private val baseWindowNanos: Long = 110_000_000L,
    private val maxWindowNanos: Long = 320_000_000L,
    private val memoryNanos: Long = 2_500_000_000L,
) {
    private val local = ArrayDeque<JoltDetector.Jolt>()
    private val peer = ArrayDeque<JoltDetector.Jolt>() // already converted to the local clock
    private var fired = false

    fun reset() { local.clear(); peer.clear(); fired = false }

    /** Allowed difference: base tolerance plus half of the best round trip (uncertainty of the offset). */
    fun window(sync: ClockSync.Sample?): Long =
        if (sync == null) maxWindowNanos
        else (baseWindowNanos + sync.rttNanos / 2).coerceAtMost(maxWindowNanos)

    fun onLocal(j: JoltDetector.Jolt, sync: ClockSync.Sample?, now: Long): Boolean {
        local.addLast(j); prune(now); return check(sync)
    }

    fun onPeer(atPeerNanos: Long, strength: Float, sync: ClockSync.Sample?, now: Long): Boolean {
        val offset = sync?.offsetNanos ?: return false // never correlate without a clock estimate
        peer.addLast(JoltDetector.Jolt(atPeerNanos - offset, strength)); prune(now); return check(sync)
    }

    private fun prune(now: Long) {
        while (local.isNotEmpty() && now - local.first().atNanos > memoryNanos) local.removeFirst()
        while (peer.isNotEmpty() && now - peer.first().atNanos > memoryNanos) peer.removeFirst()
    }

    private fun check(sync: ClockSync.Sample?): Boolean {
        if (fired || sync == null) return false
        val w = window(sync)
        for (a in local) for (b in peer) {
            if (abs(a.atNanos - b.atNanos) <= w) { fired = true; return true }
        }
        return false
    }
}

/**
 * BLE RSSI plausibility: a rolling window of samples. RSSI is NOT distance —
 * it only tells us the peer is probably within arm's reach.
 */
class RssiGate(private val threshold: Int, private val windowNanos: Long = 4_000_000_000L) {
    private val samples = ArrayDeque<Pair<Long, Int>>()
    var seenAny = false
        private set

    fun add(rssi: Int, atNanos: Long) {
        if (rssi > 0 || rssi < -127) return
        seenAny = true
        samples.addLast(atNanos to rssi)
        while (samples.isNotEmpty() && atNanos - samples.first().first > windowNanos) samples.removeFirst()
        while (samples.size > 8) samples.removeFirst()
    }

    /** Two strong packets in the recent window. */
    fun isNear(now: Long): Boolean =
        samples.count { now - it.first <= windowNanos && it.second >= threshold } >= 2

    /** Without any BLE data we cannot judge; the jolt correlation alone decides. */
    fun allows(now: Long): Boolean = !seenAny || isNear(now) ||
        samples.any { now - it.first <= windowNanos && it.second >= threshold - 8 }
}
