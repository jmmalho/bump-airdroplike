package pt.johnny.bump;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.TextView;
import android.widget.Toast;

/** User-enabled, visible Android connectedDevice foreground service. */
public final class ReceiverService extends Service implements NearbyLink.Listener {
    static final String ACTION_START = "pt.johnny.bump.START_RECEIVING";
    static final String ACTION_STOP = "pt.johnny.bump.STOP_RECEIVING";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private NearbyLink link;
    private Beacon beacon;
    private String beaconToken;
    private WindowManager windowManager;
    private PulseView overlay;
    private TextView receivedAction;
    private BroadcastReceiver radioReceiver;
    private Runnable overlayRemoval;
    private Runnable endActiveAdvertising;
    private long waveStartedAt;
    private Runnable pendingArrival;
    private boolean running;
    private static final int NOTIFICATION_ID = 47;
    @Override public IBinder onBind(Intent intent) { return null; }
    static void start(Context c) {
        Intent i = new Intent(c, ReceiverService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }
    static void stop(Context c) { c.stopService(new Intent(c, ReceiverService.class)); }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        createNotification();
        if (!running) {
            running = true;
            startRadio();
        }
        return START_STICKY;
    }
    private void createNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel("bump_presence", "BUMP em segundo plano", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Permite que os teus dispositivos emparelhados recebam ficheiros");
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification note = new Notification.Builder(this, "bump_presence")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("BUMP ativo")
                .setContentText("Disponível para os teus dispositivos")
                .setContentIntent(open).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_SECRET).build();
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, note, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(NOTIFICATION_ID, note);
    }
    private void startRadio() {
        if (endActiveAdvertising != null) handler.removeCallbacks(endActiveAdvertising);
        endActiveAdvertising = null;
        if (link != null) link.close();
        if (beacon != null) beacon.stopAdvertising();
        beacon = new Beacon(this);
        beaconToken = Prefs.randomHex(4);
        beacon.advertise(beaconToken);
        link = new NearbyLink(this, false, this);
        link.setBeaconToken(beaconToken);
        link.startReceive();
        if (radioReceiver == null) {
            radioReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    // Nearby's Wi-Fi transport adapts automatically, but reset a stale endpoint on radio re-enable.
                    String action = intent.getAction();
                    if (android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                        int state = intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1);
                        if (state == android.bluetooth.BluetoothAdapter.STATE_ON)
                            handler.postDelayed(ReceiverService.this::startRadio, 1200);
                    } else if ("android.net.wifi.WIFI_STATE_CHANGED".equals(action)) {
                        int state = intent.getIntExtra("wifi_state", -1);
                        if (state == android.net.wifi.WifiManager.WIFI_STATE_ENABLED && link == null)
                            handler.postDelayed(ReceiverService.this::startRadio, 1200);
                    }
                }
            };
            IntentFilter f = new IntentFilter();
            f.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
            f.addAction("android.net.wifi.WIFI_STATE_CHANGED");
            registerReceiver(radioReceiver, f);
        }
    }
    @Override public void onStatus(String message) { MainActivity.updateStatus(message); }
    @Override public void onPairRequest(String endpointId, String name, String digits) {
        if (!MainActivity.requestPairFromService(endpointId, name, digits, link)) {
            link.approvePairing(endpointId, false); // Never accept an unfamiliar device invisibly.
        }
    }
    @Override public void onReady(String peerName) {
        MainActivity.updateStatus("Emparelhado com " + peerName);
        // Once authenticated, increase the beacon frequency for a short window only.
        if (beacon != null && beaconToken != null) {
            beacon.advertise(beaconToken, true);
            if (endActiveAdvertising != null) handler.removeCallbacks(endActiveAdvertising);
            endActiveAdvertising = () -> {
                endActiveAdvertising = null;
                if (running && beacon != null && beaconToken != null)
                    beacon.advertise(beaconToken, false);
            };
            handler.postDelayed(endActiveAdvertising, 65000);
        }
    }
    @Override public void onBeacon(String token) { }
    @Override public void onIncoming(String name) {
        MainActivity.updateStatus("A receber ficheiro");
        showOverlay("O ficheiro está a chegar…", false);
        if (overlay != null) overlay.setFilename(name);
        BumpSfx.play(this, BumpSfx.Cue.INCOMING);
    }
    @Override public void onProgress(int percentage) {
        if (overlay != null) overlay.setSubtitle("A receber · " + percentage + "%");
    }
    @Override public void onIncomingSaved(String name, Uri uri) {
        MainActivity.updateStatus("Recebido: " + name);
        // Small PDFs may arrive in <200 ms. Preserve the visible arrival wave
        // before displaying success, but never announce success before saving.
        if (pendingArrival != null) handler.removeCallbacks(pendingArrival);
        long elapsed = SystemClock.elapsedRealtime() - waveStartedAt;
        pendingArrival = () -> {
            pendingArrival = null;
            if (overlay != null) {
                overlay.received(name);
                if (overlayRemoval != null) handler.removeCallbacks(overlayRemoval);
                overlayRemoval = this::removeOverlay;
                handler.postDelayed(overlayRemoval, 1800);
            } else {
                showOverlay("Recebido", true);
                if (overlay != null) overlay.setFilename(name);
            }
            BumpSfx.play(this, BumpSfx.Cue.RECEIVED);
            if (overlay != null) {
                showOpenAction(name, uri);
                if (overlayRemoval != null) handler.removeCallbacks(overlayRemoval);
                overlayRemoval = this::removeOverlay;
                handler.postDelayed(overlayRemoval, 5800);
            }
        };
        handler.postDelayed(pendingArrival, overlay != null ? Math.max(0, 1480 - elapsed) : 0);
        MainActivity.refreshHistory();
        handler.postDelayed(() -> {
            // Allow reconnects for the next transfer after the sender leaves.
            if (link != null && !link.isAuthenticated()) startRadio();
        }, 2200);
    }
    @Override public void onSending(String name) { }
    @Override public void onSendingFinished() { }
    @Override public void onDisconnected() {
        MainActivity.updateStatus("À espera de um dispositivo…");
        handler.postDelayed(() -> { if (running) startRadio(); }, 1700);
    }
    @Override public void onError(String message) {
        MainActivity.updateStatus(message);
        if (pendingArrival != null) { handler.removeCallbacks(pendingArrival); pendingArrival = null; }
        if (overlay != null) {
            overlay.error(message);
            BumpSfx.play(this, BumpSfx.Cue.ERROR);
            if (overlayRemoval != null) handler.removeCallbacks(overlayRemoval);
            overlayRemoval = this::removeOverlay;
            handler.postDelayed(overlayRemoval, 2300);
        }
    }

    /** A small, independently touchable CTA above the non-touchable wave overlay. */
    private void showOpenAction(String name, Uri file) {
        if (overlay == null || windowManager == null || !Settings.canDrawOverlays(this)) return;
        if (receivedAction != null) {
            try { windowManager.removeView(receivedAction); } catch (Exception ignored) { }
            receivedAction = null;
        }
        final String mime = HistoryStore.mime(this, file, name);
        TextView button = new TextView(this);
        button.setText(OpenFileActivity.buttonLabel(name, mime) + "  ›");
        button.setTextSize(15f);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(Color.WHITE);
        button.setGravity(Gravity.CENTER);
        button.setPadding(GlassStyle.dp(this, 22), GlassStyle.dp(this, 9), GlassStyle.dp(this, 22), GlassStyle.dp(this, 9));
        button.setBackground(GlassStyle.action(this, 23));
        button.setElevation(GlassStyle.dp(this, 7));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, GlassStyle.dp(this, 50),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = GlassStyle.dp(this, 64);
        button.setOnClickListener(v -> {
            removeOverlay();
            try {
                Intent open = OpenFileActivity.intent(this, file, name, mime)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(open);
            } catch (Exception ex) {
                Toast.makeText(this, "Abre o ficheiro através do histórico BUMP", Toast.LENGTH_LONG).show();
            }
        });
        try { windowManager.addView(button, p); receivedAction = button; }
        catch (Exception ignored) { /* History remains the reliable fallback. */ }
    }

    private void showOverlay(String title, boolean complete) {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        // Do not wake a sleeping/locked tablet: background screen-on is not guaranteed on Android.
        if (power != null && !power.isInteractive()) return;
        if (!Settings.canDrawOverlays(this)) return;
        removeOverlay();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        overlay = new PulseView(this, false);
        overlay.setSubtitle(title);
        if (!complete) waveStartedAt = SystemClock.elapsedRealtime();
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        // A translucent full-screen overlay must not block touches on Android 12+.
        params.alpha = .78f; // Android 12+ pass-through touch threshold; keep below .8
        // True system blur is optional; PulseView still has a self-contained glass surface.
        if (android.os.Build.VERSION.SDK_INT >= 31 && GlassStyle.canBlur(this)) {
            params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            params.setBlurBehindRadius(GlassStyle.dp(this, 15));
        }
        try {
            windowManager.addView(overlay, params);
            overlay.start(complete ? PulseView.Mode.RECEIVED : PulseView.Mode.RECEIVE);
            overlayRemoval = this::removeOverlay;
            handler.postDelayed(overlayRemoval, complete ? 1800 : 120000);
        } catch (Exception e) { overlay = null; }
    }
    private void removeOverlay() {
        if (receivedAction != null && windowManager != null) {
            try { windowManager.removeView(receivedAction); } catch (Exception ignored) { }
            receivedAction = null;
        }
        if (overlayRemoval != null) handler.removeCallbacks(overlayRemoval);
        overlayRemoval = null;
        if (overlay != null && windowManager != null) {
            try { windowManager.removeView(overlay); } catch (Exception ignored) { }
        }
        overlay = null;
    }
    @Override public void onDestroy() {
        running = false;
        if (endActiveAdvertising != null) handler.removeCallbacks(endActiveAdvertising);
        if (pendingArrival != null) handler.removeCallbacks(pendingArrival);
        if (radioReceiver != null) { try { unregisterReceiver(radioReceiver); } catch (Exception ignored) { } }
        removeOverlay();
        if (link != null) link.close();
        if (beacon != null) beacon.stopAdvertising();
        super.onDestroy();
    }
}
