package pt.johnny.bump;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

final class HistoryStore {
    private HistoryStore() {}
    static final String AUTHORITY = "pt.johnny.bump.files";
    static String clean(String name) {
        if (name == null || name.isBlank()) return "ficheiro";
        return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").substring(0, Math.min(name.length(), 120));
    }
    static String fileName(Context c, Uri uri) {
        if (uri == null) return "ficheiro";
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) return clean(q.getString(0));
        } catch (Exception ignored) { }
        return clean(uri.getLastPathSegment());
    }
    static String mime(Context c, Uri uri, String filename) {
        String m = c.getContentResolver().getType(uri);
        if (m != null && !m.equals("application/octet-stream")) return m;
        int dot = filename.lastIndexOf('.');
        if (dot >= 0) m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
        return m == null ? "application/octet-stream" : m;
    }
    static void copy(InputStream source, OutputStream target) throws IOException {
        try (InputStream in = source; OutputStream out = target) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.flush();
        }
    }
    static final class LocalFile {
        final File file;
        final String name, mime;
        final Uri uri;
        LocalFile(File file, String name, String mime, Uri uri) {
            this.file = file; this.name = name; this.mime = mime; this.uri = uri;
        }
    }
    static LocalFile stage(Context c, Uri source) throws IOException {
        String name = fileName(c, source), mime = mime(c, source, name);
        String uuid = UUID.randomUUID().toString();
        File dir = new File(c.getFilesDir(), "sent");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Não foi possível criar a pasta");
        File file = new File(dir, uuid);
        InputStream in = c.getContentResolver().openInputStream(source);
        if (in == null) throw new IOException("Não foi possível ler o ficheiro");
        copy(in, new FileOutputStream(file));
        Prefs.p(c).edit().putString("fileName_" + uuid, name)
                .putString("fileMime_" + uuid, mime).apply();
        Uri openUri = new Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(uuid).build();
        return new LocalFile(file, name, mime, openUri);
    }
    static Uri saveReceived(Context c, Uri from, String filename, String mime) throws IOException {
        filename = clean(filename);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, mime);
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BUMP");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            ContentResolver r = c.getContentResolver();
            Uri target = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("Não foi possível criar o ficheiro em Downloads");
            boolean ok = false;
            try {
                InputStream in = r.openInputStream(from);
                OutputStream out = r.openOutputStream(target);
                if (in == null || out == null) throw new IOException("Transferência indisponível");
                copy(in, out);
                ContentValues done = new ContentValues();
                done.put(MediaStore.Downloads.IS_PENDING, 0);
                r.update(target, done, null, null);
                ok = true;
                return target;
            } finally { if (!ok) r.delete(target, null, null); }
        }
        File dir = new File(c.getFilesDir(), "received");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Não foi possível criar pasta");
        String uuid = UUID.randomUUID().toString();
        File dest = new File(dir, uuid);
        InputStream in = c.getContentResolver().openInputStream(from);
        if (in == null) throw new IOException("Não foi possível ler o ficheiro recebido");
        copy(in, new FileOutputStream(dest));
        Prefs.p(c).edit().putString("fileName_" + uuid, filename)
                .putString("fileMime_" + uuid, mime).apply();
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(uuid).build();
    }
    static synchronized void add(Context c, String direction, String name, String mime, Uri uri, String device) {
        try {
            JSONArray old = new JSONArray(Prefs.p(c).getString("history", "[]"));
            JSONArray items = new JSONArray();
            JSONObject obj = new JSONObject();
            obj.put("direction", direction);
            obj.put("name", name);
            obj.put("mime", mime);
            obj.put("uri", uri.toString());
            obj.put("device", device);
            obj.put("time", System.currentTimeMillis());
            items.put(obj);
            for (int i = 0; i < Math.min(old.length(), 99); i++) items.put(old.optJSONObject(i));
            Prefs.p(c).edit().putString("history", items.toString()).apply();
        } catch (Exception ignored) { }
    }
    static JSONArray list(Context c) {
        try { return new JSONArray(Prefs.p(c).getString("history", "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }
}
