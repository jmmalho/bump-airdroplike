package pt.johnny.bump;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.PowerManager;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * BUMP visual language: a readable, pale-blue translucent material, not an invisible overlay.
 * Android 12+ can blur cross-window content when the device permits it. The tinted material is
 * deliberately self-sufficient when blur is off (battery saver, developer settings or GPU).
 */
final class GlassStyle {
    static final int INK = Color.rgb(22, 51, 88);
    static final int MUTED = Color.rgb(94, 122, 155);
    static final int BLUE = Color.rgb(37, 119, 250);
    static final int SOFT = Color.rgb(233, 243, 255);

    private GlassStyle() { }

    static int dp(Context context, float n) {
        return Math.round(n * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable panel(Context context, float radius) {
        GradientDrawable result = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.argb(247, 255, 255, 255),
                        Color.argb(229, 233, 246, 255),
                        Color.argb(241, 250, 253, 255)});
        result.setCornerRadius(dp(context, radius));
        result.setStroke(dp(context, 1), Color.argb(225, 255, 255, 255));
        return result;
    }

    static GradientDrawable secondary(Context context, float radius) {
        GradientDrawable result = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.argb(235, 245, 250, 255), Color.argb(220, 222, 238, 255)});
        result.setCornerRadius(dp(context, radius));
        result.setStroke(dp(context, 1), Color.argb(225, 255, 255, 255));
        return result;
    }

    static GradientDrawable action(Context context, float radius) {
        GradientDrawable result = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(61, 151, 255), Color.rgb(34, 103, 233)});
        result.setCornerRadius(dp(context, radius));
        result.setStroke(dp(context, 1), Color.argb(100, 255, 255, 255));
        return result;
    }

    static GradientDrawable background(Context context) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(232, 242, 255), Color.rgb(250, 252, 255), Color.rgb(220, 235, 254)});
    }

    /** Foreground activity only: system blur is optional; the glass panel is always readable. */
    static void prepareTranslucentActivity(Activity activity) {
        Window window = activity.getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = .14f;
        if (Build.VERSION.SDK_INT >= 31 && canBlur(activity)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            params.setBlurBehindRadius(dp(activity, 17));
        }
        window.setAttributes(params);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    static boolean canBlur(Context context) {
        if (Build.VERSION.SDK_INT < 31) return false;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null && power.isPowerSaveMode()) return false;
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        return manager != null && manager.isCrossWindowBlurEnabled();
    }
}
