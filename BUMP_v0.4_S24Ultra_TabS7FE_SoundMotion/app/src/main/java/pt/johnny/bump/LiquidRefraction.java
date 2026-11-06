package pt.johnny.bump;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.os.PowerManager;
import android.content.Context;
import android.view.View;

/**
 * Elastic optical displacement of the pixels rendered by PulseView.
 * This deliberately does not and cannot read the screen of another application.
 * Rendering is delegated to the GPU via AGSL on Android 13+.
 */
final class LiquidRefraction {
    private static final String AGSL =
            "uniform shader content;\n" +
            "uniform float2 resolution;\n" +
            "uniform float front;\n" +
            "uniform float originY;\n" +
            "uniform float strength;\n" +
            "half4 main(float2 xy) {\n" +
            "  float2 origin = float2(resolution.x * 0.5, originY);\n" +
            "  float2 delta = xy - origin;\n" +
            "  float distance = length(delta);\n" +
            "  float diagonal = length(resolution);\n" +
            "  float radius = front * diagonal;\n" +
            "  float breadth = max(22.0, 45.0 * resolution.y / 850.0);\n" +
            "  float band = (distance - radius) / breadth;\n" +
            "  float lens = exp(-band * band * 0.8);\n" +
            "  float displacement = strength * lens * (sin(band * 3.2) * 0.78 + 0.22);\n" +
            "  float2 normal = delta / max(distance, 1.0);\n" +
            "  float2 sampleAt = clamp(xy - normal * displacement, float2(0.0), resolution - 1.0);\n" +
            "  half4 result = content.eval(sampleAt);\n" +
            "  return result;\n" +
            "}\n";

    private RuntimeShader shader;
    private boolean enabled;
    private boolean unsupported;

    void update(View view, float progress, boolean fromTop) {
        if (Build.VERSION.SDK_INT < 33 || unsupported || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        PowerManager power = (PowerManager) view.getContext().getSystemService(Context.POWER_SERVICE);
        if (power != null && power.isPowerSaveMode()) { disable(view); return; }
        try {
            if (shader == null) shader = new RuntimeShader(AGSL);
            if (!enabled) {
                view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader,"content"));
                enabled = true;
            }
            float t=Math.max(0f,Math.min(1f,progress));
            float eased = 1f-(1f-t)*(1f-t)*(1f-t);
            float envelope = (float)(Math.sin(Math.PI*t));
            envelope = envelope*envelope;
            float density=view.getResources().getDisplayMetrics().density;
            shader.setFloatUniform("resolution",(float)view.getWidth(),(float)view.getHeight());
            shader.setFloatUniform("front",eased);
            shader.setFloatUniform("originY", Math.max(52f*density,view.getHeight()*.095f));
            shader.setFloatUniform("strength", Math.min(13f*density, 26f)*envelope);
            view.invalidate();
        } catch (RuntimeException | LinkageError failed) {
            unsupported = true;
            disable(view);
        }
    }

    void disable(View view) {
        if (!enabled) return;
        enabled=false;
        if (Build.VERSION.SDK_INT>=31) view.setRenderEffect(null);
    }
}
