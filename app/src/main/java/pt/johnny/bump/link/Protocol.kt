package pt.johnny.bump.link

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Wire format for control messages sent as Nearby BYTES payloads.
 *
 * `BUMP4|TYPE|field|field…` — every free-text field is URL-safe Base64, so the
 * separator can never appear inside a value. Pure Kotlin: unit-tested on the JVM.
 */
sealed interface Msg {
    /** Sender → receiver, first pairing only, over the digit-verified encrypted channel. */
    data class Pair(val secretHex: String) : Msg
    data object PairAck : Msg
    data class Hello(val deviceId: String, val nonce: String) : Msg
    data class Auth(val proof: String) : Msg
    /** Receiver → sender: rotating, non-secret BLE token so the sender can measure RSSI. */
    data class Beacon(val token: String) : Msg
    /** One shared item as announced before the bump (for the receiver's animation). */
    data class Item(val name: String, val mime: String, val size: Long)

    /** Sender has items ready (share sheet open). Receiver starts listening for the bump. */
    data class Arm(val count: Int, val totalBytes: Long, val items: List<Item>) : Msg {
        val firstName: String get() = items.firstOrNull()?.name.orEmpty()
        val firstMime: String get() = items.firstOrNull()?.mime.orEmpty()
    }
    /** Receiver → sender: how many previews it has decoded and can show instantly. */
    data class PreviewAck(val count: Int) : Msg
    data object Disarm : Msg
    data class Ping(val t0: Long) : Msg
    data class Pong(val t0: Long, val t1: Long) : Msg
    /** A physical jolt measured by the peer, in the peer's elapsedRealtimeNanos clock. */
    data class Knock(val atNanos: Long, val strength: Float) : Msg
    /**
     * Sender committed to the transfer. Both screens start the handoff at [startAtNanos], expressed
     * in the RECEIVER's elapsedRealtimeNanos clock (0 = as soon as possible).
     */
    data class Go(val reason: String, val startAtNanos: Long = 0) : Msg
    data class Meta(val payloadId: Long, val name: String, val mime: String, val size: Long, val index: Int = 0) : Msg
    /** A web page shared from a browser: opens on the receiver. Only http(s). */
    data class Link(val url: String, val title: String, val browser: String = "") : Msg
    /** Plain text: lands on the receiver's clipboard. */
    data class Text(val text: String) : Msg
    data object Done : Msg
}

object Protocol {
    private const val MAGIC = "BUMP4"
    const val MAX_CONTROL_BYTES = 16 * 1024
    const val MAX_TEXT = 8000
    /** Items announced (and animated) individually; more than this still transfer, shown as "+N". */
    const val MAX_ITEMS = 12
    private val PKG = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+")
    /** Nearby BYTES payloads go up to ~1 MB; a 720 px JPEG preview stays well below this cap. */
    const val MAX_PREVIEW_BYTES = 400 * 1024
    private val PREVIEW_MAGIC = "BUMP4P|".toByteArray(StandardCharsets.US_ASCII)

    fun isWebUrl(s: String): Boolean {
        if (s.length > 4000) return false
        val lower = s.lowercase()
        return (lower.startsWith("https://") || lower.startsWith("http://")) && s.none { it.isWhitespace() }
    }

    /** First http(s) URL inside shared text (Chrome sends just the URL; other apps add prose around it). */
    fun findUrl(text: String?): String? =
        text?.let { Regex("https?://[^\\s<>\"]+", RegexOption.IGNORE_CASE).find(it)?.value?.trimEnd('.', ',', ')', ';', '!', '?') }
            ?.takeIf(::isWebUrl)

    /** Preview image (item [index]) sent silently before the bump so the receiver can show it instantly. */
    fun encodePreview(index: Int, jpeg: ByteArray): ByteArray? =
        if (jpeg.size > MAX_PREVIEW_BYTES || index !in 0 until MAX_ITEMS) null
        else PREVIEW_MAGIC + byteArrayOf(index.toByte()) + jpeg

    fun decodePreview(bytes: ByteArray): Pair<Int, ByteArray>? {
        val head = PREVIEW_MAGIC.size + 1
        if (bytes.size <= head || bytes.size > MAX_PREVIEW_BYTES + head) return null
        for (i in PREVIEW_MAGIC.indices) if (bytes[i] != PREVIEW_MAGIC[i]) return null
        val index = bytes[PREVIEW_MAGIC.size].toInt()
        if (index !in 0 until MAX_ITEMS) return null
        return index to bytes.copyOfRange(head, bytes.size)
    }

    private val b64e = Base64.getUrlEncoder().withoutPadding()
    private val b64d = Base64.getUrlDecoder()
    private fun enc(s: String) = b64e.encodeToString(s.toByteArray(StandardCharsets.UTF_8))
    private fun dec(s: String) = String(b64d.decode(s), StandardCharsets.UTF_8)

    private val HEX64 = Regex("[0-9a-f]{64}")
    private val HEX32 = Regex("[0-9a-f]{32}")
    private val HEX8 = Regex("[0-9a-f]{8}")
    private val ID = Regex("[0-9a-f]{12}")

    fun encode(m: Msg): ByteArray {
        val body = when (m) {
            is Msg.Pair -> "PAIR|${m.secretHex}"
            Msg.PairAck -> "PAIR_ACK"
            is Msg.Hello -> "HELLO|${m.deviceId}|${m.nonce}"
            is Msg.Auth -> "AUTH|${m.proof}"
            is Msg.Beacon -> "BEACON|${m.token}"
            is Msg.Arm -> "ARM|${m.count}|${m.totalBytes}" + m.items.take(MAX_ITEMS).joinToString("") { "|${enc(it.name)}|${enc(it.mime)}|${it.size}" }
            is Msg.PreviewAck -> "PACK|${m.count}"
            Msg.Disarm -> "DISARM"
            is Msg.Ping -> "PING|${m.t0}"
            is Msg.Pong -> "PONG|${m.t0}|${m.t1}"
            is Msg.Knock -> "KNOCK|${m.atNanos}|${m.strength}"
            is Msg.Go -> "GO|${enc(m.reason)}|${m.startAtNanos}"
            is Msg.Meta -> "META|${m.payloadId}|${enc(m.name)}|${enc(m.mime)}|${m.size}|${m.index}"
            is Msg.Link -> "LINK|${enc(m.url)}|${enc(m.title)}|${enc(m.browser)}"
            is Msg.Text -> "TEXT|${enc(m.text)}"
            Msg.Done -> "DONE"
        }
        return "$MAGIC|$body".toByteArray(StandardCharsets.UTF_8)
    }

    /** Returns null for anything malformed; the link simply ignores it. */
    fun decode(bytes: ByteArray): Msg? {
        if (bytes.size > MAX_CONTROL_BYTES + MAX_TEXT * 2) return null
        val p = String(bytes, StandardCharsets.UTF_8).split('|')
        if (p.size < 2 || p[0] != MAGIC) return null
        val a = p.drop(1)
        return try {
            when (a[0]) {
                "PAIR" -> if (a.size == 2 && HEX64.matches(a[1])) Msg.Pair(a[1]) else null
                "PAIR_ACK" -> Msg.PairAck
                "HELLO" -> if (a.size == 3 && ID.matches(a[1]) && HEX32.matches(a[2])) Msg.Hello(a[1], a[2]) else null
                "AUTH" -> if (a.size == 2 && HEX64.matches(a[1])) Msg.Auth(a[1]) else null
                "BEACON" -> if (a.size == 2 && HEX8.matches(a[1])) Msg.Beacon(a[1]) else null
                "ARM" -> if (a.size >= 3 && (a.size - 3) % 3 == 0 && (a.size - 3) / 3 <= MAX_ITEMS) {
                    val items = (3 until a.size step 3).map { i -> Msg.Item(dec(a[i]).take(200), dec(a[i + 1]).take(120), a[i + 2].toLong().coerceAtLeast(0)) }
                    Msg.Arm(a[1].toInt().coerceIn(1, 500), a[2].toLong().coerceAtLeast(0), items)
                } else null
                "PACK" -> if (a.size == 2) Msg.PreviewAck(a[1].toInt().coerceIn(0, MAX_ITEMS)) else null
                "DISARM" -> Msg.Disarm
                "PING" -> if (a.size == 2) Msg.Ping(a[1].toLong()) else null
                "PONG" -> if (a.size == 3) Msg.Pong(a[1].toLong(), a[2].toLong()) else null
                "KNOCK" -> if (a.size == 3) Msg.Knock(a[1].toLong(), a[2].toFloat()) else null
                "GO" -> if (a.size == 3) Msg.Go(dec(a[1]).take(40), a[2].toLong()) else null
                "META" -> if (a.size == 6) Msg.Meta(a[1].toLong(), dec(a[2]).take(240), dec(a[3]).take(120), a[4].toLong(), a[5].toInt().coerceIn(0, 499)) else null
                "LINK" -> if (a.size == 4) dec(a[1]).takeIf(::isWebUrl)?.let {
                    Msg.Link(it, dec(a[2]).take(200), dec(a[3]).take(120).takeIf { p -> PKG.matches(p) }.orEmpty())
                } else null
                "TEXT" -> if (a.size == 2) Msg.Text(dec(a[1]).take(MAX_TEXT)) else null
                "DONE" -> Msg.Done
                else -> null
            }
        } catch (_: IllegalArgumentException) {
            null // NumberFormatException and Base64 errors are both IllegalArgumentException
        }
    }
}

object Crypto {
    private val random = SecureRandom()

    fun randomHex(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return b.toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** HMAC-SHA256 over both nonces, bound to the author's id (prevents reflection). */
    fun proof(sharedKeyHex: String, authorId: String, authorNonce: String, otherNonce: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hexToBytes(sharedKeyHex), "HmacSHA256"))
        return mac.doFinal("BUMP4|$authorId|$authorNonce|$otherNonce".toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
}
