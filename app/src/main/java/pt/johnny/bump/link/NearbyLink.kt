package pt.johnny.bump.link

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import pt.johnny.bump.app
import pt.johnny.bump.data.Direction
import pt.johnny.bump.data.Files
import pt.johnny.bump.data.Identity
import pt.johnny.bump.data.LocalFile
import pt.johnny.bump.data.Transfer
import pt.johnny.bump.motion.ClockSync
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Nearby Connections transport (Bluetooth for discovery, upgraded to Wi-Fi
 * Direct/hotspot for the bytes — the same stack Quick Share uses).
 *
 * Trust model
 *  - First contact: both screens show Nearby's authentication digits; the user
 *    confirms on both. Only then does the sender hand over a random 256-bit
 *    secret, inside that digit-verified encrypted channel.
 *  - Every later session: mutual HMAC challenge–response before any file or
 *    bump signal is accepted. Secrets are sealed by the Android Keystore.
 *
 * All callbacks are delivered on the main thread.
 */
class NearbyLink(context: Context, val role: Role, private val listener: Listener) {
    enum class Role { SENDER, RECEIVER }

    data class Remote(val endpointId: String, val id: String, val name: String, val tablet: Boolean, val trusted: Boolean)

    interface Listener {
        fun onDiscovered(devices: List<Remote>) {}
        fun onPairRequest(remote: Remote, digits: String) {}
        fun onConnecting(remote: Remote) {}
        fun onReady(remote: Remote) {}
        fun onControl(msg: Msg) {}
        fun onPreview(index: Int, jpeg: ByteArray) {}
        fun onIncomingStarted(name: String, mime: String) {}
        fun onProgress(fraction: Float, bytes: Long, total: Long) {}
        fun onFileSaved(index: Int, name: String, mime: String, size: Long, uri: Uri) {}
        fun onSendFinished(count: Int) {}
        fun onDisconnected(remote: Remote?) {}
        fun onError(message: String) {}
    }

    private val ctx = context.applicationContext
    private val app = ctx.app
    private val client: ConnectionsClient = Nearby.getConnectionsClient(ctx)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val myId = app.identity.id

    val clock = ClockSync()

    private val discovered = LinkedHashMap<String, Remote>()
    private var remote: Remote? = null
    private var secret: String? = null
    private var pendingSecret: String? = null
    private var userConfirmedPair = false
    private var connected = false
    var authenticated = false
        private set
    private var localNonce: String? = null
    private var remoteNonce: String? = null
    private var closed = false
    private var connectingSince = 0L

    // Receiving
    private val incoming = HashMap<Long, Payload>()
    private val completed = HashMap<Long, Payload>()
    private val meta = HashMap<Long, Msg.Meta>()
    private val progressBytes = HashMap<Long, Long>()
    private var expectedTotal = 0L

    // Sending
    private val queue = ArrayDeque<LocalFile>()
    private var active: Pair<Payload, LocalFile>? = null
    private var sentCount = 0
    private var sendIndex = 0
    private var sendTotal = 0L
    private var sendDoneBytes = 0L
    var sending = false
        private set

    val peer: Remote? get() = remote

    // ---------- lifecycle ----------

    fun startAdvertising() {
        if (closed) return
        client.startAdvertising(
            app.identity.endpointName(), SERVICE_ID, lifecycle,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnFailureListener { fail("Não foi possível ficar visível: ${it.message}") }
    }

    fun startDiscovery() {
        if (closed) return
        client.startDiscovery(
            SERVICE_ID, discovery,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnFailureListener { fail("Não foi possível procurar dispositivos: ${it.message}") }
    }

    fun close() {
        closed = true
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAdvertising() }
        runCatching { client.stopAllEndpoints() }
        active?.first?.close()
        io.shutdown()
        main.removeCallbacksAndMessages(null)
    }

    /** Sender: connect to a discovered device (trusted → silent, new → pairing digits). */
    fun connect(endpointId: String) {
        val r = discovered[endpointId] ?: return
        if (closed || connected) return
        if (remote != null && SystemClock.elapsedRealtime() - connectingSince < 8000) return
        remote = r
        connectingSince = SystemClock.elapsedRealtime()
        secret = app.peers.secret(r.id)
        listener.onConnecting(r)
        client.requestConnection(app.identity.endpointName(), endpointId, lifecycle)
            .addOnFailureListener {
                if (remote?.endpointId == endpointId) remote = null
                fail("Ligação a ${r.name} falhou. Aproxima os dispositivos e tenta outra vez.")
            }
    }

    fun disconnect() {
        remote?.let { runCatching { client.disconnectFromEndpoint(it.endpointId) } }
        resetSession()
    }

    fun approvePairing(endpointId: String, accept: Boolean) {
        if (closed || remote?.endpointId != endpointId) return
        if (!accept) {
            client.rejectConnection(endpointId)
            remote = null
            return
        }
        userConfirmedPair = true
        client.acceptConnection(endpointId, payloads)
            .addOnFailureListener { fail("Falha ao emparelhar: ${it.message}") }
    }

    // ---------- control ----------

    fun send(msg: Msg) {
        val r = remote ?: return
        if (closed || !connected) return
        client.sendPayload(r.endpointId, Payload.fromBytes(Protocol.encode(msg)))
    }

    fun sendPreview(index: Int, jpeg: ByteArray) {
        val r = remote ?: return
        if (closed || !authenticated) return
        val wire = Protocol.encodePreview(index, jpeg) ?: return
        client.sendPayload(r.endpointId, Payload.fromBytes(wire))
    }

    /** Burst of pings: the best round trip gives a tight clock offset for bump correlation. */
    fun syncClock(count: Int = 6) {
        for (i in 0 until count) main.postDelayed({
            if (authenticated) send(Msg.Ping(SystemClock.elapsedRealtimeNanos()))
        }, i * 90L)
    }

    private fun handle(msg: Msg) {
        val r = remote ?: return
        when (msg) {
            is Msg.Pair -> {
                if (role != Role.RECEIVER || !userConfirmedPair || secret != null) return
                app.peers.trust(r.id, r.name, r.tablet, msg.secretHex)
                secret = msg.secretHex
                remote = r.copy(trusted = true)
                send(Msg.PairAck)
                sendHello()
            }
            Msg.PairAck -> {
                val s = pendingSecret ?: return
                if (role != Role.SENDER || !userConfirmedPair) return
                app.peers.trust(r.id, r.name, r.tablet, s)
                secret = s; pendingSecret = null
                remote = r.copy(trusted = true)
                sendHello()
            }
            is Msg.Hello -> {
                if (msg.deviceId != r.id || secret == null) return
                remoteNonce = msg.nonce
                if (localNonce == null) sendHello()
                send(Msg.Auth(Crypto.proof(secret!!, myId, localNonce!!, msg.nonce)))
            }
            is Msg.Auth -> {
                val s = secret ?: return
                val rn = remoteNonce ?: return
                val ln = localNonce ?: return
                if (!Crypto.constantTimeEquals(Crypto.proof(s, r.id, rn, ln), msg.proof)) {
                    fail("A identidade de ${r.name} não foi confirmada. Esquece o dispositivo e emparelha de novo.")
                    disconnect()
                    return
                }
                if (!authenticated) {
                    authenticated = true
                    app.peers.touch(r.id, r.name)
                    syncClock()
                    listener.onReady(remote!!)
                }
            }
            is Msg.Ping -> if (authenticated) send(Msg.Pong(msg.t0, SystemClock.elapsedRealtimeNanos()))
            is Msg.Pong -> if (authenticated) clock.add(msg.t0, msg.t1, SystemClock.elapsedRealtimeNanos())
            is Msg.Meta -> {
                if (role != Role.RECEIVER || !authenticated) return
                meta[msg.payloadId] = msg.copy(name = Files.clean(msg.name))
                listener.onIncomingStarted(msg.name, Files.resolveMime(msg.name, msg.mime))
                finalize(msg.payloadId)
            }
            is Msg.Arm -> if (authenticated) { expectedTotal = msg.totalBytes; progressBytes.clear(); listener.onControl(msg) }
            else -> if (authenticated) listener.onControl(msg)
        }
    }

    private fun sendHello() {
        if (secret == null || !connected) return
        if (localNonce == null) localNonce = Crypto.randomHex(16)
        send(Msg.Hello(myId, localNonce!!))
    }

    // ---------- files ----------

    fun sendFiles(files: List<LocalFile>) {
        if (role != Role.SENDER || !authenticated || sending || files.isEmpty()) return
        sending = true
        sentCount = 0
        sendIndex = 0
        sendTotal = files.sumOf { it.size.coerceAtLeast(0) }
        sendDoneBytes = 0
        queue.clear(); queue.addAll(files)
        sendNext()
    }

    private fun sendNext() {
        val r = remote ?: return
        val next = queue.removeFirstOrNull()
        if (next == null) {
            sending = false
            send(Msg.Done)
            listener.onSendFinished(sentCount)
            return
        }
        try {
            val payload = Payload.fromFile(next.file)
            active = payload to next
            send(Msg.Meta(payload.id, next.name, next.mime, next.size, sendIndex++))
            client.sendPayload(r.endpointId, payload)
                .addOnFailureListener { fail("Não foi possível enviar ${next.name}.") }
        } catch (_: FileNotFoundException) {
            fail("${next.name} deixou de estar disponível.")
            sendNext()
        }
    }

    private fun finalize(id: Long) {
        val payload = completed[id] ?: return
        val m = meta[id] ?: return
        completed.remove(id); meta.remove(id)
        val source = payload.asFile()?.asUri()
        if (source == null) { fail("Ficheiro recebido sem conteúdo acessível."); return }
        val peerName = remote?.name.orEmpty()
        val mime = Files.resolveMime(m.name, m.mime)
        io.execute {
            try {
                val out = Files.saveReceived(ctx, source, m.name, mime)
                val size = Files.size(ctx, out).takeIf { it >= 0 } ?: m.size
                app.transfers.add(Transfer(UUID.randomUUID().toString(), Direction.RECEIVED, m.name, mime, size, out.toString(), peerName, System.currentTimeMillis()))
                main.post { listener.onFileSaved(m.index, m.name, mime, size, out) }
            } catch (e: Exception) {
                fail("Não foi possível guardar ${m.name}: ${e.message}")
            } finally {
                runCatching { ctx.contentResolver.delete(source, null, null) }
                payload.close()
            }
        }
    }

    // ---------- Nearby callbacks ----------

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            main.post {
                val e = Identity.parse(info.endpointName) ?: return@post
                if (closed || e.id == myId) return@post
                discovered[endpointId] = Remote(endpointId, e.id, e.name, e.tablet, app.peers.isTrusted(e.id))
                listener.onDiscovered(discovered.values.toList())
            }
        }

        override fun onEndpointLost(endpointId: String) {
            main.post {
                if (discovered.remove(endpointId) != null) listener.onDiscovered(discovered.values.toList())
            }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            main.post {
                val e = Identity.parse(info.endpointName)
                if (closed || e == null || e.id == myId || (remote != null && remote!!.endpointId != endpointId)) {
                    client.rejectConnection(endpointId); return@post
                }
                val s = app.peers.secret(e.id)
                val r = Remote(endpointId, e.id, e.name, e.tablet, s != null)
                remote = r
                secret = s
                userConfirmedPair = false
                if (s != null) {
                    client.acceptConnection(endpointId, payloads)
                        .addOnFailureListener { fail("Falha de ligação: ${it.message}") }
                } else {
                    listener.onPairRequest(r, info.authenticationDigits)
                }
            }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            main.post {
                if (closed || remote?.endpointId != endpointId) return@post
                if (!result.status.isSuccess) {
                    val r = remote
                    resetSession()
                    fail(if (r?.trusted == false) "O emparelhamento foi recusado." else "Não foi possível ligar. Confirma o Bluetooth e o Wi-Fi.")
                    return@post
                }
                connected = true
                if (role == Role.SENDER) runCatching { client.stopDiscovery() }
                if (secret == null && role == Role.SENDER && userConfirmedPair) {
                    pendingSecret = Crypto.randomHex(32)
                    send(Msg.Pair(pendingSecret!!))
                } else if (secret != null) {
                    sendHello()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            main.post {
                if (closed || remote?.endpointId != endpointId) return@post
                val r = remote
                resetSession()
                listener.onDisconnected(r)
            }
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            main.post {
                if (closed || remote?.endpointId != endpointId) return@post
                when (payload.type) {
                    Payload.Type.BYTES -> {
                        val bytes = payload.asBytes() ?: return@post
                        val preview = Protocol.decodePreview(bytes)
                        if (preview != null) { if (authenticated && role == Role.RECEIVER) listener.onPreview(preview.first, preview.second) }
                        else Protocol.decode(bytes)?.let(::handle)
                    }
                    Payload.Type.FILE -> {
                        if (!authenticated || role != Role.RECEIVER) { client.cancelPayload(payload.id); return@post }
                        incoming[payload.id] = payload
                    }
                    else -> client.cancelPayload(payload.id)
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            main.post {
                if (closed) return@post
                val id = update.payloadId
                val mine = active?.first?.id == id
                when (update.status) {
                    PayloadTransferUpdate.Status.IN_PROGRESS -> {
                        if (incoming.containsKey(id)) {
                            progressBytes[id] = update.bytesTransferred
                            val total = expectedTotal.takeIf { it > 0 } ?: update.totalBytes
                            val done = progressBytes.values.sum()
                            if (total > 0) listener.onProgress((done.toFloat() / total).coerceIn(0f, 1f), done, total)
                        } else if (mine && sendTotal > 0) {
                            val done = sendDoneBytes + update.bytesTransferred
                            listener.onProgress((done.toFloat() / sendTotal).coerceIn(0f, 1f), done, sendTotal)
                        }
                    }
                    PayloadTransferUpdate.Status.SUCCESS -> {
                        incoming.remove(id)?.let { completed[id] = it; finalize(id) }
                        if (mine) {
                            val (p, f) = active!!
                            app.transfers.add(Transfer(UUID.randomUUID().toString(), Direction.SENT, f.name, f.mime, f.size, f.openUri.toString(), remote?.name.orEmpty(), System.currentTimeMillis()))
                            sendDoneBytes += f.size.coerceAtLeast(0)
                            sentCount++
                            p.close(); active = null
                            sendNext()
                        }
                    }
                    PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> {
                        if (incoming.remove(id) != null || mine) {
                            active?.first?.close(); active = null
                            queue.clear(); sending = false
                            fail("A transferência foi interrompida.")
                        }
                    }
                }
            }
        }
    }

    private fun resetSession() {
        remote = null; secret = null; pendingSecret = null
        connected = false; authenticated = false; userConfirmedPair = false
        localNonce = null; remoteNonce = null
        incoming.clear(); completed.clear(); meta.clear(); progressBytes.clear()
        queue.clear(); active?.first?.close(); active = null; sending = false
        clock.clear()
    }

    private fun fail(message: String) = main.post { if (!closed) listener.onError(message) }

    companion object {
        const val SERVICE_ID = "pt.johnny.bump.v4"
    }
}
