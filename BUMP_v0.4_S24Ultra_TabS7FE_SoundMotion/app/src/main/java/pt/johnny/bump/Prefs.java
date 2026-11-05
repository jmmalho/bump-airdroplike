package pt.johnny.bump;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.security.SecureRandom;
import java.util.UUID;

final class Prefs {
    private Prefs() {}
    static SharedPreferences p(Context c) { return c.getSharedPreferences("bump_v3", Context.MODE_PRIVATE); }
    static String localId(Context c) {
        String s = p(c).getString("id", "");
        if (!s.isEmpty()) return s;
        s = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        p(c).edit().putString("id", s).apply();
        return s;
    }
    static String name(Context c) { return localId(c) + "|" + Build.MODEL; }
    static String peerId(String name) {
        if (name == null || !name.contains("|")) return "";
        String id = name.substring(0, name.indexOf('|'));
        return id.matches("[a-f0-9]{12}") ? id : "";
    }
    static String peerLabel(String name) {
        if (name == null) return "Dispositivo";
        int i = name.indexOf('|');
        return i >= 0 ? name.substring(i + 1) : name;
    }
    static String key(Context c, String peer) { return p(c).getString("peer_" + peer, ""); }
    static void trust(Context c, String peer, String secret) {
        p(c).edit().putString("peer_" + peer, secret).apply();
    }
    static boolean isReceiving(Context c) { return p(c).getBoolean("receive_enabled", false); }
    static void setReceiving(Context c, boolean on) { p(c).edit().putBoolean("receive_enabled", on).apply(); }
    static boolean sounds(Context c) { return p(c).getBoolean("sound_fx", true); }
    static void sounds(Context c, boolean enabled) { p(c).edit().putBoolean("sound_fx", enabled).apply(); }
    static boolean haptics(Context c) { return p(c).getBoolean("haptic_fx", true); }
    static void haptics(Context c, boolean enabled) { p(c).edit().putBoolean("haptic_fx", enabled).apply(); }
    static int sensitivity(Context c) { return p(c).getInt("rssi_threshold", -58); }
    static void sensitivity(Context c, int threshold) { p(c).edit().putInt("rssi_threshold", threshold).apply(); }
    static void forgetAll(Context c) {
        SharedPreferences.Editor edit = p(c).edit();
        for (String k : p(c).getAll().keySet()) {
            if (k.startsWith("peer_")) edit.remove(k);
        }
        edit.apply();
    }
    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(java.util.Locale.ROOT, "%02x", x & 0xff));
        return s.toString();
    }
}
