package pt.johnny.bump;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Transparent Android Sharesheet target — no separate visible main app. */
public final class ShareActivity extends Activity implements NearbyLink.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ArrayList<HistoryStore.LocalFile> staged = new ArrayList<>();
    private NearbyLink link;
    private Beacon beacon;
    private PulseView pulse;
    private TextView manual;
    private TextView cancel;
    private boolean started, triggered, destroyed, completed, readyChimePlayed;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        buildUi();
        List<Uri> uris = getSharedUris(getIntent());
        if (uris.isEmpty()) { pulse.error("Seleciona um PDF, imagem ou outro ficheiro."); return; }
        pulse.setFilename(HistoryStore.fileName(this, uris.get(0)));
        pulse.setSubtitle("A preparar o ficheiro…");
        io.execute(() -> {
            try {
                for (Uri uri : uris) staged.add(HistoryStore.stage(this, uri));
                handler.post(() -> {
                    if (destroyed) return;
                    pulse.start(PulseView.Mode.WAIT);
                    pulse.setSubtitle("Aproxima do outro dispositivo");
                    if (!readyChimePlayed) {
                        readyChimePlayed = true;
                        BumpSfx.play(this, BumpSfx.Cue.READY);
                    }
                    if (Permissions.granted(this)) begin();
                    else requestPermissions(Permissions.missing(this), 14);
                });
            } catch (Exception e) { handler.post(() -> pulse.error("Não consegui abrir o ficheiro: " + e.getMessage())); }
        });
    }
    private List<Uri> getSharedUris(Intent intent) {
        List<Uri> found = new ArrayList<>();
        if (intent == null) return found;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) found.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (uris != null) found.addAll(uris);
        }
        if (found.isEmpty() && intent.getClipData() != null) {
            for (int i = 0; i < intent.getClipData().getItemCount(); i++) {
                Uri uri = intent.getClipData().getItemAt(i).getUri();
                if (uri != null) found.add(uri);
            }
        }
        return found;
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] p, int[] results) {
        super.onRequestPermissionsResult(requestCode, p, results);
        if (requestCode == 14) {
            if (Permissions.granted(this)) begin();
            else pulse.error("Autoriza os dispositivos próximos para enviar.");
        }
    }
    private void begin() {
        if (started || destroyed) return;
        started = true;
        link = new NearbyLink(this, true, this);
        beacon = new Beacon(this);
        link.startSendDiscovery();
    }
    private TextView tappable(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        t.setPadding(18, 8, 18, 8);
        return t;
    }
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        pulse = new PulseView(this, true);
        pulse.start(PulseView.Mode.PREPARING);
        root.addView(pulse, new FrameLayout.LayoutParams(-1, -1));
        cancel = tappable("Cancelar");
        FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(-2, 54, Gravity.TOP | Gravity.END);
        top.setMargins(0, 72, 26, 0);
        root.addView(cancel, top);
        cancel.setOnClickListener(v -> finish());
        manual = tappable("Enviar sem gesto");
        manual.setAlpha(.45f);
        FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(-2, 56, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bottom.bottomMargin = 66;
        root.addView(manual, bottom);
        manual.setOnClickListener(v -> sendNow());
        setContentView(root);
    }
    private void sendNow() {
        if (triggered || link == null || !link.isAuthenticated()) {
            if (pulse != null) pulse.setSubtitle("À espera do tablet emparelhado…");
            return;
        }
        triggered = true;
        if (beacon != null) beacon.stopScan();
        pulse.setSubtitle("A enviar…");
        pulse.start(PulseView.Mode.SEND);
        BumpSfx.play(this, BumpSfx.Cue.SEND);
        link.sendFiles(new ArrayList<>(staged));
    }
    @Override public void onPairRequest(String endpointId, String name, String digits) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Primeiro emparelhamento")
                .setMessage("Confirma que o tablet mostra o mesmo código.\n\n" + name + "\nCódigo: " + digits + "\n\nSó vais precisar desta confirmação uma vez.")
                .setPositiveButton("Os códigos coincidem", (d, w) -> { if (link != null) link.approvePairing(endpointId, true); })
                .setNegativeButton("Cancelar", (d, w) -> { if (link != null) link.approvePairing(endpointId, false); finish(); })
                .setCancelable(false).show();
    }
    @Override public void onReady(String device) {
        pulse.setSubtitle("Aproxima o telemóvel do " + device);
        manual.setAlpha(1f);
    }
    @Override public void onBeacon(String token) {
        if (beacon == null || triggered) return;
        if (!beacon.listen(token, this::sendNow)) pulse.setSubtitle("Proximidade indisponível. Podes enviar manualmente.");
    }
    @Override public void onIncoming(String name) { }
    @Override public void onIncomingSaved(String name, Uri uri) { }
    @Override public void onSending(String name) { pulse.setFilename(name); }
    @Override public void onSendingFinished() {
        completed = true;
        pulse.sent();
        BumpSfx.play(this, BumpSfx.Cue.SENT);
        handler.postDelayed(this::finish, 1350);
    }
    @Override public void onProgress(int pct) { pulse.setSubtitle("A transferir · " + pct + "%"); }
    @Override public void onDisconnected() {
        if (!completed) {
            pulse.error("A ligação caiu antes de terminar.");
            BumpSfx.play(this, BumpSfx.Cue.ERROR);
        }
    }
    @Override public void onStatus(String message) { pulse.setSubtitle(message); }
    @Override public void onError(String message) {
        pulse.error(message);
        BumpSfx.play(this, BumpSfx.Cue.ERROR);
    }
    @Override protected void onDestroy() {
        destroyed = true;
        if (beacon != null) beacon.stopScan();
        if (link != null) link.close();
        io.shutdown();
        super.onDestroy();
    }
}
