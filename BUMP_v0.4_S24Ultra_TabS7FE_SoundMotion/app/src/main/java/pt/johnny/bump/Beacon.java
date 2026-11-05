package pt.johnny.bump;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.util.Collections;
import java.util.UUID;

/**
 * A non-secret 4-byte rotating BLE token identifies the ALREADY AUTHENTICATED peer's beacon.
 * RSSI is NOT distance; multiple strong samples are a heuristic, not proof of contact.
 */
final class Beacon {
    private static final ParcelUuid UUID_SERVICE = new ParcelUuid(UUID.fromString("2473bf40-9238-4b0b-8b6d-02db6cab9c5c"));
    private final Context context;
    private final BluetoothAdapter adapter;
    private BluetoothLeAdvertiser advertiser;
    private BluetoothLeScanner scanner;
    private boolean broadcasting;
    private boolean listening;
    private ProximityGate gate;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable timeout;
    private Runnable trigger;
    private Runnable scanFailure;
    private AdvertiseCallback advCb;
    private ScanCallback scanCb;

    Beacon(Context c) {
        context = c.getApplicationContext();
        BluetoothManager manager = (BluetoothManager) c.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }
    private byte[] parse(String hex) {
        if (hex == null || !hex.matches("[0-9a-f]{8}")) return null;
        byte[] data = new byte[4];
        for (int i = 0; i < 4; i++) data[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return data;
    }
    @SuppressLint("MissingPermission") boolean advertise(String token) {
        return advertise(token, false);
    }

    /** Balanced mode only while a trusted peer is actively sharing; idle stays low power. */
    @SuppressLint("MissingPermission") boolean advertise(String token, boolean activeShare) {
        byte[] bytes = parse(token);
        if (adapter == null || !adapter.isEnabled() || bytes == null) return false;
        stopAdvertising();
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) return false;
        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceData(UUID_SERVICE, bytes).setIncludeDeviceName(false).build();
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(activeShare ? AdvertiseSettings.ADVERTISE_MODE_BALANCED : AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(false).build();
        advCb = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings settings) { broadcasting = true; }
            @Override public void onStartFailure(int code) { broadcasting = false; }
        };
        try { advertiser.startAdvertising(settings, data, advCb); return true; }
        catch (Exception e) { return false; }
    }
    @SuppressLint("MissingPermission") void stopAdvertising() {
        if (advertiser != null && advCb != null) {
            try { advertiser.stopAdvertising(advCb); } catch (Exception ignored) { }
        }
        broadcasting = false;
        advCb = null;
    }
    @SuppressLint("MissingPermission") boolean listen(String token, Runnable onNear, Runnable onUnavailable) {
        byte[] bytes = parse(token);
        if (adapter == null || !adapter.isEnabled() || bytes == null) return false;
        stopScan();
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return false;
        trigger = onNear;
        scanFailure = onUnavailable;
        listening = true;
        gate = new ProximityGate(Prefs.sensitivity(context));
        ScanFilter filter = new ScanFilter.Builder().setServiceData(UUID_SERVICE, bytes).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0L).build();
        scanCb = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                if (!listening || result == null) return;
                long now = android.os.SystemClock.elapsedRealtime();
                if (gate.sample(result.getRssi(), now)) {
                    stopScan();
                    handler.post(onNear);
                }
            }
            @Override public void onScanFailed(int code) {
                if (!listening) return;
                Runnable failure = scanFailure;
                stopScan();
                if (failure != null) handler.post(failure);
            }
        };
        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCb);
            timeout = () -> {
                if (!listening) return;
                Runnable failure = scanFailure;
                stopScan();
                if (failure != null) failure.run();
            };
            handler.postDelayed(timeout, 45000);
            return true;
        } catch (Exception e) { stopScan(); return false; }
    }
    @SuppressLint("MissingPermission") void stopScan() {
        listening = false;
        if (timeout != null) handler.removeCallbacks(timeout);
        if (scanner != null && scanCb != null) {
            try { scanner.stopScan(scanCb); } catch (Exception ignored) { }
        }
        scanCb = null;
        scanFailure = null;
    }
}
