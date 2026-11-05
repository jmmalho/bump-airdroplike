package pt.johnny.bump;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import java.util.ArrayList;

final class Permissions {
    private Permissions() {}
    static String[] missing(Activity a) {
        ArrayList<String> all = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            all.add(Manifest.permission.BLUETOOTH_SCAN);
            all.add(Manifest.permission.BLUETOOTH_CONNECT);
            all.add(Manifest.permission.BLUETOOTH_ADVERTISE);
        } else {
            all.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 32)
            all.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 33) all.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        ArrayList<String> missing = new ArrayList<>();
        for (String p : all) if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
        return missing.toArray(new String[0]);
    }
    static boolean granted(Activity a) { return missing(a).length == 0; }
}
