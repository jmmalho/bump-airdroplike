package pt.johnny.bump;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Sound + animation demo available even without a second device. Never transfers files. */
public final class PreviewActivity extends Activity {
    private PulseView pulse;
    private final Handler timeline = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        GlassStyle.prepareTranslucentActivity(this);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);
        pulse = new PulseView(this, true);
        pulse.setFilename("apontamentos.pdf");
        pulse.setSubtitle("Aproxima do outro dispositivo");
        root.addView(pulse, new FrameLayout.LayoutParams(-1, -1));

        TextView close = label("Fechar");
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(85), dp(46), Gravity.TOP | Gravity.END);
        closeParams.setMargins(0, dp(65), dp(24), 0);
        root.addView(close, closeParams);
        close.setBackground(GlassStyle.secondary(this, 22));
        close.setElevation(dp(3));
        close.setOnClickListener(v -> finish());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(11), dp(13), dp(11), dp(11));
        controls.setBackground(GlassStyle.panel(this, 26));
        controls.setElevation(dp(9));

        TextView label = label("PRÉ-VISUALIZAÇÃO · SEM TRANSFERÊNCIA");
        label.setTextSize(10);
        label.setAlpha(.75f);
        label.setGravity(Gravity.CENTER);
        controls.addView(label, new LinearLayout.LayoutParams(-1, dp(34)));
        TextView show = label("▶  Ver sequência AirDrop Wave");
        show.setTextSize(14);
        show.setTextColor(Color.WHITE);
        show.setBackground(GlassStyle.action(this, 16));
        show.setElevation(dp(4));
        LinearLayout.LayoutParams demoParams = new LinearLayout.LayoutParams(-1, dp(48));
        demoParams.setMargins(dp(3), dp(0), dp(3), dp(11));
        controls.addView(show, demoParams);
        show.setOnClickListener(v -> playDemo());
        LinearLayout top = new LinearLayout(this);
        addControl(top, "Pronto", PulseView.Mode.WAIT, BumpSfx.Cue.READY);
        addControl(top, "Enviar", PulseView.Mode.SEND, BumpSfx.Cue.SEND);
        addControl(top, "Chegada", PulseView.Mode.RECEIVE, BumpSfx.Cue.INCOMING);
        controls.addView(top);
        LinearLayout bottom = new LinearLayout(this);
        addControl(bottom, "Enviado", PulseView.Mode.SENT, BumpSfx.Cue.SENT);
        addControl(bottom, "Recebido", PulseView.Mode.RECEIVED, BumpSfx.Cue.RECEIVED);
        controls.addView(bottom);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        panelParams.setMargins(dp(17), 0, dp(17), dp(34));
        root.addView(controls, panelParams);
        setContentView(root);
    }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }
    private TextView label(String content) {
        TextView t = new TextView(this);
        t.setText(content); t.setTextSize(13); t.setTextColor(GlassStyle.INK);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        return t;
    }
    private void addControl(LinearLayout line, String title, PulseView.Mode mode, BumpSfx.Cue sound) {
        TextView button = label(title);
        button.setTextSize(12);
        button.setPadding(dp(4),dp(9),dp(4),dp(9));
        GradientDrawable shape = new GradientDrawable();
        button.setBackground(GlassStyle.secondary(this, 15));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(43), 1);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        line.addView(button, params);
        button.setOnClickListener(v -> {
            timeline.removeCallbacksAndMessages(null);
            pulse.start(mode);
            pulse.setFilename("apontamentos.pdf");
            if (mode == PulseView.Mode.RECEIVE) pulse.setSubtitle("apontamentos.pdf");
            if (mode == PulseView.Mode.WAIT) pulse.setSubtitle("Aproxima do outro dispositivo");
            BumpSfx.play(this, sound);
        });
    }

    /** Visual-only, deterministic demonstration of both sides; does not contact any device. */
    private void playDemo() {
        timeline.removeCallbacksAndMessages(null);
        pulse.setFilename("apontamentos.pdf");
        pulse.setSubtitle("Aproxima do outro dispositivo");
        pulse.start(PulseView.Mode.WAIT);
        BumpSfx.play(this, BumpSfx.Cue.READY);
        timeline.postDelayed(() -> {
            pulse.start(PulseView.Mode.SEND);
            BumpSfx.play(this, BumpSfx.Cue.SEND);
        }, 1050);
        timeline.postDelayed(() -> {
            pulse.sent();
            BumpSfx.play(this, BumpSfx.Cue.SENT);
        }, 2390);
        timeline.postDelayed(() -> {
            pulse.setSubtitle("apontamentos.pdf");
            pulse.start(PulseView.Mode.RECEIVE);
            BumpSfx.play(this, BumpSfx.Cue.INCOMING);
        }, 3380);
        timeline.postDelayed(() -> {
            pulse.received("apontamentos.pdf");
            BumpSfx.play(this, BumpSfx.Cue.RECEIVED);
        }, 4690);
    }
    @Override protected void onDestroy() {
        timeline.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
