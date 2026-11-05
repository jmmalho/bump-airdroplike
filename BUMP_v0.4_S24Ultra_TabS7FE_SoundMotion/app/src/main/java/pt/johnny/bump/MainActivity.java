package pt.johnny.bump;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Date;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
    private static WeakReference<MainActivity> resumed = new WeakReference<>(null);
    private static final int BG = Color.rgb(239, 246, 255);
    private static final int INK = Color.rgb(17, 42, 77);
    private static final int MUTED = Color.rgb(102, 124, 157);
    private static final int BLUE = Color.rgb(35, 115, 247);
    private LinearLayout body, historyPanel;
    private TextView status;
    private Button toggle;

    @Override public void onCreate(Bundle state) { super.onCreate(state); build(); }
    @Override protected void onResume() { super.onResume(); resumed = new WeakReference<>(this); refreshButtons(); showHistory(); }
    @Override protected void onPause() {
        if (resumed.get() == this) resumed.clear();
        super.onPause();
    }
    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable shape(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(size); t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return t;
    }
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(22), dp(20), dp(22), dp(20));
        c.setBackground(GlassStyle.panel(this, 26));
        c.setElevation(dp(5));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(13);
        body.addView(c, p);
        return c;
    }
    private void add(LinearLayout parent, TextView view, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top); parent.addView(view, p);
    }
    private Button button(String label, int bg) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(15);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(bg == BLUE ? Color.WHITE : INK);
        b.setBackground(bg == BLUE ? GlassStyle.action(this, 17) : GlassStyle.secondary(this, 17));
        b.setStateListAnimator(null);
        b.setElevation(0);
        b.setMinHeight(dp(54));
        return b;
    }
    private void build() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackground(GlassStyle.background(this));
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(22), dp(32), dp(22), dp(50));
        scroll.addView(body);
        setContentView(scroll);
        add(body, text("B.", 54, BLUE, true), 0);
        add(body, text("BUMP", 27, INK, true), 0);
        add(body, text("Partilha como se fosse magia.", 15, MUTED, false), 5);
        add(body, text("LIQUID GLASS  ·  BLUE EDITION", 10, BLUE, true), 13);
        LinearLayout primary = card();
        add(primary, text("Este dispositivo", 18, INK, true), 0);
        add(primary, text(Build.MODEL + "  ·  " + Prefs.localId(this), 12, MUTED, false), 8);
        status = text("Pronto para configurar", 13, MUTED, false);
        add(primary, status, 14);
        toggle = button("Ativar receção", BLUE);
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(-1, dp(55));
        toggleLp.topMargin = dp(18);
        primary.addView(toggle, toggleLp);
        toggle.setOnClickListener(v -> toggleReceiving());
        add(primary, text("Quando a receção está ativa, o Android pode mostrar um indicador discreto de serviço em segundo plano.", 12, MUTED, false), 12);

        LinearLayout settings = card();
        add(settings, text("A experiência invisível", 18, INK, true), 0);
        add(settings, text("Autoriza a sobreposição para ver a animação do documento a entrar, por cima das outras aplicações.", 13, MUTED, false), 8);
        Button overlay = button("Permitir animações sobre outras apps", Color.rgb(236, 243, 255));
        LinearLayout.LayoutParams overlayLp = new LinearLayout.LayoutParams(-1, dp(52));
        overlayLp.topMargin = dp(14);
        settings.addView(overlay, overlayLp);
        overlay.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        Button forget = button("Esquecer dispositivos emparelhados", Color.rgb(236, 243, 255));
        LinearLayout.LayoutParams forgetLp = new LinearLayout.LayoutParams(-1, dp(52));
        forgetLp.topMargin = dp(12);
        settings.addView(forget, forgetLp);
        forget.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Esquecer emparelhamentos?")
                .setMessage("Será necessário confirmar novamente o código nos dois dispositivos. Faz isto em ambos se tiveres problemas de ligação.")
                .setPositiveButton("Esquecer", (d, w) -> {
                    Prefs.forgetAll(this);
                    status.setText("Dispositivos esquecidos. Reinicia a receção para emparelhar novamente.");
                })
                .setNegativeButton("Cancelar", null).show());
        add(settings, text("Sensibilidade à proximidade", 14, INK, true), 22);
        TextView rssiHint = text("Padrão: -68 dBm. Se enviar cedo demais, sobe para -63 dBm.", 12, MUTED, false);
        add(settings, rssiHint, 6);
        SeekBar sensitivity = new SeekBar(this);
        sensitivity.setMax(30);
        sensitivity.setProgress(Prefs.sensitivity(this) + 75);
        settings.addView(sensitivity);
        sensitivity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int dBm = progress - 75;
                rssiHint.setText("Limite: " + dBm + " dBm · " + (dBm >= -53 ? "precisa de sinal muito forte" : "mais permissivo"));
                if (fromUser) Prefs.sensitivity(MainActivity.this, dBm);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        LinearLayout sound = card();
        add(sound, text("Som e movimento", 18, INK, true), 0);
        add(sound, text("Sinais sonoros originais para cada momento: pronto, envio, chegada e confirmação. Os dois Galaxy usam a mesma linguagem visual e sonora.", 13, MUTED, false), 8);
        Switch soundToggle = new Switch(this);
        soundToggle.setText("Efeitos sonoros");
        soundToggle.setTextColor(INK);
        soundToggle.setTextSize(15);
        soundToggle.setChecked(Prefs.sounds(this));
        add(sound, soundToggle, 16);
        soundToggle.setOnCheckedChangeListener((button, checked) -> Prefs.sounds(this, checked));
        Switch vibrationToggle = new Switch(this);
        vibrationToggle.setText("Vibração discreta");
        vibrationToggle.setTextColor(INK);
        vibrationToggle.setTextSize(15);
        vibrationToggle.setChecked(Prefs.haptics(this));
        add(sound, vibrationToggle, 13);
        vibrationToggle.setOnCheckedChangeListener((button, checked) -> Prefs.haptics(this, checked));
        add(sound, text("Os efeitos respeitam o modo silencioso do Android e não interrompem música a tocar.", 12, MUTED, false), 11);
        Button preview = button("Experimentar as animações e os sons", Color.rgb(236, 243, 255));
        LinearLayout.LayoutParams previewLp = new LinearLayout.LayoutParams(-1, dp(52));
        previewLp.topMargin = dp(16);
        sound.addView(preview, previewLp);
        preview.setOnClickListener(v -> startActivity(new Intent(this, PreviewActivity.class)));

        LinearLayout history = card();
        add(history, text("Ficheiros", 18, INK, true), 0);
        add(history, text("Enviados e recebidos. Toca para abrir.", 13, MUTED, false), 7);
        historyPanel = new LinearLayout(this);
        historyPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(-1, -2);
        listLp.topMargin = dp(12);
        history.addView(historyPanel, listLp);
        showHistory();
        refreshButtons();
    }
    private void toggleReceiving() {
        if (Prefs.isReceiving(this)) {
            Prefs.setReceiving(this, false);
            ReceiverService.stop(this);
            refreshButtons();
            status.setText("Receção desligada");
            return;
        }
        if (!Permissions.granted(this)) {
            requestPermissions(Permissions.missing(this), 17);
            return;
        }
        startReceiving();
    }
    private void startReceiving() {
        try {
            Prefs.setReceiving(this, true);
            ReceiverService.start(this);
            status.setText("A ativar Bluetooth e Wi-Fi…");
            refreshButtons();
        } catch (Exception e) {
            Prefs.setReceiving(this, false);
            status.setText("Não foi possível iniciar: " + e.getMessage());
        }
    }
    @Override public void onRequestPermissionsResult(int code, String[] p, int[] results) {
        super.onRequestPermissionsResult(code, p, results);
        if (code == 17) {
            if (Permissions.granted(this)) startReceiving();
            else status.setText("Autoriza Bluetooth e dispositivos próximos para receber.");
        }
    }
    private void refreshButtons() {
        if (toggle != null) toggle.setText(Prefs.isReceiving(this) ? "Parar receção" : "Ativar receção");
    }
    private void showHistory() {
        if (historyPanel == null) return;
        historyPanel.removeAllViews();
        JSONArray history = HistoryStore.list(this);
        if (history.length() == 0) { add(historyPanel, text("Os teus primeiros ficheiros vão aparecer aqui.", 13, MUTED, false), 9); return; }
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "ficheiro"), direction = item.optString("direction", "sent");
            long timestamp = item.optLong("time", 0);
            String date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(timestamp));
            String action = OpenFileActivity.buttonLabel(name, item.optString("mime", ""));
            TextView row = text(("received".equals(direction) ? "↓  " : "↑  ") + name + "\n" + date + "  ·  " + action + " ›", 14, INK, true);
            row.setPadding(dp(12), dp(13), dp(12), dp(13));
            row.setBackground(GlassStyle.secondary(this, 14));
            add(historyPanel, row, 8);
            row.setOnClickListener(v -> openItem(item));
        }
    }
    private void openItem(JSONObject item) {
        try {
            Uri uri = Uri.parse(item.getString("uri"));
            startActivity(OpenFileActivity.intent(this, uri,
                    item.optString("name", "ficheiro"),
                    item.optString("mime", "application/octet-stream")));
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Ficheiro indisponível")
                    .setMessage("Não foi possível encontrar o ficheiro no histórico.")
                    .setPositiveButton("OK", null).show();
        }
    }
    static boolean requestPairFromService(String id, String name, String digits, NearbyLink link) {
        MainActivity activity = resumed.get();
        if (activity == null || activity.isFinishing()) return false;
        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                .setTitle("Emparelhar " + name)
                .setMessage("Verifica se o S24 Ultra mostra o mesmo código de segurança:\n\n" + digits + "\n\nDepois, as ligações entre os dois serão automáticas.")
                .setPositiveButton("Os códigos coincidem", (d, w) -> link.approvePairing(id, true))
                .setNegativeButton("Recusar", (d, w) -> link.approvePairing(id, false))
                .setCancelable(false).show());
        return true;
    }
    static void updateStatus(String msg) {
        MainActivity activity = resumed.get();
        if (activity != null) activity.runOnUiThread(() -> { if (activity.status != null) activity.status.setText(msg); });
    }
    static void refreshHistory() {
        MainActivity activity = resumed.get();
        if (activity != null) activity.runOnUiThread(activity::showHistory);
    }
}
