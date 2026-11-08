package pt.johnny.bump.share

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import pt.johnny.bump.app
import pt.johnny.bump.data.Files
import pt.johnny.bump.data.LocalFile
import pt.johnny.bump.feedback.Cue
import pt.johnny.bump.link.Msg
import pt.johnny.bump.link.NearbyLink
import pt.johnny.bump.link.Permissions
import pt.johnny.bump.link.Radios
import pt.johnny.bump.motion.Beacon
import pt.johnny.bump.motion.BumpMatcher
import pt.johnny.bump.motion.JoltDetector
import pt.johnny.bump.motion.MotionSensor
import pt.johnny.bump.motion.RssiGate
import java.util.concurrent.Executors

enum class Phase { PREPARING, PERMISSIONS, RADIOS_OFF, SEARCHING, CONNECTING, PAIRING, READY, SENDING, SENT, ERROR }

data class Pairing(val endpointId: String, val name: String, val digits: String)

data class ShareState(
    val phase: Phase = Phase.PREPARING,
    val title: String = "",
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val firstName: String = "",
    val firstMime: String = "",
    val firstUri: Uri? = null,
    val devices: List<NearbyLink.Remote> = emptyList(),
    val target: NearbyLink.Remote? = null,
    val pairing: Pairing? = null,
    val progress: Float = 0f,
    val transferred: Long = 0,
    val hint: String = "",
    val error: String? = null,
    val radios: Radios.State = Radios.State(true, true),
    val bumpSupported: Boolean = true,
    val proximityNear: Boolean = false,
    /** Increments every time the handoff wave must start; the UI animates on change. */
    val waveTick: Int = 0,
    /** Shown only if nothing answers for a while; before that the sheet never reveals connection state. */
    val nobodyAround: Boolean = false,
    val manualQueued: Boolean = false,
    /** A jolt matched but BLE says the other device is far: ask for a firmer, closer touch. */
    val weakSignal: Boolean = false,
)

class ShareViewModel(application: Application) : AndroidViewModel(application), NearbyLink.Listener {
    private val ctx = application
    private val app = application.app
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private val _state = MutableStateFlow(ShareState())
    val state: StateFlow<ShareState> = _state.asStateFlow()

    private var staged: List<LocalFile> = emptyList()
    private var link: NearbyLink? = null
    private val beacon = Beacon(application)
    private val motion = MotionSensor(application, ::onLocalJolt)
    private val matcher = BumpMatcher()
    private var rssi = RssiGate(app.settings.current.rssiThreshold)
    private var initialized = false
    private var triggered = false
    private var manualQueued = false
    val ripple = pt.johnny.bump.ui.handoff.RippleParams.load(application)
    private val nobodyTimeout = Runnable {
        if (link?.authenticated != true && _state.value.phase in listOf(Phase.SEARCHING, Phase.CONNECTING)) _state.update { it.copy(nobodyAround = true) }
    }
    private var sendStartedAt = 0L

    private val syncLoop = object : Runnable {
        override fun run() {
            val l = link ?: return
            if (l.authenticated && !triggered) l.syncClock(3)
            main.postDelayed(this, 2500)
        }
    }

    fun load(uris: List<Uri>) {
        if (initialized) return
        initialized = true
        if (uris.isEmpty()) { error("Escolhe um ficheiro para partilhar."); return }
        val first = uris.first()
        _state.update {
            it.copy(
                phase = Phase.PREPARING, fileCount = uris.size, firstUri = first,
                firstName = Files.displayName(ctx, first),
                firstMime = Files.mime(ctx, first, Files.displayName(ctx, first)),
                title = "A preparar…",
            )
        }
        io.execute {
            try {
                val files = uris.map { Files.stage(ctx, it) }
                main.post {
                    staged = files
                    _state.update { it.copy(totalBytes = files.sumOf { f -> f.size }) }
                    begin()
                }
            } catch (e: Exception) {
                main.post { error("Não consegui abrir o ficheiro: ${e.message}") }
            }
        }
    }

    /** Called again after permissions are granted or radios switched on. */
    fun begin() {
        if (staged.isEmpty() || link != null) return
        if (!Permissions.granted(ctx)) { _state.update { it.copy(phase = Phase.PERMISSIONS, title = "Autoriza dispositivos próximos") }; return }
        val radios = Radios.state(ctx)
        if (!radios.ready) { _state.update { it.copy(phase = Phase.RADIOS_OFF, radios = radios, title = "Liga o Bluetooth e o Wi-Fi") }; return }
        _state.update { it.copy(phase = Phase.SEARCHING, radios = radios, title = "À procura…", hint = "Os teus dispositivos aparecem aqui", bumpSupported = motion.available) }
        link = NearbyLink(ctx, NearbyLink.Role.SENDER, this).also { it.startDiscovery() }
        main.removeCallbacks(nobodyTimeout)
        main.postDelayed(nobodyTimeout, 15_000)
    }

    fun refreshRadios() {
        val r = Radios.state(ctx)
        _state.update { it.copy(radios = r) }
        if (r.ready && _state.value.phase == Phase.RADIOS_OFF) begin()
    }

    /** Tap on a device avatar: connect, or send right away if it is the connected one. */
    fun tapDevice(remote: NearbyLink.Remote) {
        val l = link ?: return
        val target = _state.value.target
        if (target?.endpointId == remote.endpointId && l.authenticated) { trigger("tap"); return }
        if (target != null && target.endpointId != remote.endpointId) { disarm(); l.disconnect() }
        l.connect(remote.endpointId)
    }

    /** Manual fallback. If the link is not up yet, the request waits and fires the moment it is. */
    fun sendNow() {
        if (link?.authenticated == true) trigger("button")
        else { manualQueued = true; _state.update { it.copy(manualQueued = true) } }
    }

    fun confirmPairing(accept: Boolean) {
        val p = _state.value.pairing ?: return
        link?.approvePairing(p.endpointId, accept)
        _state.update { it.copy(pairing = null, phase = if (accept) Phase.CONNECTING else Phase.SEARCHING, title = if (accept) "A emparelhar…" else "À procura…") }
    }

    // ---------- bump ----------

    private fun arm() {
        val l = link ?: return
        val first = staged.firstOrNull() ?: return
        triggered = false
        matcher.reset()
        rssi = RssiGate(app.settings.current.rssiThreshold)
        l.send(Msg.Arm(staged.size, staged.sumOf { it.size }, first.name, first.mime))
        if (motion.available) motion.start(app.settings.senderThreshold())
        main.removeCallbacks(syncLoop)
        main.postDelayed(syncLoop, 1200)
    }

    private fun disarm() {
        motion.stop(); beacon.stopScan()
        main.removeCallbacks(syncLoop)
        link?.send(Msg.Disarm)
    }

    private fun onLocalJolt(j: JoltDetector.Jolt) {
        val l = link ?: return
        if (triggered || !l.authenticated) return
        if (matcher.onLocal(j, l.clock.best(), SystemClock.elapsedRealtimeNanos())) onBump()
    }

    private fun onPeerKnock(k: Msg.Knock) {
        val l = link ?: return
        if (triggered) return
        if (matcher.onPeer(k.atNanos, k.strength, l.clock.best(), SystemClock.elapsedRealtimeNanos())) onBump()
    }

    private fun onBump() {
        if (!rssi.allows(SystemClock.elapsedRealtimeNanos())) {
            _state.update { it.copy(weakSignal = true) }
            matcher.reset()
            return
        }
        trigger("bump")
    }

    private fun trigger(reason: String) {
        val l = link ?: return
        if (triggered || !l.authenticated || staged.isEmpty()) return
        triggered = true
        motion.stop(); beacon.stopScan(); main.removeCallbacks(syncLoop)
        l.send(Msg.Go(reason))
        // Charge hum now, release tick + sound when the ripple bursts (same timeline as the receiver).
        app.feedback.playCharge(ripple.chargeSeconds)
        val leadMs = ((ripple.chargeSeconds - pt.johnny.bump.feedback.Feedback.RELEASE_SOUND_LEAD_SECONDS) * 1000).toLong().coerceAtLeast(0)
        main.postDelayed({ app.feedback.play(Cue.BUMP) }, leadMs)
        main.removeCallbacks(nobodyTimeout)
        sendStartedAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(phase = Phase.SENDING, title = "A enviar", hint = "", progress = 0f, waveTick = it.waveTick + 1) }
        l.sendFiles(staged)
    }

    // ---------- link callbacks ----------

    override fun onDiscovered(devices: List<NearbyLink.Remote>) {
        _state.update { it.copy(devices = devices) }
        if (_state.value.target != null) return
        // Auto-connect to the most recently used trusted device: no tap needed.
        val order = app.peers.peers.value.map { it.id }
        val best = devices.filter { it.trusted }.minByOrNull { d -> order.indexOf(d.id).let { if (it < 0) Int.MAX_VALUE else it } }
        if (best != null) link?.connect(best.endpointId)
        else if (_state.value.phase == Phase.SEARCHING) _state.update { it.copy(hint = "Toca num dispositivo para emparelhar") }
    }

    override fun onConnecting(remote: NearbyLink.Remote) {
        _state.update { it.copy(phase = Phase.CONNECTING, target = remote, title = "A ligar a ${remote.name}…", error = null) }
    }

    override fun onPairRequest(remote: NearbyLink.Remote, digits: String) {
        _state.update { it.copy(phase = Phase.PAIRING, target = remote, pairing = Pairing(remote.endpointId, remote.name, digits), title = "Confirma o código") }
    }

    override fun onReady(remote: NearbyLink.Remote) {
        // Silent on purpose: no sound, no name. The user discovers the target only when they touch.
        _state.update {
            it.copy(
                phase = Phase.READY, target = remote, nobodyAround = false,
                devices = it.devices.map { d -> if (d.endpointId == remote.endpointId) d.copy(trusted = true) else d },
            )
        }
        arm()
        if (manualQueued) trigger("button")
    }

    override fun onControl(msg: Msg) {
        when (msg) {
            is Msg.Beacon -> {
                val ok = beacon.scan(msg.token) { value, at ->
                    rssi.add(value, at)
                    val near = rssi.isNear(at)
                    if (near != _state.value.proximityNear) _state.update { it.copy(proximityNear = near) }
                }
                // Without BLE the jolt correlation alone decides (RssiGate allows when it has no data).
                if (!ok) beacon.stopScan()
            }
            is Msg.Knock -> onPeerKnock(msg)
            else -> Unit
        }
    }

    override fun onProgress(fraction: Float, bytes: Long, total: Long) {
        _state.update { it.copy(progress = fraction, transferred = bytes) }
    }

    override fun onSendFinished(count: Int) {
        // Finish only once the card has risen behind the ripple, even if a small file was instant.
        val cardVisibleMs = ((ripple.chargeSeconds + ripple.rippleSeconds * 0.3f + 0.55f) * 1000).toLong()
        val wait = (cardVisibleMs - (SystemClock.elapsedRealtime() - sendStartedAt)).coerceAtLeast(0)
        main.postDelayed({
            _state.update { it.copy(phase = Phase.SENT, progress = 1f, title = if (count > 1) "$count ficheiros enviados" else "Enviado") }
            app.feedback.play(Cue.SENT)
        }, wait)
    }

    override fun onDisconnected(remote: NearbyLink.Remote?) {
        val s = _state.value
        if (s.phase == Phase.SENT) return
        motion.stop(); beacon.stopScan(); main.removeCallbacks(syncLoop)
        if (s.phase == Phase.SENDING) { error("A ligação caiu antes de terminar."); return }
        _state.update { it.copy(phase = Phase.SEARCHING, target = null) }
        link?.startDiscovery()
    }

    override fun onError(message: String) {
        val p = _state.value.phase
        if (p == Phase.SENDING || p == Phase.PREPARING) error(message)
        else _state.update { it.copy(hint = message, phase = if (p == Phase.CONNECTING || p == Phase.PAIRING) Phase.SEARCHING else p, target = if (p == Phase.CONNECTING || p == Phase.PAIRING) null else it.target) }
    }

    private fun error(message: String) {
        motion.stop(); beacon.stopScan(); main.removeCallbacks(syncLoop)
        _state.update { it.copy(phase = Phase.ERROR, error = message, title = "Não foi possível enviar") }
        app.feedback.play(Cue.ERROR)
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        link?.close(); link = null
        triggered = false
        begin()
    }

    override fun onCleared() {
        if (!triggered) link?.send(Msg.Disarm)
        motion.stop(); beacon.stopAll(); main.removeCallbacksAndMessages(null)
        link?.close()
        io.shutdown()
    }
}
