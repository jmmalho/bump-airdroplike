package pt.johnny.bump.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Pairing secrets never sit in plain text: they are wrapped by a non-exportable Android Keystore key. */
object SecureBox {
    private const val ALIAS = "bump.pairing.v4"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (_: Exception) { null }
}

/** User-facing settings, observable from Compose. */
class AppSettings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("bump_settings", Context.MODE_PRIVATE)

    data class Values(
        val receiving: Boolean,
        val sounds: Boolean,
        val haptics: Boolean,
        val rssiThreshold: Int,
        /** 0 = firme, 1 = normal, 2 = leve. */
        val bumpSensitivity: Int,
        val onboarded: Boolean,
        val displayName: String,
    )

    private val _values = MutableStateFlow(read())
    val values: StateFlow<Values> = _values.asStateFlow()
    val current: Values get() = _values.value

    private fun read() = Values(
        receiving = p.getBoolean("receiving", false),
        sounds = p.getBoolean("sounds", true),
        haptics = p.getBoolean("haptics", true),
        rssiThreshold = p.getInt("rssi", -66),
        bumpSensitivity = p.getInt("bump", 1),
        onboarded = p.getBoolean("onboarded", false),
        displayName = p.getString("name", "") ?: "",
    )

    fun update(edit: SharedPreferences.Editor.() -> Unit) {
        p.edit().apply(edit).apply()
        _values.value = read()
    }

    fun setReceiving(v: Boolean) = update { putBoolean("receiving", v) }
    fun setSounds(v: Boolean) = update { putBoolean("sounds", v) }
    fun setHaptics(v: Boolean) = update { putBoolean("haptics", v) }
    fun setRssi(v: Int) = update { putInt("rssi", v.coerceIn(-80, -45)) }
    fun setBump(v: Int) = update { putInt("bump", v.coerceIn(0, 2)) }
    fun setOnboarded() = update { putBoolean("onboarded", true) }
    fun setDisplayName(v: String) = update { putString("name", v.trim().take(32)) }

    /** Jolt thresholds in m/s² for a hand-held sender and a resting receiver. */
    fun senderThreshold() = floatArrayOf(4.2f, 3.0f, 2.1f)[current.bumpSensitivity]
    fun receiverThreshold() = floatArrayOf(1.6f, 1.05f, 0.7f)[current.bumpSensitivity]
}

/** Stable random identity, plus the human name shown to the other device. */
class Identity(private val context: Context, private val settings: AppSettings) {
    private val p = context.getSharedPreferences("bump_identity", Context.MODE_PRIVATE)

    val id: String by lazy {
        p.getString("id", null) ?: UUID.randomUUID().toString().replace("-", "").take(12)
            .also { p.edit().putString("id", it).apply() }
    }

    val displayName: String
        get() = settings.current.displayName.ifBlank {
            Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
                ?: Build.MODEL
        }

    /** Endpoint name advertised over Nearby: `id|kind|name`. */
    fun endpointName(): String = "$id|${if (isTablet()) "t" else "p"}|${displayName.replace('|', ' ').take(40)}"

    fun isTablet(): Boolean = context.resources.configuration.smallestScreenWidthDp >= 600

    companion object {
        data class Endpoint(val id: String, val tablet: Boolean, val name: String)

        fun parse(endpointName: String?): Endpoint? {
            val parts = endpointName?.split('|', limit = 3) ?: return null
            if (parts.size != 3 || !Regex("[0-9a-f]{12}").matches(parts[0])) return null
            return Endpoint(parts[0], parts[1] == "t", parts[2].ifBlank { "Dispositivo" })
        }
    }
}

data class Peer(val id: String, val name: String, val tablet: Boolean, val pairedAt: Long, val lastSeen: Long)

/** Trusted devices. Secrets are sealed with [SecureBox]. */
class PeerStore(context: Context) {
    private val p = context.getSharedPreferences("bump_peers", Context.MODE_PRIVATE)
    private val _peers = MutableStateFlow(load())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private fun load(): List<Peer> = p.all.keys.filter { it.startsWith("meta_") }.mapNotNull { k ->
        runCatching {
            val o = JSONObject(p.getString(k, "{}")!!)
            Peer(o.getString("id"), o.getString("name"), o.optBoolean("tablet"), o.optLong("paired"), o.optLong("seen"))
        }.getOrNull()
    }.sortedByDescending { it.lastSeen }

    fun secret(id: String): String? = p.getString("secret_$id", null)?.let(SecureBox::open)
    fun isTrusted(id: String) = secret(id) != null

    fun trust(id: String, name: String, tablet: Boolean, secretHex: String) {
        val now = System.currentTimeMillis()
        val meta = JSONObject().put("id", id).put("name", name).put("tablet", tablet).put("paired", now).put("seen", now)
        p.edit().putString("secret_$id", SecureBox.seal(secretHex)).putString("meta_$id", meta.toString()).apply()
        _peers.value = load()
    }

    fun touch(id: String, name: String) {
        val raw = p.getString("meta_$id", null) ?: return
        val o = JSONObject(raw).put("seen", System.currentTimeMillis()).put("name", name)
        p.edit().putString("meta_$id", o.toString()).apply()
        _peers.value = load()
    }

    fun forget(id: String) {
        p.edit().remove("secret_$id").remove("meta_$id").apply()
        _peers.value = load()
    }

    fun forgetAll() {
        p.edit().clear().apply()
        _peers.value = emptyList()
    }
}

enum class Direction { SENT, RECEIVED }

data class Transfer(
    val id: String,
    val direction: Direction,
    val name: String,
    val mime: String,
    val size: Long,
    val uri: String,
    val peer: String,
    val time: Long,
)

/** Transfer history in a small JSON file (atomic replace), not in SharedPreferences. */
class TransferStore(context: Context) {
    private val file = File(context.filesDir, "history.json")
    private val lock = Any()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Transfer>> = _items.asStateFlow()

    private fun load(): List<Transfer> = runCatching {
        if (!file.exists()) return emptyList()
        val a = JSONArray(file.readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Transfer(
                o.getString("id"), Direction.valueOf(o.getString("dir")), o.getString("name"),
                o.optString("mime", "application/octet-stream"), o.optLong("size"), o.getString("uri"),
                o.optString("peer"), o.getLong("time"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<Transfer>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("id", it.id).put("dir", it.direction.name).put("name", it.name).put("mime", it.mime)
                .put("size", it.size).put("uri", it.uri).put("peer", it.peer).put("time", it.time))
        }
        val tmp = File(file.parentFile, "history.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    fun add(t: Transfer) = synchronized(lock) {
        val next = (listOf(t) + _items.value).take(200)
        save(next); _items.value = next
    }

    fun remove(id: String) = synchronized(lock) {
        val next = _items.value.filterNot { it.id == id }
        save(next); _items.value = next
    }

    fun clear() = synchronized(lock) { save(emptyList()); _items.value = emptyList() }
}
