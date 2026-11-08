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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
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
import pt.johnny.bump.ui.handoff.HandoffView
import pt.johnny.bump.ui.handoff.dp

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
    private var overlay: HandoffView? = null
    private var action: TextView? = null
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

    override fun onReady(remote: NearbyLink.Remote) {
        publish("Ligado a ${remote.name}", peer = remote.name)
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
            Msg.Done -> Unit
            else -> Unit
        }
    }

    private fun arm(m: Msg.Arm) {
        armed = true
        armedName = if (m.count > 1) "${m.count} ficheiros" else m.firstName
        armedMime = m.firstMime
        motion.start(app.settings.receiverThreshold())
        main.removeCallbacks(disarmTimeout)
        main.postDelayed(disarmTimeout, 90_000)
        publish("${_status.value.peer ?: "Um dispositivo"} quer partilhar $armedName")
        showOverlay(HandoffView.Mode.READY)
    }

    private fun disarm() {
        if (!armed) return
        armed = false
        motion.stop()
        main.removeCallbacks(disarmTimeout)
        if (overlay?.mode == HandoffView.Mode.READY) hideOverlay()
        publish(if (_status.value.peer != null) "Ligado a ${_status.value.peer}" else "Visível para os teus dispositivos")
    }

    private fun incoming() {
        armed = false
        motion.stop()
        main.removeCallbacks(disarmTimeout)
        app.feedback.play(Cue.INCOMING)
        publish("A receber $armedName")
        showOverlay(HandoffView.Mode.RECEIVE)
        waveStartedAt = SystemClock.elapsedRealtime()
    }

    override fun onIncomingStarted(name: String, mime: String) {
        overlay?.setFile(name, mime)
    }

    override fun onProgress(fraction: Float, bytes: Long, total: Long) {
        overlay?.setProgress(fraction)
        overlay?.setDetail("${Files.humanSize(bytes)} de ${Files.humanSize(total)}")
    }

    override fun onFileSaved(name: String, mime: String, size: Long, uri: Uri) {
        publish("Recebido: $name")
        val wait = (1450 - (SystemClock.elapsedRealtime() - waveStartedAt)).coerceAtLeast(0)
        main.postDelayed({
            app.feedback.play(Cue.RECEIVED)
            overlay?.let {
                it.setFile(name, mime)
                it.setDetail(Files.humanSize(size))
                it.play(HandoffView.Mode.DONE)
                showOpenAction(name, mime, uri)
                main.removeCallbacks(removeOverlay)
                main.postDelayed(removeOverlay, 6000)
            }
        }, wait)
        notifyReceived(name, mime, uri)
    }

    override fun onDisconnected(remote: NearbyLink.Remote?) {
        disarm()
        publish("Visível para os teus dispositivos", peer = null)
        main.postDelayed({ if (running) restartLink() }, 1500)
    }

    override fun onError(message: String) {
        publish(message)
        if (overlay != null && overlay?.mode != HandoffView.Mode.DONE) {
            overlay?.setDetail(message)
            overlay?.play(HandoffView.Mode.ERROR)
            app.feedback.play(Cue.ERROR)
            main.removeCallbacks(removeOverlay)
            main.postDelayed(removeOverlay, 2600)
        }
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
            .setContentTitle("Recebido de ${_status.value.peer ?: "dispositivo"}")
            .setContentText(name)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, Files.openLabel(name, mime), open).build())
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        notify(NOTIF_FILE_BASE + (uri.hashCode() and 0xffff), n)
    }

    // ---------- overlay ----------

    private fun canShowOverlay(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        return Permissions.overlayGranted(this) && power?.isInteractive == true
    }

    private fun showOverlay(mode: HandoffView.Mode) {
        if (!canShowOverlay()) return
        main.removeCallbacks(removeOverlay)
        val existing = overlay
        if (existing != null) {
            existing.setFile(armedName, armedMime)
            existing.setPeer(_status.value.peer.orEmpty())
            existing.play(mode)
            main.postDelayed(removeOverlay, if (mode == HandoffView.Mode.READY) 90_000 else 120_000)
            return
        }
        val view = HandoffView(this, sender = false).apply {
            setFile(armedName, armedMime)
            setPeer(_status.value.peer.orEmpty())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Android 12+: an untouchable overlay only lets touches through at ≤ 0.8 opacity.
            alpha = 0.8f
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
        }
        try {
            windowManager?.addView(view, params)
            overlay = view
            view.play(mode)
            main.postDelayed(removeOverlay, if (mode == HandoffView.Mode.READY) 90_000 else 120_000)
        } catch (_: Exception) { overlay = null }
    }

    private fun showOpenAction(name: String, mime: String, uri: Uri) {
        if (!Permissions.overlayGranted(this)) return
        action?.let { runCatching { windowManager?.removeView(it) } }
        val button = TextView(this).apply {
            text = "${Files.openLabel(name, mime)}  ›"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            val h = dp(26f).toInt(); val v = dp(12f).toInt()
            setPadding(h, v, h, v)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(28f); setColor(0xFF1F6BFF.toInt())
            }
            elevation = dp(8f)
            setOnClickListener {
                hideOverlay()
                runCatching { startActivity(OpenFileActivity.intent(this@ReceiverService, uri, name, mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = dp(72f).toInt() }
        runCatching { windowManager?.addView(button, p); action = button }
    }

    private fun hideOverlay() {
        main.removeCallbacks(removeOverlay)
        action?.let { runCatching { windowManager?.removeView(it) } }; action = null
        overlay?.let { runCatching { windowManager?.removeView(it) } }; overlay = null
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
