package pt.johnny.bump.receive

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pt.johnny.bump.BumpApp
import pt.johnny.bump.R
import pt.johnny.bump.app
import pt.johnny.bump.data.Files
import pt.johnny.bump.feedback.Cue
import pt.johnny.bump.link.Crypto
import pt.johnny.bump.link.Msg
import pt.johnny.bump.link.NearbyLink
import pt.johnny.bump.link.Permissions
import pt.johnny.bump.link.Radios
import pt.johnny.bump.motion.Beacon
import pt.johnny.bump.motion.MotionSensor
import pt.johnny.bump.ui.MainActivity
import pt.johnny.bump.ui.OpenFileActivity
import pt.johnny.bump.ui.handoff.NameDropView
import pt.johnny.bump.ui.handoff.RippleParams

/** Visible connectedDevice foreground service that lets paired devices bump files into this one. */
class ReceiverService : Service(), NearbyLink.Listener {

    data class Status(
        val running: Boolean = false,
        val text: String = "Receção desligada",
        val peer: String? = null,
        val armedFile: String? = null,
        val radios: Radios.State = Radios.State(true, true),
    )

    data class PairRequest(val endpointId: String, val name: String, val digits: String)

    private val main = Handler(Looper.getMainLooper())
    private var link: NearbyLink? = null
    private lateinit var beacon: Beacon
    private lateinit var motion: MotionSensor
    private var token = Crypto.randomHex(4)
    private var running = false
    private var armed = false
    private var armedName = ""
    private var armedMime = ""
    private var waveStartedAt = 0L
    private var radioReceiver: BroadcastReceiver? = null

    private var windowManager: WindowManager? = null
    private var overlay: NameDropView? = null
    private var peerName: String? = null
    /** Preview the sender pushed before the bump, so the item can appear the instant they touch. */
    private var preview: android.graphics.Bitmap? = null
    private var linkHost = ""
    private val removeOverlay = Runnable { hideOverlay() }
    private val disarmTimeout = Runnable { disarm() }
    private val calmBeacon = Runnable { if (running) beacon.advertise(token, active = false) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        beacon = Beacon(this)
        motion = MotionSensor(this) { jolt ->
            if (armed) link?.send(Msg.Knock(jolt.atNanos, jolt.strength))
        }
        windowManager = getSystemService(WindowManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { app.settings.setReceiving(false); stopSelf(); return START_NOT_STICKY }
            ACTION_PAIR_ACCEPT, ACTION_PAIR_REJECT -> {
                answerPairing(intent.getStringExtra(EXTRA_ENDPOINT).orEmpty(), intent.action == ACTION_PAIR_ACCEPT)
                return START_STICKY
            }
        }
        goForeground()
        if (!running) {
            running = true
            registerRadios()
            restartLink()
        }
        return START_STICKY
    }

    private fun goForeground() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReceiverService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, BumpApp.CH_PRESENCE)
            .setSmallIcon(R.drawable.ic_stat_bump)
            .setContentTitle("BUMP pronto a receber")
            .setContentText("Encosta um dispositivo emparelhado para receber ficheiros")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Parar", stop).build())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_PRESENCE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(NOTIF_PRESENCE, n)
    }

    private fun restartLink() {
        link?.close()
        link = null
        disarm()
        val radios = Radios.state(this)
        if (!radios.ready || !Permissions.granted(this)) {
            publish(if (!radios.ready) "Liga o Bluetooth e o Wi-Fi para receber" else "Faltam autorizações de dispositivos próximos", radios = radios)
            return
        }
        token = Crypto.randomHex(4)
        beacon.advertise(token, active = false)
        link = NearbyLink(this, NearbyLink.Role.RECEIVER, this).also { it.startAdvertising() }
        publish("Visível para os teus dispositivos", radios = radios)
    }

    private fun registerRadios() {
        if (radioReceiver != null) return
        radioReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                main.removeCallbacks(restartRunnable)
                main.postDelayed(restartRunnable, 1200)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction("android.net.wifi.WIFI_STATE_CHANGED")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(radioReceiver, filter, Context.RECEIVER_EXPORTED)
        else registerReceiver(radioReceiver, filter)
    }

    private val restartRunnable = Runnable { if (running && link?.authenticated != true) restartLink() }

    private fun publish(text: String, peer: String? = _status.value.peer, armedFile: String? = if (armed) armedName else null, radios: Radios.State = _status.value.radios) {
        _status.value = Status(running, text, peer, armedFile, radios)
    }

    // ---------- pairing ----------

    override fun onPairRequest(remote: NearbyLink.Remote, digits: String) {
        _pairRequest.value = PairRequest(remote.endpointId, remote.name, digits)
        val accept = PendingIntent.getService(this, 2, Intent(this, ReceiverService::class.java).setAction(ACTION_PAIR_ACCEPT).putExtra(EXTRA_ENDPOINT, remote.endpointId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val reject = PendingIntent.getService(this, 3, Intent(this, ReceiverService::class.java).setAction(ACTION_PAIR_REJECT).putExtra(EXTRA_ENDPOINT, remote.endpointId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, BumpApp.CH_PAIRING)
            .setSmallIcon(R.drawable.ic_stat_bump)
            .setContentTitle("Emparelhar com ${remote.name}?")
            .setContentText("Código $digits — confirma que é igual no outro ecrã")
            .setStyle(Notification.BigTextStyle().bigText("Código de segurança: $digits\nConfirma que o ${remote.name} mostra exatamente o mesmo código. Só precisas de fazer isto uma vez."))
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Os códigos coincidem", accept).build())
            .addAction(Notification.Action.Builder(null, "Recusar", reject).build())
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        notify(NOTIF_PAIR, n)
        main.postDelayed({ if (_pairRequest.value?.endpointId == remote.endpointId) answerPairing(remote.endpointId, false) }, 60_000)
    }

    private fun answerPairing(endpointId: String, accept: Boolean) {
        if (_pairRequest.value?.endpointId != endpointId) return
        _pairRequest.value = null
        getSystemService(NotificationManager::class.java).cancel(NOTIF_PAIR)
        link?.approvePairing(endpointId, accept)
    }

    // ---------- session ----------
    // Nothing on screen before the bump: the connection stays invisible so the touch feels like magic.

    override fun onReady(remote: NearbyLink.Remote) {
        peerName = remote.name
        publish("Pronto a receber")
        link?.send(Msg.Beacon(token))
        beacon.advertise(token, active = true)
        main.removeCallbacks(calmBeacon)
        main.postDelayed(calmBeacon, 60_000)
    }

    override fun onControl(msg: Msg) {
        when (msg) {
            is Msg.Arm -> arm(msg)
            Msg.Disarm -> disarm()
            is Msg.Go -> incoming()
            is Msg.Link -> linkReceived(msg)
            is Msg.Text -> textReceived(msg)
            else -> Unit
        }
    }

    override fun onPreview(jpeg: ByteArray) {
        Thread {
            val opts = android.graphics.BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
            val b = runCatching { android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) }.getOrNull()
            main.post { if (b != null) { preview = b; overlay?.setHeroImage(b) } }
        }.start()
    }

    private fun arm(m: Msg.Arm) {
        preview = null
        linkHost = if (m.firstMime == Files.LINK_MIME) m.firstName else ""
        armed = true
        armedName = if (m.count > 1) "${m.count} ficheiros" else m.firstName
        armedMime = m.firstMime
        motion.start(app.settings.receiverThreshold())
        main.removeCallbacks(disarmTimeout)
        main.postDelayed(disarmTimeout, 90_000)
    }

    private fun disarm() {
        if (!armed) return
        armed = false
        motion.stop()
        main.removeCallbacks(disarmTimeout)
    }

    private fun incoming() {
        armed = false
        motion.stop()
        main.removeCallbacks(disarmTimeout)
        publish("A receber $armedName")
        waveStartedAt = SystemClock.elapsedRealtime()
        val params = RippleParams.load(this)
        app.feedback.playCharge(params.chargeSeconds)
        showOverlay(params)
    }

    private fun locked() = getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true

    /**
     * Shared finish for files and links: wait until the item has landed on screen, then either
     * grow it to full screen and open it (photos, videos, PDFs, links) or show it with a button.
     */
    private fun deliver(name: String, mime: String, detail: String, open: () -> Unit) {
        val p = RippleParams.load(this)
        val landedMs = ((p.chargeSeconds + 0.9f) * 1000).toLong()
        val wait = (landedMs - (SystemClock.elapsedRealtime() - waveStartedAt)).coerceAtLeast(0)
        val direct = app.settings.current.autoOpen && Files.opensDirectly(name, mime) && !locked()
        main.postDelayed({
            app.feedback.play(Cue.RECEIVED)
            val o = overlay
            when {
                o != null && direct -> o.expandThen {
                    open()
                    main.postDelayed({ hideOverlay() }, 380)
                }
                o != null -> {
                    o.setFile(name, mime)
                    o.showDone(detail, Files.openLabel(name, mime)) { hideOverlay(); open() }
                    main.removeCallbacks(removeOverlay)
                    main.postDelayed(removeOverlay, 9000)
                }
                direct -> open() // no overlay permission: still open it if Android allows
            }
        }, wait)
    }

    private fun openItem(uri: Uri, name: String, mime: String) {
        runCatching { startActivity(OpenFileActivity.intent(this, uri, name, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun linkReceived(m: Msg.Link) {
        val host = runCatching { Uri.parse(m.url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        val title = m.title.ifBlank { host }
        val uri = Uri.parse(m.url)
        app.transfers.add(pt.johnny.bump.data.Transfer(java.util.UUID.randomUUID().toString(), pt.johnny.bump.data.Direction.RECEIVED, title, Files.LINK_MIME, 0, m.url, peerName.orEmpty(), System.currentTimeMillis()))
        publish("Recebido: $title")
        overlay?.apply { setLinkHost(host); setFile(title, Files.LINK_MIME) }
        notifyReceived(title, Files.LINK_MIME, uri)
        deliver(title, Files.LINK_MIME, host) { openItem(uri, title, Files.LINK_MIME) }
    }

    private fun textReceived(m: Msg.Text) {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        runCatching { cm?.setPrimaryClip(android.content.ClipData.newPlainText("BUMP", m.text)) }
        publish("Texto copiado")
        overlay?.let {
            it.setFile("", Files.TEXT_MIME)
            it.showDone("Copiado para a área de transferência", null, null)
            main.removeCallbacks(removeOverlay)
            main.postDelayed(removeOverlay, 3500)
        }
        app.feedback.play(Cue.RECEIVED)
    }

    override fun onIncomingStarted(name: String, mime: String) {
        overlay?.setFile(name, mime)
    }

    override fun onProgress(fraction: Float, bytes: Long, total: Long) {
        overlay?.setProgress(fraction)
        overlay?.setHeadline(peerName ?: "Outro dispositivo", "${Files.humanSize(bytes)} de ${Files.humanSize(total)}")
    }

    override fun onFileSaved(name: String, mime: String, size: Long, uri: Uri) {
        publish("Recebido: $name")
        notifyReceived(name, mime, uri)
        if (preview == null) loadThumb(uri) { bmp -> preview = bmp; overlay?.setHeroImage(bmp) }
        deliver(name, mime, "De ${peerName ?: "outro dispositivo"} · ${Files.humanSize(size)}") { openItem(uri, name, mime) }
    }

    private fun loadThumb(uri: Uri, done: (android.graphics.Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 29) return
        Thread {
            val b = runCatching { contentResolver.loadThumbnail(uri, android.util.Size(384, 384), null) }.getOrNull()
            if (b != null) main.post { done(b) }
        }.start()
    }

    override fun onDisconnected(remote: NearbyLink.Remote?) {
        disarm()
        publish("Pronto a receber")
        main.postDelayed({ if (running) restartLink() }, 1500)
    }

    override fun onError(message: String) {
        publish(message)
        val o = overlay ?: return
        o.showError(message)
        app.feedback.play(Cue.ERROR)
        main.removeCallbacks(removeOverlay)
        main.postDelayed(removeOverlay, 3500)
    }

    // ---------- notifications ----------

    private fun notify(id: Int, n: Notification) {
        if (Permissions.notificationsGranted(this)) getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun notifyReceived(name: String, mime: String, uri: Uri) {
        val open = PendingIntent.getActivity(
            this, uri.hashCode(),
            OpenFileActivity.intent(this, uri, name, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, BumpApp.CH_FILES)
            .setSmallIcon(R.drawable.ic_stat_bump)
            .setContentTitle("Recebido de ${peerName ?: "outro dispositivo"}")
            .setContentText(name)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, Files.openLabel(name, mime), open).build())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        notify(NOTIF_FILE_BASE + (uri.hashCode() and 0xffff), n)
    }

    // ---------- overlay ----------

    private fun showOverlay(params: RippleParams) {
        if (!Permissions.overlayGranted(this)) return
        if (getSystemService(PowerManager::class.java)?.isInteractive != true) return // never wake a sleeping screen
        hideOverlay()
        val view = NameDropView(this).apply {
            this.params = params
            configure(NameDropView.Role.RECEIVER, null, null)
            setFile(armedName, armedMime)
            if (armedMime == Files.LINK_MIME) setLinkHost(linkHost)
            setHeroImage(preview)
            setHeadline(peerName ?: "Outro dispositivo", "A receber…")
            onReleaseCue = { app.feedback.play(Cue.BUMP) }
            // Once the choreography is over, a tap anywhere dismisses (a long transfer keeps going).
            setOnClickListener { if (!isRunning) hideOverlay() }
        }
        val params2 = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        try {
            windowManager?.addView(view, params2)
            overlay = view
            view.play()
            main.postDelayed(removeOverlay, 120_000)
        } catch (_: Exception) { overlay = null }
    }

    private fun hideOverlay() {
        main.removeCallbacks(removeOverlay)
        val v = overlay ?: return
        overlay = null
        // Leave with a quick ease-out fade instead of vanishing.
        v.isClickable = false
        v.animate().alpha(0f).setDuration(200).setInterpolator(pt.johnny.bump.ui.theme.Motion.easeOutInterpolator())
            .withEndAction { runCatching { windowManager?.removeView(v) } }.start()
    }

    override fun onDestroy() {
        running = false
        _status.value = Status()
        _pairRequest.value = null
        main.removeCallbacksAndMessages(null)
        radioReceiver?.let { runCatching { unregisterReceiver(it) } }
        hideOverlay()
        motion.stop()
        beacon.stopAll()
        link?.close()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "pt.johnny.bump.STOP"
        private const val ACTION_PAIR_ACCEPT = "pt.johnny.bump.PAIR_ACCEPT"
        private const val ACTION_PAIR_REJECT = "pt.johnny.bump.PAIR_REJECT"
        private const val EXTRA_ENDPOINT = "endpoint"
        private const val NOTIF_PRESENCE = 47
        private const val NOTIF_PAIR = 48
        private const val NOTIF_FILE_BASE = 1000

        private val _status = MutableStateFlow(Status())
        val status: StateFlow<Status> = _status.asStateFlow()
        private val _pairRequest = MutableStateFlow<PairRequest?>(null)
        val pairRequest: StateFlow<PairRequest?> = _pairRequest.asStateFlow()

        fun start(c: Context) {
            c.startForegroundService(Intent(c, ReceiverService::class.java))
        }

        fun stop(c: Context) { c.stopService(Intent(c, ReceiverService::class.java)) }

        fun answer(c: Context, endpointId: String, accept: Boolean) {
            c.startService(Intent(c, ReceiverService::class.java).setAction(if (accept) ACTION_PAIR_ACCEPT else ACTION_PAIR_REJECT).putExtra(EXTRA_ENDPOINT, endpointId))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.app.settings.current.receiving && Permissions.granted(context)) {
            runCatching { ReceiverService.start(context) }
        }
    }
}
