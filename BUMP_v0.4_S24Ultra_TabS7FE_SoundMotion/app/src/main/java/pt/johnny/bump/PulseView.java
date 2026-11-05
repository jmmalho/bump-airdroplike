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
 * BUMP Motion v0.5 — GPU-friendly, original two-screen handoff choreography.
 * Outgoing files travel toward the sender's TOP edge; incoming files rise through
 * the receiver's BOTTOM edge. Both play the same blue wave motif without needing
 * synchronized clocks or access to the other device's screen coordinates.
 *
 * All rendering is procedural Canvas. No bitmap blur, network, camera, mic or
 * 3rd-party animation engine; audio and delivery callbacks remain independent.
 */
final class PulseView extends View {
    enum Mode { PREPARING, WAIT, SEND, RECEIVE, SENT, RECEIVED, ERROR }

    private static final int INK = Color.rgb(28, 54, 91);
    private static final int MUTED = Color.rgb(94, 124, 161);
    private static final int BLUE = Color.rgb(39, 120, 248);
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF glass = new RectF();
    private final Path path = new Path();
    private final boolean sender;
    private Mode mode = Mode.PREPARING;
    private String filename = "Documento";
    private String subtitle = "Aproxima do outro dispositivo";
    private String errorText = "Não foi possível concluir";
    private float breathe, progress = 1f, reveal = 0f;
    private ValueAnimator idle, action, intro;
    private final LiquidRefraction liquid = new LiquidRefraction();

    PulseView(Context context, boolean isSender) {
        super(context);
        sender = isSender;
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        start(Mode.PREPARING);
    }

    void setFilename(String name) {
        filename = name == null || name.trim().isEmpty() ? "Documento" : name;
        invalidate();
    }
    void setSubtitle(String line) { subtitle = line == null ? "" : line; invalidate(); }

    void start(Mode next) {
        if (intro != null) intro.cancel();
        if (action != null) action.cancel();
        mode = next;
        liquid.disable(this);
        progress = (next == Mode.WAIT || next == Mode.PREPARING) ? 1f : 0f;
        if (!ValueAnimator.areAnimatorsEnabled()) { reveal = 1f; progress = 1f; invalidate(); return; }

        if (next == Mode.WAIT || next == Mode.PREPARING) {
            reveal = next == Mode.WAIT ? .44f : 0f;
            intro = ValueAnimator.ofFloat(reveal, 1f);
            intro.setDuration(next == Mode.WAIT ? 670 : 480);
            intro.addUpdateListener(a -> { reveal = (float) a.getAnimatedValue(); invalidate(); });
            intro.start();
        } else reveal = 1f;

        if (next == Mode.SEND || next == Mode.RECEIVE || next == Mode.SENT || next == Mode.RECEIVED) {
            action = ValueAnimator.ofFloat(0f, 1f);
            action.setDuration(next == Mode.SEND ? 1140 : next == Mode.RECEIVE ? 1200 : 760);
            // Linear timeline with different physically-motivated easing for each element.
            action.setInterpolator(input -> input);
            action.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                if (mode == Mode.SEND || mode == Mode.RECEIVE)
                    liquid.update(this, progress, mode == Mode.RECEIVE);
                invalidate();
            });
            action.start();
        }
        invalidate();
    }
    void sent() { start(Mode.SENT); }
    void received(String name) { setFilename(name); start(Mode.RECEIVED); }
    void error(String message) { errorText = message == null ? "Tenta novamente" : message; start(Mode.ERROR); }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (idle == null && ValueAnimator.areAnimatorsEnabled()) {
            idle = ValueAnimator.ofFloat(0f, 1f);
            idle.setDuration(4200);
            idle.setRepeatCount(ValueAnimator.INFINITE);
            idle.setInterpolator(input -> input);
            idle.addUpdateListener(a -> { breathe = (float) a.getAnimatedValue(); invalidate(); });
            idle.start();
        }
    }
    @Override protected void onDetachedFromWindow() {
        liquid.disable(this);
        if (idle != null) { idle.cancel(); idle = null; }
        if (action != null) { action.cancel(); action = null; }
        if (intro != null) { intro.cancel(); intro = null; }
        super.onDetachedFromWindow();
    }

    private float dp(float n) { return n * getResources().getDisplayMetrics().density; }
    private static float clamp(float t) { return Math.max(0f, Math.min(1f, t)); }
    private static float smooth(float t) { t = clamp(t); return t*t*(3f-2f*t); }
    private static float out(float t) { t = clamp(t); return 1f-(float)Math.pow(1f-t, 3); }
    private static float in(float t) { t = clamp(t); return t*t*t; }
    private static float interval(float t, float from, float to) { return clamp((t-from)/(to-from)); }
    private static float bell(float t, float a, float b, float c, float d) {
        return smooth(interval(t,a,b)) * (1f-smooth(interval(t,c,d)));
    }
    private int alpha(int a, float fraction) { return Math.round(clamp(fraction)*a); }
    private void fill(int color) {
        p.setShader(null); p.setColor(color); p.setStyle(Paint.Style.FILL);
        p.setStrokeWidth(dp(1)); p.clearShadowLayer();
    }
    private void shader(Shader s) { p.setShader(s); p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE); p.clearShadowLayer(); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        final float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        final float cx = w/2f;
        final float gw = Math.min(w-dp(34), dp(386));
        final float gh = Math.min(h-dp(155), dp(352));
        glass.set(cx-gw/2f, (h-gh)*.48f, cx+gw/2f, (h-gh)*.48f+gh);
        final float cy = glass.top + Math.min(dp(137), gh*.405f);
        final float titleY = glass.top + gh*.71f;

        drawBackdrop(c,w,h,cx,cy);
        drawGlass(c,cx,cy);

        // The light path is deliberately allowed beyond the frosted sheet and
        // the device display bounds: sender exits TOP, receiver enters BOTTOM.
        if (mode == Mode.SEND) drawHandoff(c,cx,cy,w,h,false);
        else if (mode == Mode.RECEIVE) drawHandoff(c,cx,cy,w,h,true);
        else drawBreathingRings(c,cx,cy);

        float fileY = cy + (float)Math.sin(breathe*TWO_PI)*dp(2.4f);
        float scale = .95f + .05f*reveal;
        float opacity = reveal;
        float rotation = 0;
        if (mode == Mode.SEND) {
            float launch = out(interval(progress,.15f,.94f));
            float squash = bell(progress,.02f,.09f,.17f,.27f);
            fileY = cy + dp(8)*squash - launch*(cy+dp(135));
            scale = (1f-.09f*squash)*(1f-.27f*launch);
            opacity = 1f-smooth(interval(progress,.67f,.97f));
            rotation = -6f*bell(progress,.11f,.23f,.62f,.91f);
        } else if (mode == Mode.RECEIVE) {
            float rise = out(interval(progress,.17f,.88f));
            fileY = h + dp(106) - rise*(h + dp(106) - cy);
            scale = .70f+.30f*rise + .055f*bell(progress,.74f,.89f,.93f,1f);
            opacity = smooth(interval(progress,.19f,.47f));
            rotation = 7f*(1f-rise);
        } else if (mode == Mode.SENT || mode == Mode.ERROR || mode == Mode.PREPARING) opacity = 0f;
        else if (mode == Mode.RECEIVED) scale = .95f+.05f*out(progress);
        else { fileY += (1f-reveal)*dp(19); }

        drawFile(c,cx,fileY,opacity,scale,rotation);
        if (mode == Mode.SENT || mode == Mode.RECEIVED) drawConfirmation(c,cx,cy,mode == Mode.RECEIVED);
        drawLabels(c,cx,titleY);
    }

    private void drawBackdrop(Canvas c,float w,float h,float cx,float cy) {
        fill(Color.argb(52,12,30,65));
        c.drawRect(0,0,w,h,p);
        shader(new LinearGradient(0,0,w,h,Color.argb(23,65,137,231),Color.argb(7,33,56,111),Shader.TileMode.CLAMP));
        c.drawRect(0,0,w,h,p); p.setShader(null);
        if (mode != Mode.SEND && mode != Mode.RECEIVE) return;
        float a = bell(progress,.08f,.3f,.66f,1f);
        float y = mode == Mode.SEND ? dp(26) : h-dp(26);
        shader(new RadialGradient(cx,y,Math.max(w,dp(190)),new int[]{Color.argb(alpha(70,a),59,163,255),Color.argb(alpha(28,a),74,142,250),Color.TRANSPARENT},null,Shader.TileMode.CLAMP));
        c.drawCircle(cx,y,Math.max(w,dp(190)),p);p.setShader(null);
    }

    private void drawGlass(Canvas c,float cx,float cy) {
        float r = dp(34);
        float op = .78f+.22f*reveal;
        float expansion = (1f-reveal)*dp(20);
        RectF rect = new RectF(glass.left,glass.top+expansion,glass.right,glass.bottom+expansion);
        fill(Color.argb(alpha(65,op),20,67,147));
        p.setShadowLayer(dp(29),0,dp(14),Color.argb(alpha(68,op),26,72,150));
        c.drawRoundRect(rect,r,r,p);p.clearShadowLayer();
        shader(new LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
                new int[]{Color.argb(alpha(248,op),253,255,255),Color.argb(alpha(227,op),220,238,255),Color.argb(alpha(247,op),248,252,255)},
                new float[]{0f,.55f,1f},Shader.TileMode.CLAMP));
        c.drawRoundRect(rect,r,r,p);p.setShader(null);

        c.save();path.reset();path.addRoundRect(rect,r,r,Path.Direction.CW);c.clipPath(path);
        float shimmer = .5f+.5f*(float)Math.sin(breathe*TWO_PI);
        shader(new RadialGradient(cx-dp(79),rect.top+dp(40),dp(230),
                new int[]{Color.argb(alpha(62,op),255,255,255),Color.argb(alpha(27,op),89,170,255),Color.TRANSPARENT},null,Shader.TileMode.CLAMP));
        c.drawRect(rect,p);p.setShader(null);
        float spec = rect.left+rect.width()*(.22f+.28f*shimmer);
        shader(new LinearGradient(spec-dp(75),rect.top,spec+dp(80),rect.bottom,
                new int[]{Color.TRANSPARENT,Color.argb(alpha(29,op),255,255,255),Color.TRANSPARENT},
                null,Shader.TileMode.CLAMP));c.drawRect(rect,p);p.setShader(null);
        c.restore();

        fill(Color.argb(alpha(227,op),255,255,255));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.2f));
        c.drawRoundRect(rect.left+dp(.8f),rect.top+dp(.8f),rect.right-dp(.8f),rect.bottom-dp(.8f),r,r,p);
        fill(Color.argb(alpha(41,op),67,138,239));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(.7f));
        c.drawRoundRect(rect.left+dp(2.4f),rect.top+dp(2.4f),rect.right-dp(2.4f),rect.bottom-dp(2.4f),r-dp(2),r-dp(2),p);
        p.setStyle(Paint.Style.FILL);
        // Tiny native-feeling header and transfer-direction indicator.
        fill(Color.argb(alpha(228,op),48,137,249));
        c.drawCircle(cx-dp(34),rect.top+dp(37),dp(3.7f),p);
        text(c,"BUMP",cx+dp(7),rect.top+dp(41),12,Color.argb(alpha(245,op),55,107,175),true,rect.width()-dp(36));
        fill(Color.argb(alpha(54,op),95,155,232));
        c.drawRoundRect(rect.left+dp(32),rect.bottom-dp(43),rect.right-dp(32),rect.bottom-dp(42),dp(1),dp(1),p);
        for (int i=0;i<3;i++) {
            float active = .35f+.65f*clamp((float)Math.sin(breathe*TWO_PI+i*1.3f)*.5f+.5f);
            fill(Color.argb(alpha(174,op*active),47,130,247));
            c.drawCircle(cx+dp((i-1)*12),rect.bottom-dp(24),dp(i==1?2.5f:1.8f),p);
        }
    }

    private void drawBreathingRings(Canvas c,float cx,float cy) {
        float activity = mode == Mode.WAIT ? reveal : mode == Mode.RECEIVED ? 1f : mode == Mode.ERROR ? .4f : .68f;
        float pulse = .5f+.5f*(float)Math.sin(breathe*TWO_PI);
        shader(new RadialGradient(cx,cy,dp(118),
                new int[]{Color.argb(alpha(83,activity),86,173,255),Color.argb(alpha(52,activity),97,186,255),Color.TRANSPARENT},
                new float[]{0f,.43f,1f},Shader.TileMode.CLAMP));
        c.drawCircle(cx,cy,dp(118),p);p.setShader(null);
        for (int i=0;i<3;i++) {
            float f=(breathe+i/3f)%1f;
            float r=dp(59+f*54);
            float a=activity*(1f-smooth(f))*(i==0?.60f:.38f);
            fill(Color.argb(alpha(124,a),52,153,255));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.35f-.52f*f));
            c.drawCircle(cx,cy,r,p);
        }
        p.setStyle(Paint.Style.FILL);
        fill(Color.argb(alpha(53,activity*(.6f+.4f*pulse)),36,123,248));
        c.drawCircle(cx,cy,dp(55),p);
    }

    /** Shared luminous ribbon: launches upward from sender, enters from bottom on receiver. */
    private void drawHandoff(Canvas c,float cx,float cy,float w,float h,boolean incoming) {
        float t = progress;
        float entry = incoming ? smooth(interval(t,.03f,.31f)) : smooth(interval(t,.1f,.34f));
        float envelope = bell(t,.02f,.24f,.78f,1f);
        float edgeY = incoming ? h-dp(9) : dp(9);
        float centerY = incoming ? h-(h-cy)*out(interval(t,.18f,.83f)) : cy-(cy+dp(108))*out(interval(t,.18f,.92f));
        float energy = incoming ? bell(t,.01f,.2f,.73f,.99f) : bell(t,.10f,.26f,.76f,.99f);

        // Wide pearlescent energy wave at the edge and two delayed follow-up waves.
        for (int j=0;j<3;j++) {
            float delay=j*.14f;
            float wave = smooth(interval(t, .08f+delay, .43f+delay));
            float y = incoming ? h+dp(48)-wave*dp(156) : -dp(48)+wave*dp(156);
            float a=energy*(1f-j*.23f);
            drawWaveLine(c,cx,y,w,a,incoming,j);
        }
        // Directional thread is a finite-distance glow with a narrow bright spine.
        float ribbonAlpha = entry*energy;
        if (ribbonAlpha>.005f) {
            float y1=incoming?centerY:cy;
            float y2=incoming?h+dp(35):centerY;
            float left=cx-dp(43),right=cx+dp(43);
            shader(new LinearGradient(cx,y1,cx,y2,
                    new int[]{Color.argb(alpha(3,ribbonAlpha),68,167,255),Color.argb(alpha(106,ribbonAlpha),71,164,255),Color.argb(alpha(9,ribbonAlpha),71,164,255)},
                    new float[]{0f,.56f,1f},Shader.TileMode.CLAMP));
            path.reset();path.moveTo(left,y1);path.quadTo(cx-dp(15),(.5f*(y1+y2)),cx-dp(8),y2);
            path.lineTo(cx+dp(8),y2);path.quadTo(cx+dp(15),(.5f*(y1+y2)),right,y1);path.close();
            c.drawPath(path,p);p.setShader(null);
            fill(Color.argb(alpha(167,ribbonAlpha),119,212,255));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.7f));
            c.drawLine(cx,y1,cx,y2,p);p.setStyle(Paint.Style.FILL);
        }
        // Soft portal glow at the device boundary.
        shader(new RadialGradient(cx,edgeY,Math.max(dp(130),w*.58f),
                new int[]{Color.argb(alpha(90,envelope),43,153,255),Color.argb(alpha(29,envelope),84,188,255),Color.TRANSPARENT},
                null,Shader.TileMode.CLAMP));
        c.drawCircle(cx,edgeY,Math.max(dp(130),w*.58f),p);p.setShader(null);
        // Small deterministic motes, no randomness / allocation every frame.
        for (int i=0;i<9;i++) {
            float f=(i*.23f+t*1.7f)%1f;
            float spread=(i%2==0?1f:-1f)*dp(19+(i*19)%84);
            float yy=incoming ? h-dp(f*260) : dp(f*260);
            float aa=energy*(1f-f)*(.44f+.56f*(i%3)/2f);
            fill(Color.argb(alpha(160,aa),72,167,255));
            c.drawCircle(cx+spread,yy,dp(.9f+(i%3)*.45f),p);
        }
    }

    private void drawWaveLine(Canvas c,float cx,float y,float width,float amount,boolean incoming,int index) {
        if (amount<.005f) return;
        float span=Math.min(width*.54f,dp(280));
        float lift=dp((index==0?26:13)*(incoming?-1f:1f));
        path.reset();path.moveTo(cx-span,y);
        path.cubicTo(cx-span*.57f,y+lift,cx-span*.34f,y-lift,cx,y);
        path.cubicTo(cx+span*.34f,y+lift,cx+span*.57f,y-lift,cx+span,y);
        fill(Color.argb(alpha(130,amount),91,187,255));
        p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(dp(index==0?5.5f:2.7f));c.drawPath(path,p);
        fill(Color.argb(alpha(223,amount),220,248,255));p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(index==0?1.7f:.95f));c.drawPath(path,p);
        p.setStyle(Paint.Style.FILL);
    }

    private void drawFile(Canvas c,float x,float y,float opacity,float scale,float degrees) {
        if (opacity<.01f) return;
        c.save();c.translate(x,y);c.rotate(degrees);c.scale(scale,scale);
        float fw=dp(80),fh=dp(98);
        fill(Color.argb(alpha(49,opacity),31,128,255));
        c.drawRoundRect(-fw/2-dp(10),-fh/2-dp(10),fw/2+dp(10),fh/2+dp(10),dp(20),dp(20),p);
        shader(new LinearGradient(0,-fh/2,0,fh/2,
                Color.argb(alpha(255,opacity),255,255,255),Color.argb(alpha(255,opacity),224,243,255),Shader.TileMode.CLAMP));
        p.setShadowLayer(dp(16),0,dp(7),Color.argb(alpha(76,opacity),35,103,208));
        c.drawRoundRect(-fw/2,-fh/2,fw/2,fh/2,dp(12),dp(12),p);
        p.clearShadowLayer();p.setShader(null);
        fill(Color.argb(alpha(246,opacity),126,187,255));
        path.reset();path.moveTo(fw/2-dp(24),-fh/2);path.lineTo(fw/2-dp(24),-fh/2+dp(24));
        path.lineTo(fw/2,-fh/2+dp(24));path.close();c.drawPath(path,p);
        for(int i=0;i<3;i++) {
            fill(Color.argb(alpha(213-i*27,opacity),51,130,245));
            c.drawRoundRect(-dp(23),dp(-4+i*15),dp(23-i*5),dp(-1+i*15),dp(2),dp(2),p);
        }
        String type="PDF";
        String lower=filename.toLowerCase(java.util.Locale.ROOT);
        if(lower.endsWith(".jpg")||lower.endsWith(".jpeg")||lower.endsWith(".png"))type="IMG";
        else if(lower.endsWith(".mp4")||lower.endsWith(".mov"))type="VID";
        else if(!lower.endsWith(".pdf"))type="FILE";
        fill(Color.argb(alpha(244,opacity),39,119,241));
        c.drawRoundRect(-dp(27),-dp(31),dp(6),-dp(13),dp(5),dp(5),p);
        text(c,type,-dp(10.5f),-dp(18),8,Color.argb(alpha(255,opacity),255,255,255),true,dp(38));
        c.restore();
    }

    private void drawConfirmation(Canvas c,float x,float y,boolean incoming) {
        float t=progress;
        float burst=out(interval(t,0f,.76f));
        float opacity=1f-smooth(interval(t,.5f,1f));
        for(int i=0;i<3;i++) {
            float f=out(interval(t,i*.09f,.73f+i*.09f));
            fill(Color.argb(alpha(140-i*28,opacity),52,148,255));
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2.6f-i*.55f));
            c.drawCircle(x,y,dp(42+f*(98+i*27)),p);
        }
        p.setStyle(Paint.Style.FILL);
        fill(Color.argb(alpha(230,smooth(interval(t,.03f,.27f))),253,255,255));
        p.setShadowLayer(dp(15),0,dp(7),Color.argb(alpha(91,1f-t*.5f),36,137,244));
        c.drawCircle(x,y,dp(37+burst*3),p);p.clearShadowLayer();
        // The tick draws progressively, not as an instant icon.
        float stroke=smooth(interval(t,.17f,.68f));
        fill(Color.rgb(36,123,245));p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(4.5f));
        p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        if(stroke<=.41f) {
            float part=stroke/.41f;
            path.moveTo(x-dp(15),y+dp(1));
            path.lineTo(x-dp(15)+dp(12)*part,y+dp(1)+dp(11)*part);
        } else {
            float part=(stroke-.41f)/.59f;
            path.moveTo(x-dp(15),y+dp(1));path.lineTo(x-dp(3),y+dp(12));
            path.lineTo(x-dp(3)+dp(23)*part,y+dp(12)-dp(27)*part);
        }
        c.drawPath(path,p);p.setStyle(Paint.Style.FILL);
    }

    private void drawLabels(Canvas c,float x,float y) {
        String title,detail,small="";
        switch(mode) {
            case PREPARING: title="A preparar…"; detail=filename; break;
            case WAIT: title="Pronto a partilhar"; detail=filename; small=subtitle; break;
            case SEND: title="A enviar"; detail=filename; small=subtitle; break;
            case RECEIVE: title="A receber"; detail=subtitle; break;
            case SENT: title="Enviado"; detail=filename; break;
            case RECEIVED: title="Recebido"; detail=filename; small="Guardado em Downloads/BUMP"; break;
            default: title=sender?"Não foi possível enviar":"Não foi possível receber";detail=errorText;
        }
        float max=glass.width()-dp(48);
        text(c,title,x,y,17,INK,true,max);
        text(c,detail,x,y+dp(28),13,MUTED,false,max);
        if(!small.isEmpty())text(c,small,x,y+dp(51),11,Color.rgb(66,130,206),false,max);
    }
    private void text(Canvas c,String source,float x,float y,float size,int color,boolean semibold,float maxWidth) {
        fill(color);
        p.setTypeface(Typeface.create(semibold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        p.setTextSize(dp(size));p.setTextAlign(Paint.Align.CENTER);
        String s=source==null?"":source;
        if(p.measureText(s)>maxWidth) {
            int len=s.length();
            while(len>1&&p.measureText(s.substring(0,len)+"…")>maxWidth)len--;
            s=s.substring(0,len)+"…";
        }
        p.setAlpha(alpha(255,reveal));c.drawText(s,x,y,p);p.setAlpha(255);
    }
}
