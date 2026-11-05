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
 * BUMP's new AirDrop / Quick Share-inspired material.
 * A real, legible frosted sheet on top of the current app; not an almost invisible canvas.
 * WAIT: the glass appears and the document floats; SEND exits upwards; RECEIVE rises from
 * below. SENT and RECEIVED are only invoked by the actual completion callbacks.
 */
final class PulseView extends View {
    enum Mode { PREPARING, WAIT, SEND, RECEIVE, SENT, RECEIVED, ERROR }

    private static final int INK = Color.rgb(25, 55, 93);
    private static final int MUTED = Color.rgb(92, 125, 161);
    private static final int BLUE = Color.rgb(38, 125, 255);
    private static final PathInterpolator EASE = new PathInterpolator(.20f, .75f, .14f, 1f);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF sheet = new RectF();
    private final boolean sender;
    private Mode mode = Mode.PREPARING;
    private String filename = "Documento";
    private String subtitle = "Aproxima do outro dispositivo";
    private String errorText = "Não foi possível concluir";
    private float phase, travel, appear;
    private ValueAnimator idle, action, intro;

    PulseView(Context context, boolean isSender) {
        super(context);
        sender = isSender;
        // No software rendering layer: use the GPU and avoid expensive bitmap blur loops.
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        idle = ValueAnimator.ofFloat(0f, 1f);
        idle.setDuration(3400);
        idle.setRepeatCount(ValueAnimator.INFINITE);
        idle.addUpdateListener(a -> { phase = (float) a.getAnimatedValue(); invalidate(); });
        idle.start();
        start(Mode.PREPARING);
    }

    void setFilename(String name) {
        filename = name == null || name.trim().isEmpty() ? "Documento" : name;
        invalidate();
    }

    void setSubtitle(String value) {
        subtitle = value == null ? "" : value;
        invalidate();
    }

    void start(Mode next) {
        mode = next;
        travel = 0f;
        if (intro != null) intro.cancel();
        if (action != null) action.cancel();
        if (next == Mode.WAIT || (appear < 1f && next == Mode.PREPARING)) {
            float from = next == Mode.WAIT ? .25f : 0f;
            appear = from;
            intro = ValueAnimator.ofFloat(from, 1f);
            intro.setDuration(next == Mode.WAIT ? 650 : 560);
            intro.setInterpolator(EASE);
            intro.addUpdateListener(a -> { appear = (float) a.getAnimatedValue(); invalidate(); });
            intro.start();
        } else appear = 1f;
        if (next == Mode.SEND || next == Mode.RECEIVE || next == Mode.SENT || next == Mode.RECEIVED) {
            action = ValueAnimator.ofFloat(0f, 1f);
            action.setDuration(next == Mode.SEND ? 890 : next == Mode.RECEIVE ? 980 : 720);
            action.setInterpolator(EASE);
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
    private void solid(int color) {
        p.setShader(null);
        p.setColor(color);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(dp(1));
        p.clearShadowLayer();
    }
    private void gradient(Shader shader) {
        p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); p.setShader(shader);
        p.clearShadowLayer();
    }
    private int alpha(float max, float multiplier) {
        return Math.min(255, Math.max(0, Math.round(max * Math.max(0f, Math.min(1f, multiplier)))));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width <= 0 || height <= 0) return;
        float cx = width / 2f;
        float glassW = Math.min(width - dp(34), dp(382));
        float glassH = Math.min(height - dp(108), dp(350));
        float top = (height - glassH) * .47f;
        sheet.set(cx - glassW/2f, top, cx + glassW/2f, top + glassH);
        float docX = cx;
        float docBaseY = sheet.top + Math.min(dp(133), glassH * .40f);
        float titleY = sheet.top + Math.min(dp(239), glassH * .69f);

        // Backdrop is deliberately translucent but not clear/invisible. App content survives.
        solid(Color.argb(52, 14, 29, 57));
        canvas.drawRect(0, 0, width, height, p);
        gradient(new LinearGradient(0, 0, width, height,
                Color.argb(24, 48, 97, 178), Color.argb(13, 20, 51, 101), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, p);
        p.setShader(null);

        drawGlass(canvas, appear);

        // The aura is inside the glass, while file movement can cross the screen boundary.
        canvas.save();
        Path clip = new Path();
        clip.addRoundRect(sheet, dp(34), dp(34), Path.Direction.CW);
        canvas.clipPath(clip);
        drawAura(canvas, docX, docBaseY);
        canvas.restore();

        float docY = docBaseY + (float)Math.sin(phase * Math.PI * 2) * dp(2.7f);
        float opacity = appear;
        float scale = 1f;
        if (mode == Mode.SEND) {
            docY = docBaseY - travel * (docBaseY + dp(105));
            scale = 1f - travel * .25f;
            opacity = 1f - Math.max(0f, (travel - .62f)/.38f);
            drawTail(canvas, cx, docY + dp(41), travel, false);
        } else if (mode == Mode.RECEIVE) {
            docY = height + dp(70) - travel * (height + dp(70) - docBaseY);
            scale = .86f + travel * .14f;
            opacity = Math.min(1f, travel * 2f);
            drawTail(canvas, cx, docY + dp(41), travel, true);
        } else if (mode == Mode.SENT || mode == Mode.ERROR || mode == Mode.PREPARING) {
            opacity = 0f;
        } else if (mode == Mode.WAIT) {
            docY += dp((1f - appear) * 18f);
            scale = .87f + appear * .13f;
        }
        drawDocument(canvas, docX, docY, opacity, scale);
        if (mode == Mode.SENT || mode == Mode.RECEIVED) {
            drawSuccess(canvas, docX, docBaseY, travel, mode == Mode.RECEIVED);
        }
        drawTitles(canvas, cx, titleY);
    }

    private void drawGlass(Canvas c, float progress) {
        float radius = dp(34);
        float alpha = .65f + .35f * progress;
        // Soft contact shadow, without an extra bitmap or continuous blur convolution.
        solid(Color.argb(this.alpha(47, alpha), 17, 56, 124));
        p.setShadowLayer(dp(31), 0, dp(14), Color.argb(this.alpha(57, alpha), 23, 45, 93));
        c.drawRoundRect(sheet, radius, radius, p);
        p.clearShadowLayer();
        gradient(new LinearGradient(sheet.left, sheet.top, sheet.right, sheet.bottom,
                new int[]{Color.argb(this.alpha(244, alpha), 252, 254, 255),
                        Color.argb(this.alpha(229, alpha), 221, 237, 255),
                        Color.argb(this.alpha(242, alpha), 244, 250, 255)},
                new float[]{0f, .55f, 1f}, Shader.TileMode.CLAMP));
        c.drawRoundRect(sheet, radius, radius, p);
        p.setShader(null);
        // Inner highlight + double edge give a lens-like refraction impression.
        solid(Color.argb(this.alpha(215, alpha), 255, 255, 255));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(1.25f));
        c.drawRoundRect(sheet.left + dp(.7f), sheet.top + dp(.7f), sheet.right - dp(.7f),
                sheet.bottom - dp(.7f), radius, radius, p);
        solid(Color.argb(this.alpha(45, alpha), 63, 128, 214));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(.6f));
        c.drawRoundRect(sheet.left + dp(2.4f), sheet.top + dp(2.4f), sheet.right - dp(2.4f),
                sheet.bottom - dp(2.4f), radius - dp(2), radius - dp(2), p);
        p.setStyle(Paint.Style.FILL);
        c.save();
        Path clip = new Path(); clip.addRoundRect(sheet, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        // Pale blue volume, top lip, and a subtle moving specular reflection.
        gradient(new RadialGradient(sheet.centerX()-dp(60), sheet.top+dp(25), dp(220),
                new int[]{Color.argb(this.alpha(66, alpha), 255, 255, 255),
                        Color.argb(this.alpha(21, alpha), 124, 193, 255), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP));
        c.drawRect(sheet, p);
        float reflectiveX = sheet.left + sheet.width() * (.20f + phase * .36f);
        gradient(new LinearGradient(reflectiveX-dp(55), sheet.top, reflectiveX+dp(95), sheet.bottom,
                new int[]{Color.TRANSPARENT, Color.argb(this.alpha(30, alpha), 255, 255, 255), Color.TRANSPARENT},
                null, Shader.TileMode.CLAMP));
        c.drawRect(sheet, p);
        p.setShader(null);
        c.restore();
        drawBrand(c, alpha);
    }

    private void drawBrand(Canvas c, float a) {
        float y = sheet.top + dp(37);
        solid(Color.argb(alpha(225, a), 44, 132, 252));
        c.drawCircle(sheet.centerX()-dp(31), y-dp(4), dp(4), p);
        drawText(c, "BUMP", sheet.centerX()+dp(7), y, 12, Color.argb(alpha(255, a), 51, 106, 177), true,
                sheet.width() - dp(36));
        solid(Color.argb(alpha(50, a), 95, 160, 230));
        c.drawRoundRect(sheet.left + dp(31), sheet.bottom - dp(43), sheet.right - dp(31),
                sheet.bottom - dp(42), dp(.5f), dp(.5f), p);
        // Three small transfer marks at the bottom of the material.
        for (int i=0; i<3; i++) {
            solid(Color.argb(alpha(i==1?195:84, a), 76, 145, 240));
            c.drawCircle(sheet.centerX() + dp((i-1)*12), sheet.bottom - dp(23), dp(i==1?2.4f:1.8f), p);
        }
    }

    private void drawAura(Canvas c, float x, float y) {
        float pulse = (float)Math.sin(phase * Math.PI*2);
        float success = (mode == Mode.SENT || mode == Mode.RECEIVED) ? 1f+.17f*(1f-travel) : 1f;
        float r = dp((100 + pulse * 7) * success);
        gradient(new RadialGradient(x, y, r,
                new int[]{Color.argb(alpha(80, appear), 77, 169, 255),
                        Color.argb(alpha(42, appear), 125, 202, 255), Color.TRANSPARENT},
                new float[]{0f, .43f, 1f}, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, r, p);
        p.setShader(null);
        for (int i = 0; i < 2; i++) {
            solid(Color.argb(alpha(61 - i*19, appear), 63, 160, 248));
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(i==0?1.2f:.75f));
            c.drawCircle(x, y, dp(64 + i*28 + phase*9), p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private void drawDocument(Canvas c, float x, float y, float opacity, float scale) {
        if (opacity < .012f) return;
        c.save(); c.translate(x, y); c.scale(scale, scale);
        float w = dp(76), h = dp(94);
        solid(Color.argb(alpha(38, opacity), 35, 125, 255));
        c.drawRoundRect(-w/2-dp(8),-h/2-dp(8),w/2+dp(8),h/2+dp(8),dp(20),dp(20),p);
        gradient(new LinearGradient(0,-h/2,0,h/2,
                Color.argb(alpha(255, opacity),255,255,255),
                Color.argb(alpha(255, opacity),233,245,255),Shader.TileMode.CLAMP));
        p.setShadowLayer(dp(17),0,dp(5),Color.argb(alpha(79, opacity),36,102,217));
        c.drawRoundRect(-w/2,-h/2,w/2,h/2,dp(12),dp(12),p);
        p.clearShadowLayer();p.setShader(null);
        solid(Color.argb(alpha(255,opacity),143,198,255));
        Path fold = new Path();
        fold.moveTo(w/2-dp(23),-h/2);
        fold.lineTo(w/2-dp(23),-h/2+dp(23));
        fold.lineTo(w/2,-h/2+dp(23));
        fold.close(); c.drawPath(fold,p);
        solid(Color.argb(alpha(235,opacity),49,132,245));
        for (int i=0;i<3;i++) {
            c.drawRoundRect(-dp(23),-dp(4)+i*dp(14),dp(22-i*3),-dp(1)+i*dp(14),dp(2),dp(2),p);
        }
        c.restore();
    }

    private void drawTail(Canvas c, float x, float y, float fraction, boolean entering) {
        if (fraction < .03f) return;
        float a = entering ? Math.max(0f, 1f - fraction*.7f) : fraction;
        gradient(new LinearGradient(x, y, x, y+dp(100),
                new int[]{Color.argb(alpha(167,a),62,164,255),Color.argb(alpha(60,a),112,204,255),Color.TRANSPARENT},
                null,Shader.TileMode.CLAMP));
        c.drawRoundRect(x-dp(2),y,x+dp(2),y+dp(100),dp(2),dp(2),p);
        p.setShader(null);
    }

    private void drawSuccess(Canvas c,float x,float y,float fraction,boolean receiving) {
        if (!receiving) {
            solid(Color.argb(alpha(245, Math.min(1f, fraction*4f)),246,251,255));
            c.drawCircle(x,y,dp(36),p);
        }
        solid(Color.argb(alpha(145,1f-fraction),64,146,253));
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2.6f));
        c.drawCircle(x,y,dp(48+fraction*75),p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.rgb(37,119,250));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(receiving?4f:4.8f));
        p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        Path mark = new Path();
        mark.moveTo(x-dp(15),y+dp(1));mark.lineTo(x-dp(4),y+dp(12));mark.lineTo(x+dp(18),y-dp(13));
        c.drawPath(mark,p);p.setStyle(Paint.Style.FILL);
    }

    private void drawTitles(Canvas c, float cx, float base) {
        String title, lineTwo, lineThree = "";
        switch (mode) {
            case PREPARING: title="A preparar…";lineTwo=filename;break;
            case WAIT: title="Pronto a partilhar";lineTwo=filename;lineThree=subtitle;break;
            case SEND: title="A enviar";lineTwo=filename;break;
            case RECEIVE: title="A receber";lineTwo=subtitle;break;
            case SENT: title="Enviado";lineTwo=filename;break;
            case RECEIVED: title="Recebido";lineTwo=filename;lineThree="Guardado em Downloads/BUMP";break;
            default: title=sender?"Não foi possível enviar":"Não foi possível receber";lineTwo=errorText;
        }
        float max = sheet.width() - dp(51);
        drawText(c,title,cx,base,17,INK,true,max);
        drawText(c,lineTwo,cx,base+dp(28),13,MUTED,false,max);
        if (!lineThree.isEmpty()) drawText(c,lineThree,cx,base+dp(52),11,Color.rgb(69,131,201),false,max);
    }

    private void drawText(Canvas c,String content,float x,float y,float size,int color,boolean bold,float max) {
        solid(color);
        p.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);p.setTextSize(dp(size));
        String value = content == null ? "" : content;
        if (p.measureText(value)>max) {
            String original=value;int limit=original.length();
            while (limit>1 && p.measureText(original.substring(0,limit)+"…")>max) limit--;
            value=original.substring(0,limit)+"…";
        }
        p.setAlpha(alpha(255,appear));
        c.drawText(value,x,y,p);
        p.setAlpha(255);
    }

    @Override protected void onDetachedFromWindow() {
        if (idle!=null) idle.cancel();
        if (intro!=null) intro.cancel();
        if (action!=null) action.cancel();
        super.onDetachedFromWindow();
    }
}
