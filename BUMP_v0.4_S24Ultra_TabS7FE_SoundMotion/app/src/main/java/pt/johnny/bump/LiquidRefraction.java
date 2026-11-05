package pt.johnny.bump;

import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.os.Build;
import android.view.View;

/**
 * Brief, GPU-rendered elastic refraction of pixels drawn *by BUMP itself*.
 * It does NOT capture or distort other applications behind the overlay.
 * Android 13+ only, with a safe Canvas-only fallback on older devices.
 */
final class LiquidRefraction {
    private static final String SOURCE =
            "uniform shader source;\n" +
            "uniform float2 canvasSize;\n" +
            "uniform float progress;\n" +
            "uniform float edge;\n" +
            "uniform float amplitude;\n" +
            "half4 main(float2 xy) {\n" +
            "  float2 center = float2(canvasSize.x * 0.5, edge);\n" +
            "  float2 radial = xy - center;\n" +
            "  float d = length(radial);\n" +
            "  float maxR = length(canvasSize) * 1.05;\n" +
            "  float front = progress * maxR;\n" +
            "  float z = (d - front) / 44.0;\n" +
            "  float envelope = exp(-z*z) * (1.0 - progress);\n" +
            "  float displacement = sin((d - front)*0.065) * envelope * amplitude;\n" +
            "  float2 direction = radial / max(d, 1.0);\n" +
            "  return source.eval(xy + direction * displacement);\n" +
            "}\n";
    private RuntimeShader shader;
    private boolean unsupported;
    private boolean enabled;

    void update(View view, float progress, boolean entersFromBottom) {
        if (Build.VERSION.SDK_INT < 33 || unsupported || view.getWidth() < 1 || view.getHeight() < 1) return;
        try {
            if (shader == null) {
                shader = new RuntimeShader(SOURCE);
                view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "source"));
            } else if (!enabled) {
                view.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "source"));
            }
            enabled = true;
            float density = view.getResources().getDisplayMetrics().density;
            shader.setFloatUniform("canvasSize", (float) view.getWidth(), (float) view.getHeight());
            shader.setFloatUniform("edge", entersFromBottom ? (float) view.getHeight() : 0f);
            shader.setFloatUniform("progress", Math.max(0f, Math.min(1f, progress)));
            shader.setFloatUniform("amplitude", 7.5f * density);
        } catch (RuntimeException | LinkageError failure) {
            // Some GPU drivers may reject a shader. Preserve BUMP's Canvas animation.
            unsupported = true;
            disable(view);
        }
    }

    void disable(View view) {
        if (!enabled) return;
        enabled = false;
        if (Build.VERSION.SDK_INT >= 31) view.setRenderEffect(null);
    }
}
