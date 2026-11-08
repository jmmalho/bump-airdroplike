package pt.johnny.bump.motion

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID

/**
 * Accelerometer at ~100 Hz on a background thread. Only runs while a transfer
 * is armed, so the battery cost is a few seconds of sensing, not all day.
 * Event timestamps share the elapsedRealtimeNanos base used by the clock sync.
 */
class MotionSensor(context: Context, private val onJolt: (JoltDetector.Jolt) -> Unit) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var detector: JoltDetector? = null
    /** Some devices report timestamps in another base; we re-anchor if they look off. */
    private var timestampSkew: Long? = null

    val available: Boolean get() = sensor != null

    fun start(threshold: Float): Boolean {
        val s = sensor ?: return false
        stop()
        detector = JoltDetector(threshold)
        timestampSkew = null
        val t = HandlerThread("bump-motion").also { it.start() }
        thread = t
        return manager!!.registerListener(this, s, 10_000, Handler(t.looper))
    }

    fun stop() {
        manager?.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        detector = null
    }

    override fun onSensorChanged(e: SensorEvent) {
        val d = detector ?: return
        val now = SystemClock.elapsedRealtimeNanos()
        val skew = timestampSkew ?: (if (kotlin.math.abs(now - e.timestamp) > 1_000_000_000L) now - e.timestamp else 0L)
            .also { timestampSkew = it }
        val jolt = d.onSample(e.values[0], e.values[1], e.values[2], e.timestamp + skew) ?: return
        main.post { onJolt(jolt) }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * BLE presence: the receiver advertises a rotating 4-byte token (no name, not
 * connectable); the sender scans for exactly that token and reports RSSI.
 */
@SuppressLint("MissingPermission")
class Beacon(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advCallback: AdvertiseCallback? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null

    private fun bytes(hex: String): ByteArray? =
        if (!Regex("[0-9a-f]{8}").matches(hex)) null
        else ByteArray(4) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    fun advertise(token: String, active: Boolean): Boolean {
        val data = bytes(token) ?: return false
        if (adapter?.isEnabled != true) return false
        stopAdvertising()
        val adv = adapter.bluetoothLeAdvertiser ?: return false
        val cb = object : AdvertiseCallback() {}
        return try {
            adv.startAdvertising(
                AdvertiseSettings.Builder()
                    .setAdvertiseMode(if (active) AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                    .setConnectable(false).build(),
                AdvertiseData.Builder().addServiceData(SERVICE, data).setIncludeDeviceName(false).build(),
                cb,
            )
            advertiser = adv; advCallback = cb; true
        } catch (_: Exception) { false }
    }

    fun stopAdvertising() {
        val a = advertiser; val cb = advCallback
        if (a != null && cb != null) runCatching { a.stopAdvertising(cb) }
        advertiser = null; advCallback = null
    }

    fun scan(token: String, onRssi: (rssi: Int, atNanos: Long) -> Unit): Boolean {
        val data = bytes(token) ?: return false
        if (adapter?.isEnabled != true) return false
        stopScan()
        val sc = adapter.bluetoothLeScanner ?: return false
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult?) {
                val r = result ?: return
                val at = SystemClock.elapsedRealtimeNanos()
                main.post { onRssi(r.rssi, at) }
            }
        }
        return try {
            sc.startScan(
                listOf(ScanFilter.Builder().setServiceData(SERVICE, data).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES).setReportDelay(0).build(),
                cb,
            )
            scanner = sc; scanCallback = cb; true
        } catch (_: Exception) { false }
    }

    fun stopScan() {
        val s = scanner; val cb = scanCallback
        if (s != null && cb != null) runCatching { s.stopScan(cb) }
        scanner = null; scanCallback = null
    }

    fun stopAll() { stopScan(); stopAdvertising() }

    companion object {
        private val SERVICE = ParcelUuid(UUID.fromString("2473bf40-9238-4b0b-8b6d-02db6cab9c5c"))
    }
}
