package pt.johnny.bump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.johnny.bump.link.Crypto
import pt.johnny.bump.link.Msg
import pt.johnny.bump.link.Protocol
import pt.johnny.bump.motion.BumpMatcher
import pt.johnny.bump.motion.ClockSync
import pt.johnny.bump.motion.JoltDetector
import pt.johnny.bump.motion.RssiGate

class BumpLogicTest {
    private val ms = 1_000_000L

    /** 100 Hz still device, then a single-sample impact. */
    private fun feed(d: JoltDetector, impactAt: Int, impact: Float, startNanos: Long = 0): JoltDetector.Jolt? {
        var hit: JoltDetector.Jolt? = null
        for (i in 0 until 60) {
            val z = 9.81f + if (i == impactAt) impact else 0f
            d.onSample(0.02f, -0.03f, z, startNanos + i * 10 * ms)?.let { hit = it }
        }
        return hit
    }

    @Test fun joltDetectedOnImpact() {
        val hit = feed(JoltDetector(threshold = 2.5f), impactAt = 40, impact = 6f)
        assertNotNull(hit)
        assertEquals(400 * ms, hit!!.atNanos)
    }

    @Test fun noJoltWhenStillOrWeak() {
        assertNull(feed(JoltDetector(threshold = 2.5f), impactAt = 40, impact = 1.2f))
        assertNull(feed(JoltDetector(threshold = 2.5f), impactAt = -1, impact = 0f))
    }

    @Test fun slowHandMotionIsNotAJolt() {
        val d = JoltDetector(threshold = 2.5f)
        var hit: JoltDetector.Jolt? = null
        // Smooth ramp up to +4 m/s² over 1 s: large but not steep.
        for (i in 0 until 120) {
            val ramp = (i.coerceAtMost(100) / 100f) * 4f
            d.onSample(0f, 0f, 9.81f + ramp, i * 10 * ms)?.let { hit = it }
        }
        assertNull(hit)
    }

    @Test fun refractoryPeriodSuppressesRinging() {
        val d = JoltDetector(threshold = 2.5f)
        var count = 0
        for (i in 0 until 80) {
            val z = 9.81f + if (i == 30 || i == 33) 7f else 0f
            if (d.onSample(0f, 0f, z, i * 10 * ms) != null) count++
        }
        assertEquals(1, count)
    }

    @Test fun clockSyncPrefersShortestRoundTrip() {
        val c = ClockSync()
        // Peer clock is 5 s ahead. Slow asymmetric sample, then a fast one.
        c.add(t0 = 0, t1Peer = 5_000 * ms + 180 * ms, t2 = 200 * ms)
        c.add(t0 = 1_000 * ms, t1Peer = 5_000 * ms + 1_000 * ms + 6 * ms, t2 = 1_012 * ms)
        val best = c.best()!!
        assertEquals(12 * ms, best.rttNanos)
        assertEquals(5_000 * ms, best.offsetNanos)
    }

    @Test fun matcherFiresOnSimultaneousJolts() {
        val sync = ClockSync.Sample(offsetNanos = 5_000 * ms, rttNanos = 20 * ms)
        val m = BumpMatcher()
        assertFalse(m.onLocal(JoltDetector.Jolt(10_000 * ms, 4f), sync, 10_000 * ms))
        // Peer reported its jolt 40 ms later in its own clock domain.
        assertTrue(m.onPeer(15_040 * ms, 1.2f, sync, 10_100 * ms))
        // Fires only once.
        assertFalse(m.onPeer(15_041 * ms, 1.2f, sync, 10_100 * ms))
    }

    @Test fun matcherRejectsUnrelatedJolts() {
        val sync = ClockSync.Sample(offsetNanos = 0, rttNanos = 20 * ms)
        val m = BumpMatcher()
        m.onLocal(JoltDetector.Jolt(1_000 * ms, 4f), sync, 1_000 * ms)
        assertFalse(m.onPeer(1_400 * ms, 1.2f, sync, 1_400 * ms))
        assertFalse(m.onPeer(1_000 * ms, 1.2f, null, 1_400 * ms))
    }

    @Test fun windowGrowsWithUncertaintyButIsCapped() {
        val m = BumpMatcher()
        assertEquals(120 * ms, m.window(ClockSync.Sample(0, 20 * ms)))
        assertEquals(320 * ms, m.window(ClockSync.Sample(0, 2_000 * ms)))
    }

    @Test fun rssiGate() {
        val g = RssiGate(threshold = -60)
        assertTrue(g.allows(0)) // no data → does not block
        g.add(-85, 0); g.add(-88, 10 * ms)
        assertFalse(g.allows(20 * ms))
        g.add(-55, 30 * ms); g.add(-57, 40 * ms)
        assertTrue(g.isNear(50 * ms))
    }

    @Test fun protocolRoundTrip() {
        val msgs = listOf(
            Msg.Pair("a".repeat(64)), Msg.PairAck, Msg.Hello("0123456789ab", "f".repeat(32)),
            Msg.Auth("0".repeat(64)), Msg.Beacon("deadbeef"),
            Msg.Arm(2, 12345, listOf(Msg.Item("relatório | final.pdf", "application/pdf", 12000), Msg.Item("foto.jpg", "image/jpeg", 345))),
            Msg.Arm(1, 0, listOf(Msg.Item("example.com", "text/uri-list", 0))), Msg.PreviewAck(3),
            Msg.Disarm, Msg.Ping(42), Msg.Pong(42, 99), Msg.Knock(123456789, 3.5f),
            Msg.Go("bump", 123_456_789_000L), Msg.Meta(77, "ção|x.png", "image/png", 9, 4), Msg.Done,
            Msg.Link("https://pt.wikipedia.org/wiki/Lisboa?a=1|2", "Lisboa – Wikipédia", "com.android.chrome"), Msg.Text("olá\nmundo | 2"),
        )
        for (m in msgs) assertEquals(m, Protocol.decode(Protocol.encode(m)))
    }

    @Test fun protocolRejectsGarbage() {
        assertNull(Protocol.decode("hello".toByteArray()))
        assertNull(Protocol.decode("BUMP4|PAIR|xyz".toByteArray()))
        assertNull(Protocol.decode("BUMP4|PING|notanumber".toByteArray()))
        assertNull(Protocol.decode("BUMP4|META|1|%%%|x|2".toByteArray()))
    }

    @Test fun linksAreValidatedAndExtracted() {
        assertNull(Protocol.decode(Protocol.encode(Msg.Link("javascript:alert(1)", "x"))))
        assertNull(Protocol.decode(Protocol.encode(Msg.Link("file:///sdcard/a", "x"))))
        assertEquals("https://example.com/a?b=1", Protocol.findUrl("Vê isto: https://example.com/a?b=1."))
        assertEquals("https://chrome.example/page", Protocol.findUrl("https://chrome.example/page"))
        assertNull(Protocol.findUrl("sem link aqui"))
        // A forged browser package is dropped, the link survives.
        val l = Protocol.decode(Protocol.encode(Msg.Link("https://a.pt", "t", "bad pkg|x"))) as Msg.Link
        assertEquals("", l.browser)
    }

    @Test fun previewRoundTrip() {
        val jpeg = ByteArray(5000) { (it % 251).toByte() }
        val wire = Protocol.encodePreview(7, jpeg)!!
        val (index, body) = Protocol.decodePreview(wire)!!
        assertEquals(7, index)
        assertTrue(jpeg.contentEquals(body))
        assertNull(Protocol.decode(wire)) // never mistaken for a control message
        assertNull(Protocol.decodePreview(Protocol.encode(Msg.Done)))
        assertNull(Protocol.encodePreview(0, ByteArray(Protocol.MAX_PREVIEW_BYTES + 1)))
        assertNull(Protocol.encodePreview(Protocol.MAX_ITEMS, jpeg))
    }

    @Test fun proofIsBoundToAuthor() {
        val key = Crypto.randomHex(32)
        val a = Crypto.proof(key, "aaaaaaaaaaaa", "1", "2")
        val b = Crypto.proof(key, "bbbbbbbbbbbb", "1", "2")
        assertFalse(Crypto.constantTimeEquals(a, b))
        assertTrue(Crypto.constantTimeEquals(a, Crypto.proof(key, "aaaaaaaaaaaa", "1", "2")))
    }
}
