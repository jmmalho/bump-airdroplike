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

/**
 * BUMP 0.6 · Surface Handoff
 *
 * The transfer is suggested by one continuous light field, not by floating
 * decorative rings. The halo emerges at the TOP edge, the interface bends,
 * and the receiver sees the continuation of the same visual motif.
 *
 * Important: this view refracts only pixels drawn by BUMP. Android does not
 * allow an ordinary overlay to sample the live contents of other apps.
 */
final class PulseView extends View {
    enum Mode { PREPARING, WAIT, SEND, RECEIVE, SENT, RECEIVED, ERROR }

    private static final int INK = Color.rgb(30, 43, 65);
    private static final int SECONDARY = Color.rgb(101, 116, 141);
    private static final int ACCENT = Color.rgb(37, 123, 248);
    private static final float PI = (float) Math.PI;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF card = new RectF();
    private final RectF icon = new RectF();
    private final LiquidRefraction refraction = new LiquidRefraction();
    private final boolean sender;
    private Mode mode = Mode.PREPARING;
    private String filename = "Documento";
    private String subtitle = "Aproxima do outro dispositivo";
    private String errorText = "Tenta novamente";
    private float phase = 1f;
    private float reveal = 1f;
    private float breath = 0f;
    private ValueAnimator motion, entry, idle;

    PulseView(Context context, boolean isSender) {
        super(context);
        sender = isSender;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    void setFilename(String name) {
        filename = name == null || name.trim().isEmpty() ? "Documento" : name;
        invalidate();
    }
    void setSubtitle(String value) { subtitle = value == null ? "" : value; invalidate(); }

    void start(Mode next) {
        if (motion != null) { motion.cancel(); motion = null; }
        if (entry != null) { entry.cancel(); entry = null; }
        refraction.disable(this);
        mode = next;
        phase = next == Mode.PREPARING || next == Mode.WAIT ? 1f : 0f;
        reveal = (next == Mode.WAIT || next == Mode.PREPARING) ? 0f : 1f;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            phase = 1f;
            reveal = 1f;
            invalidate();
            return;
        }
        if (next == Mode.WAIT || next == Mode.PREPARING) {
            entry = ValueAnimator.ofFloat(0f, 1f);
            entry.setDuration(500L);
            entry.setInterpolator(v -> 1f - (float) Math.pow(1f - v, 3));
            entry.addUpdateListener(a -> { reveal = (float) a.getAnimatedValue(); invalidate(); });
            entry.start();
        } else {
            long duration = next == Mode.SEND || next == Mode.RECEIVE ? 1480L : 780L;
            motion = ValueAnimator.ofFloat(0f, 1f);
            motion.setDuration(duration);
            motion.setInterpolator(v -> v);
            motion.addUpdateListener(a -> {
                phase = (float) a.getAnimatedValue();
                if (mode == Mode.SEND || mode == Mode.RECEIVE)
                    refraction.update(this, phase, true);
                invalidate();
            });
            motion.start();
        }
        invalidate();
    }
    void sent() { start(Mode.SENT); }
    void received(String name) { setFilename(name); start(Mode.RECEIVED); }
    void error(String message) {
        errorText = message == null ? "Tenta novamente" : message;
        start(Mode.ERROR);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (idle == null && ValueAnimator.areAnimatorsEnabled()) {
            idle = ValueAnimator.ofFloat(0f, 1f);
            idle.setDuration(4500L);
            idle.setRepeatCount(ValueAnimator.INFINITE);
            idle.setInterpolator(v -> v);
            idle.addUpdateListener(a -> {
                breath = (float) a.getAnimatedValue();
                if (mode == Mode.WAIT || mode == Mode.PREPARING) invalidate();
            });
            idle.start();
        }
    }
    @Override protected void onDetachedFromWindow() {
        refraction.disable(this);
        if (idle != null) { idle.cancel(); idle = null; }
        if (motion != null) { motion.cancel(); motion = null; }
        if (entry != null) { entry.cancel(); entry = null; }
        super.onDetachedFromWindow();
    }

    private float dp(float x) { return x * getResources().getDisplayMetrics().density; }
    private static float clamp(float x) { return Math.max(0f, Math.min(1f, x)); }
    private static float smooth(float x) { x = clamp(x); return x*x*(3f - 2f*x); }
    private static float easeOut(float x) { x = clamp(x); return 1f-(1f-x)*(1f-x)*(1f-x); }
    private static float window(float t,float a,float b,float c,float d) {
        return smooth((t-a)/(b-a))*(1f-smooth((t-c)/(d-c)));
    }
    private static int opacity(float alpha) { return Math.round(clamp(alpha)*255f); }
    private void color(int argb) {
        paint.reset();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setColor(argb);
        paint.setStyle(Paint.Style.FILL);
    }
    private void gradient(Shader shader) { color(Color.WHITE); paint.setShader(shader); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth(), height = getHeight();
        if (width < 1 || height < 1) return;
        final float mid = width*.5f;
        final float top = Math.max(dp(52f), height*.095f);
        final boolean flowing = mode == Mode.SEND || mode == Mode.RECEIVE;

        // Context is always visible: no full-screen opaque activity backdrop.
        color(Color.argb(41, 10, 20, 42));
        canvas.drawRect(0, 0, width, height, paint);
        gradient(new LinearGradient(0, 0, width, height,
                new int[]{Color.argb(19, 58, 103, 186), Color.TRANSPARENT,
                        Color.argb(18, 8, 19, 44)}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, paint);
        paint.setShader(null);

        float amplitude = flowing ? window(phase,.015f,.2f,.76f,.99f)
                : mode == Mode.WAIT ? .30f * (.7f + .3f*(float)Math.sin(2f*PI*breath))
                : mode == Mode.RECEIVED || mode == Mode.SENT ? .36f*(1f-easeOut(phase)) : .14f;
        drawEdgeLight(canvas, width, height, mid, top, amplitude, flowing);
        if (flowing) drawRefractionFront(canvas, width, height, mid, top);
        if (mode == Mode.SENT || mode == Mode.RECEIVED) drawCompletion(canvas, mid, top);
        drawFileMotion(canvas, width, height, mid, top);
        drawStatusCapsule(canvas, width, height, mid);
    }

    private void drawEdgeLight(Canvas canvas,float w,float h,float x,float top,float strength,boolean moving) {
        float t = phase;
        float compact = moving ? window(t,.03f,.18f,.47f,.85f) : strength;
        float haloRadius = Math.max(dp(175), w*.57f);
        // Peripheral light scatters naturally through the glass, no hard discs.
        gradient(new RadialGradient(x,top,haloRadius,
                new int[]{Color.argb(opacity(strength*.43f), 222, 242, 255),
                        Color.argb(opacity(strength*.27f), 81, 163, 254),
                        Color.argb(opacity(strength*.09f), 41, 109, 219),Color.TRANSPARENT},
                new float[]{0f,.20f,.51f,1f},Shader.TileMode.CLAMP));
        canvas.drawCircle(x,top,haloRadius,paint);paint.setShader(null);
        float innerR = dp(19) + (moving ? dp(13)*(1f-smooth(t)) : dp(5));
        gradient(new RadialGradient(x,top,innerR,
                new int[]{Color.argb(opacity(compact*.93f),255,255,255),
                        Color.argb(opacity(compact*.67f),220,244,255),
                        Color.argb(opacity(compact*.1f),97,164,255),Color.TRANSPARENT},
                new float[]{0f,.22f,.64f,1f},Shader.TileMode.CLAMP));
        canvas.drawCircle(x,top,innerR,paint);paint.setShader(null);
        if (moving) {
            float stretch = window(t,.19f,.32f,.54f,.77f);
            gradient(new LinearGradient(x,top-dp(55),x,top+dp(155),
                    new int[]{Color.TRANSPARENT,Color.argb(opacity(stretch*.28f),255,255,255),
                            Color.argb(opacity(stretch*.10f),66,168,255),Color.TRANSPARENT},
                    null,Shader.TileMode.CLAMP));
            path.reset();
            path.moveTo(x-dp(11),top-dp(31));
            path.cubicTo(x-dp(24),top+dp(37),x-dp(47),top+dp(77),x-dp(79),top+dp(157));
            path.lineTo(x+dp(79),top+dp(157));
            path.cubicTo(x+dp(47),top+dp(77),x+dp(24),top+dp(37),x+dp(11),top-dp(31));
            path.close(); canvas.drawPath(path,paint);paint.setShader(null);
        }
    }

    private void drawRefractionFront(Canvas canvas,float w,float h,float x,float top) {
        // A refractive wave travels across the WHOLE surface. The actual pixel
        // displacement is performed by LiquidRefraction on Android 13+.
        float t = phase;
        float diag = (float) Math.hypot(w*.5f, h-top);
        float radius = dp(8) + easeOut(clamp((t-.075f)/.83f))*diag;
        float strength = window(t,.03f,.20f,.75f,.99f);
        for (int i=0;i<3;i++) {
            float r = radius - dp(i*15f);
            if (r <= 0) continue;
            float light = strength*(1f-i*.24f);
            color(Color.argb(opacity(light*(i==0?.24f:.13f)),158,214,255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(i==0?11f:5f));
            canvas.drawCircle(x,top,r,paint);
            color(Color.argb(opacity(light*(i==0?.68f:.30f)),238,251,255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(i==0?1.6f:.8f));
            canvas.drawCircle(x,top,r,paint);
        }
        // The wave is brightest locally rather than washing out the whole UI.
        if (strength > .005f) {
            float focusY = top + radius*.53f;
            gradient(new RadialGradient(x,focusY,dp(110),
                    new int[]{Color.argb(opacity(strength*.09f),255,255,255),
                            Color.TRANSPARENT},null,Shader.TileMode.CLAMP));
            canvas.drawCircle(x,focusY,dp(110),paint);
            paint.setShader(null);
        }
    }

    private void drawFileMotion(Canvas canvas,float w,float h,float mid,float top) {
        if (mode != Mode.SEND && mode != Mode.RECEIVE) return;
        float t=phase;
        float launch = easeOut(clamp((t-.09f)/.74f));
        float pointY = h*.665f;
        float y;
        float scale;
        float alpha;
        if (mode == Mode.SEND) {
            y = pointY + dp(15f)*window(t,.01f,.08f,.15f,.29f)
                    - (pointY-top+dp(35))*launch;
            alpha = 1f - smooth((t-.68f)/.3f);
            scale = 1f-.54f*launch;
        } else {
            y = top-dp(30)+(pointY-top+dp(30))*launch;
            alpha = smooth((t-.12f)/.28f)*(1f-smooth((t-.8f)/.18f));
            scale = .46f+.54f*launch;
        }
        if (alpha <= .003f) return;
        canvas.save();canvas.translate(mid,y);canvas.scale(scale,scale);
        float size=dp(51);
        color(Color.argb(opacity(.17f*alpha),35,126,252));
        paint.setShadowLayer(dp(19),0,dp(9),Color.argb(opacity(.23f*alpha),25,108,237));
        canvas.drawRoundRect(-size*.5f,-size*.5f,size*.5f,size*.5f,dp(14),dp(14),paint);
        paint.clearShadowLayer();
        gradient(new LinearGradient(-size*.5f,-size*.5f,size*.5f,size*.5f,
                new int[]{Color.argb(opacity(alpha),255,255,255),
                        Color.argb(opacity(alpha),225,240,255)},null,Shader.TileMode.CLAMP));
        canvas.drawRoundRect(-size*.5f,-size*.5f,size*.5f,size*.5f,dp(13),dp(13),paint);
        paint.setShader(null);
        label(canvas,typeLabel(),0,dp(3),11,Color.argb(opacity(alpha),37,119,237),true,size-dp(10),Paint.Align.CENTER);
        canvas.restore();
    }

    private void drawCompletion(Canvas canvas,float x,float top) {
        float t=phase;
        float a=1f-smooth((t-.32f)/.68f);
        float r=dp(22)+dp(106)*easeOut(t);
        color(Color.argb(opacity(a*.45f),226,247,255));
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3.2f));
        canvas.drawCircle(x,top,r,paint);
        float draw=smooth(t/.65f);
        color(Color.argb(opacity(smooth(t/.25f)),255,255,255));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3.6f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        path.reset();path.moveTo(x-dp(12),top+dp(1));
        path.lineTo(x-dp(12)+dp(10)*Math.min(1f,draw*2f),top+dp(1)+dp(9)*Math.min(1f,draw*2f));
        if (draw>.5f)path.lineTo(x-dp(2)+dp(22)*(draw-.5f)*2f,top+dp(10)-dp(27)*(draw-.5f)*2f);
        canvas.drawPath(path,paint);
    }

    private void drawStatusCapsule(Canvas canvas,float w,float h,float mid) {
        float width=Math.min(w-dp(34),dp(363));
        float height=dp(113);
        float y = Math.max(dp(158),h*.68f);
        float opacity=reveal;
        if (mode == Mode.SEND) opacity=1f-smooth((phase-.10f)/.33f);
        if (mode == Mode.RECEIVE) opacity=smooth((phase-.56f)/.31f);
        if (mode == Mode.SENT || mode == Mode.RECEIVED) opacity=smooth(phase/.28f);
        if (opacity<.006f) return;
        float translate=dp(12)*(1f-opacity);
        card.set(mid-width*.5f,y+translate,mid+width*.5f,y+height+translate);
        float radius=dp(28);
        // The capsule is physical glass: mostly opaque, thin white rim, cool shadow.
        color(Color.argb(opacity(opacity*.23f),14,33,69));
        paint.setShadowLayer(dp(26),0,dp(12),Color.argb(opacity(opacity*.33f),4,26,62));
        canvas.drawRoundRect(card,radius,radius,paint);paint.clearShadowLayer();
        gradient(new LinearGradient(card.left,card.top,card.right,card.bottom,
                new int[]{Color.argb(opacity(opacity*.97f),255,255,255),
                        Color.argb(opacity(opacity*.91f),235,243,253),
                        Color.argb(opacity(opacity*.97f),245,250,255)},
                new float[]{0f,.59f,1f},Shader.TileMode.CLAMP));
        canvas.drawRoundRect(card,radius,radius,paint);paint.setShader(null);
        color(Color.argb(opacity(opacity*.9f),255,255,255));
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.1f));
        canvas.drawRoundRect(card,radius,radius,paint);
        // A restrained specular arc, like light grazing polished glass.
        path.reset();path.moveTo(card.left+dp(24),card.top+dp(8));
        path.cubicTo(mid-dp(45),card.top+dp(2),mid+dp(41),card.top+dp(3),card.right-dp(23),card.top+dp(9));
        color(Color.argb(opacity(opacity*.72f),255,255,255));
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1.8f));
        canvas.drawPath(path,paint);

        float lx=card.left+dp(48), cy=card.top+height*.5f;
        icon.set(lx-dp(23),cy-dp(24),lx+dp(23),cy+dp(24));
        gradient(new LinearGradient(icon.left,icon.top,icon.right,icon.bottom,
                Color.rgb(248,252,255),Color.rgb(216,233,252),Shader.TileMode.CLAMP));
        canvas.drawRoundRect(icon,dp(13),dp(13),paint);paint.setShader(null);
        color(Color.rgb(71,142,244));
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));
        canvas.drawRoundRect(icon,dp(13),dp(13),paint);
        label(canvas,typeLabel(),lx,cy+dp(4),10,ACCENT,true,dp(40),Paint.Align.CENTER);

        float textX=card.left+dp(89);
        String title;
        String detail;
        switch (mode) {
            case PREPARING: title="A preparar";detail=filename;break;
            case WAIT: title="Pronto a partilhar";detail=filename;break;
            case SEND: title="A enviar";detail=filename;break;
            case RECEIVE: title="A receber";detail=filename;break;
            case SENT: title="Enviado";detail=filename;break;
            case RECEIVED: title="Recebido";detail=filename;break;
            default: title="Falha na transferência";detail=errorText;
        }
        float remaining=card.right-dp(18)-textX;
        label(canvas,title,textX,card.top+dp(47),16,INK,true,remaining,Paint.Align.LEFT);
        label(canvas,detail,textX,card.top+dp(72),12,SECONDARY,false,remaining,Paint.Align.LEFT);
        if (mode == Mode.WAIT && !subtitle.isEmpty()) {
            label(canvas,subtitle,mid,card.bottom+dp(27),11,Color.argb(opacity(opacity),244,250,255),false,w-dp(36),Paint.Align.CENTER);
        }
    }

    private String typeLabel() {
        String ext=filename.toLowerCase(java.util.Locale.ROOT);
        if(ext.endsWith(".pdf")) return "PDF";
        if(ext.endsWith(".apk")) return "APK";
        if(ext.endsWith(".jpg")||ext.endsWith(".jpeg")||ext.endsWith(".png")||ext.endsWith(".webp"))return "IMG";
        if(ext.endsWith(".mp4")||ext.endsWith(".mov"))return "VID";
        return "DOC";
    }
    private void label(Canvas canvas,String value,float x,float y,float size,int color,boolean semibold,float maxWidth,Paint.Align align) {
        color(color);
        paint.setTypeface(Typeface.create(semibold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        paint.setTextSize(dp(size));
        paint.setTextAlign(align);
        String text=value==null?"":value;
        if (paint.measureText(text)>maxWidth) {
            int end=text.length();
            while(end>1&&paint.measureText(text.substring(0,end)+"…")>maxWidth)end--;
            text=text.substring(0,end)+"…";
        }
        canvas.drawText(text,x,y,paint);
    }
}
