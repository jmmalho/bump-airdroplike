package pt.johnny.bump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.content.pm.PackageManager;

/** Best-effort restart on boot, subject to Android and Samsung battery restrictions. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Prefs.isReceiving(context)) return;
        if (Build.VERSION.SDK_INT >= 31 && context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) return;
        try { ReceiverService.start(context); }
        catch (Exception ignored) { /* user can re-enable from BUMP if Android blocks it */ }
    }
}
