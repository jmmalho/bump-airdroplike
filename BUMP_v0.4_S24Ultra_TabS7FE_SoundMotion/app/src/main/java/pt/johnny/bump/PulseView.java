package pt.johnny.bump;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * Transparent system-like canvas overlay: no screen replacement or opaque background.
 * WAIT: blue aura assembles + document fades in.
 * SEND: document rises and vanishes past top edge.
 * RECEIVE: document enters from the bottom.
 * SENT / RECEIVED: distinct confirmation glows (only after actual transfer/save).
 */
final class PulseView extends View {
    enum Mode { PREPARING, WAIT, SEND, RECEIVE, SENT, RECEIVED, ERROR }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean sender;
    private Mode mode = Mode.WAIT;
    private String filename = "Documento";
    private String subtitle = "Aproxima do outro dispositivo";
    private String errorText = "Não foi possível concluir";
    private float phase = 0, travel = 0, appear = 0;
    private ValueAnimator idle, action, intro;
    private static final int BLUE = Color.rgb(38, 125, 255);
    private static final int WHITE = Color.WHITE;
    private static final int PALE = Color.rgb(210, 232, 255);
    private static final PathInterpolator SMOOTH = new PathInterpolator(.22f, .68f, .14f, 1f);

    PulseView(Context c, boolean isSender) {
        super(c);
        sender = isSender;
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        idle = ValueAnimator.ofFloat(0, 1);
        idle.setDuration(3100);
        idle.setRepeatCount(ValueAnimator.INFINITE);
        idle.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
        idle.start();
        intro = ValueAnimator.ofFloat(0, 1);
        intro.setDuration(710);
        intro.setInterpolator(SMOOTH);
        intro.addUpdateListener(a -> { appear = (float) a.getAnimatedValue(); invalidate(); });
        intro.start();
    }

    void setFilename(String name) { filename = name == null ? "Documento" : name; invalidate(); }
    void setSubtitle(String value) { subtitle = value == null ? "" : value; invalidate(); }
    void start(Mode next) {
        mode = next;
        travel = 0;
        if (next == Mode.WAIT) {
            if (intro != null) intro.cancel();
            appear = 0;
            intro = ValueAnimator.ofFloat(0, 1);
            intro.setDuration(710);
            intro.setInterpolator(SMOOTH);
            intro.addUpdateListener(a -> { appear = (float) a.getAnimatedValue(); invalidate(); });
            intro.start();
        }
        if (action != null) action.cancel();
        if (next == Mode.SEND || next == Mode.RECEIVE || next == Mode.SENT || next == Mode.RECEIVED) {
            action = ValueAnimator.ofFloat(0, 1);
            action.setDuration(next == Mode.SEND ? 900 : next == Mode.RECEIVE ? 1050 : 760);
            action.setInterpolator(SMOOTH);
            action.addUpdateListener(a -> { travel = (float) a.getAnimatedValue(); invalidate(); });
            action.start();
        }
        invalidate();
    }
    void sent() { start(Mode.SENT); }
    void received(String name) { setFilename(name); start(Mode.RECEIVED); }
    void error(String value) {
        errorText = value == null ? "Tenta novamente" : value;
        start(Mode.ERROR);
    }
    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }
    private void color(int c) {
        paint.setColor(c); paint.setShader(null); paint.setStyle(Paint.Style.FILL);
        paint.clearShadowLayer(); paint.setStrokeWidth(1);
    }
    private void text(Canvas c, String value, float x, float y, float size, int shade, boolean bold) {
        paint.setShader(null); paint.setStyle(Paint.Style.FILL); paint.setColor(shade);
        if (mode == Mode.WAIT) paint.setAlpha((int)(Color.alpha(shade) * appear));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(dp(size));
        String display = value == null ? "" : value;
        float max = Math.max(dp(80), getWidth() - dp(65));
        while (paint.measureText(display) > max && display.length() > 5)
            display = display.substring(0, display.length() - 2) + "…";
        paint.setShadowLayer(dp(7), 0, dp(2), Color.argb(110, 3, 30, 68));
        c.drawText(display, x, y, paint);
        paint.clearShadowLayer();
    }
    private void drawAura(Canvas c, float x, float y) {
        float breath = (float) Math.sin(phase * 2 * Math.PI);
        float glow = (mode == Mode.SENT || mode == Mode.RECEIVED) ? (1f + .42f * (1f-travel)) : 1f;
        float radius = dp((130 + breath * 9) * (.70f + appear * .30f) * glow);
        paint.setShader(new RadialGradient(x, y, radius,
                new int[]{Color.argb(78, 26, 128, 255), Color.argb(34, 66, 182, 255), Color.TRANSPARENT},
                new float[]{0, .42f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, radius, paint);
        paint.setShader(null);
        for (int i = 0; i < 3; i++) {
            color(Color.argb((int)((44-i*12)*appear), 105, 193, 255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(i == 0 ? 1.4f : .9f));
            c.drawCircle(x, y, dp((68+i*29 + phase*15) * (.82f+appear*.18f)), paint);
        }
        paint.setStyle(Paint.Style.FILL);
        // Slow orbiting points make the aura feel alive while staying lightweight.
        if (mode == Mode.WAIT) {
            for (int i = 0; i < 6; i++) {
                double theta = 2 * Math.PI * (i / 6.0 + phase*.22);
                float r = dp(88 + (i%3)*15);
                color(Color.argb((int)(appear*(80 + 40*Math.sin(theta)*Math.sin(theta))), 164, 221, 255));
                c.drawCircle(x + (float)Math.cos(theta)*r, y + (float)Math.sin(theta)*r, dp(i%2==0?2:1.3f), paint);
            }
        }
    }
    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight(), cx = w/2, cy = h*.46f;
        // Only a faint tint; the app behind remains visible.
        color(Color.argb(9, 6, 18, 47)); c.drawRect(0, 0, w, h, paint);
        drawAura(c, cx, cy);
        float docY = cy + dp(2), docOpacity = appear;
        if (mode == Mode.SEND) {
            docY = cy - travel*(h*.59f + dp(85));
            docOpacity *= Math.max(0, 1 - Math.max(0, travel-.65f)/.35f);
            drawTrail(c, cx, docY + dp(45), travel, false);
        } else if (mode == Mode.RECEIVE) {
            docY = h + dp(70) - travel*(h - cy + dp(70));
            drawTrail(c, cx, docY + dp(45), travel, true);
        } else if (mode == Mode.SENT || mode == Mode.ERROR || mode == Mode.PREPARING) {
            docOpacity = 0;
        } else if (mode == Mode.WAIT) {
            docY += dp((1-appear)*19) + dp((float)Math.sin(phase*2*Math.PI)*3);
        }
        drawDocument(c, cx, docY, docOpacity);
        if (mode == Mode.SENT || mode == Mode.RECEIVED) drawSuccess(c, cx, cy, travel, mode == Mode.RECEIVED);
        float y = cy + dp(104);
        if (mode == Mode.PREPARING) {
            text(c, "A preparar…", cx, y, 15, WHITE, true);
            text(c, filename, cx, y+dp(26), 12, PALE, false);
        } else if (mode == Mode.WAIT) {
            text(c, "Pronto a partilhar", cx, y, 16, WHITE, true);
            text(c, filename, cx, y+dp(26), 12, PALE, false);
            text(c, subtitle, cx, y+dp(54), 11, Color.rgb(163, 206, 255), false);
        } else if (mode == Mode.SEND) {
            text(c, "A enviar", cx, y, 16, WHITE, true);
            text(c, filename, cx, y+dp(27), 12, PALE, false);
        } else if (mode == Mode.RECEIVE) {
            text(c, "A receber", cx, y, 16, WHITE, true);
            text(c, subtitle, cx, y+dp(26), 12, PALE, false);
        } else if (mode == Mode.SENT) {
            text(c, "Enviado", cx, y, 16, WHITE, true);
            text(c, filename, cx, y+dp(27), 12, PALE, false);
        } else if (mode == Mode.RECEIVED) {
            text(c, "Recebido", cx, y, 16, WHITE, true);
            text(c, filename, cx, y+dp(26), 12, PALE, false);
            text(c, "Guardado em Downloads/BUMP", cx, y+dp(54), 11, Color.rgb(163,206,255), false);
        } else {
            text(c, sender ? "Não foi possível enviar" : "Não foi possível receber", cx, y, 16, WHITE, true);
            text(c, errorText, cx, y+dp(29), 12, PALE, false);
        }
    }
    private void drawTrail(Canvas c, float x, float y, float fraction, boolean enter) {
        if (fraction <= .02f) return;
        float opacity = enter ? Math.max(0, 1-fraction*.75f) : Math.max(0, fraction*.7f);
        paint.setShader(new LinearGradient(x, y, x, y+dp(140),
                new int[]{Color.argb((int)(155*opacity), 73, 197, 255), Color.argb((int)(45*opacity), 38, 125, 255), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP));
        c.drawRoundRect(new RectF(x-dp(2), y, x+dp(2), y+dp(140)), dp(2), dp(2), paint);
        paint.setShader(null);
    }
    private void drawSuccess(Canvas c, float x, float y, float progress, boolean receiving) {
        float ringR = dp(53 + progress*112);
        color(Color.argb((int)(115*(1-progress)), receiving ? 132 : 80, 215, 255));
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2.5f*(1-progress)+.4f));
        c.drawCircle(x, y, ringR, paint); paint.setStyle(Paint.Style.FILL);
        // When receiving, retain the file and draw a compact check over it.
        if (!receiving) {
            color(Color.argb((int)(230*Math.min(1,progress*3)), 232, 247, 255));
            c.drawCircle(x, y, dp(37 + (1-progress)*4), paint);
        }
        paint.setColor(BLUE); paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(receiving ? 3.8f : 5f));
        float s = receiving ? .57f : 1f;
        Path check = new Path();
        check.moveTo(x-dp(16*s), y+dp(1*s));
        check.lineTo(x-dp(4*s), y+dp(13*s));
        check.lineTo(x+dp(18*s), y-dp(13*s));
        c.drawPath(check, paint); paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 7; i++) {
            double angle = i*Math.PI*2/7 + .3;
            float r = dp(75 + progress*35);
            color(Color.argb((int)(130*(1-progress)), 148, 223, 255));
            c.drawCircle(x+(float)Math.cos(angle)*r, y+(float)Math.sin(angle)*r, dp(2.2f*(1-progress)+.6f), paint);
        }
    }
    private void drawDocument(Canvas c, float x, float y, float alpha) {
        if (alpha <= .01f) return;
        c.save(); c.translate(x,y);
        float width=dp(72), height=dp(90);
        color(Color.argb((int)(alpha*37),80,180,255));
        c.drawRoundRect(new RectF(-width/2-dp(8),-height/2-dp(8),width/2+dp(8),height/2+dp(8)),dp(18),dp(18),paint);
        color(Color.argb((int)(alpha*245),245,250,255));
        paint.setShadowLayer(dp(20),0,dp(4),Color.argb((int)(alpha*125),26,91,246));
        c.drawRoundRect(new RectF(-width/2,-height/2,width/2,height/2),dp(13),dp(13),paint);
        paint.clearShadowLayer();
        color(Color.argb((int)(alpha*255),174,210,255));
        Path fold=new Path();
        fold.moveTo(width/2-dp(24),-height/2);
        fold.lineTo(width/2-dp(24),-height/2+dp(24));
        fold.lineTo(width/2,-height/2+dp(24));
        fold.close(); c.drawPath(fold,paint);
        color(Color.argb((int)(alpha*220),70,146,245));
        for (int i=0;i<3;i++)
            c.drawRoundRect(new RectF(-dp(22),-dp(5)+i*dp(14),dp(19-i*3),-dp(2)+i*dp(14)),dp(2),dp(2),paint);
        c.restore();
    }
    @Override protected void onDetachedFromWindow() {
        if (intro != null) intro.cancel();
        if (idle != null) idle.cancel();
        if (action != null) action.cancel();
        super.onDetachedFromWindow();
    }
}
