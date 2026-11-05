package pt.johnny.bump;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Private files opened via explicit, temporary grant to external viewers. */
public final class BumpProvider extends ContentProvider {
    private File resolve(Uri uri) throws FileNotFoundException {
        String id = uri.getLastPathSegment();
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) throw new FileNotFoundException();
        File sent = new File(getContext().getFilesDir(), "sent/" + id);
        File received = new File(getContext().getFilesDir(), "received/" + id);
        File file = sent.isFile() ? sent : received;
        if (!file.isFile()) throw new FileNotFoundException();
        return file;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        return Prefs.p(getContext()).getString("fileMime_" + uri.getLastPathSegment(), "application/octet-stream");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File file = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            String name = Prefs.p(getContext()).getString("fileName_" + file.getName(), file.getName());
            c.addRow(new Object[]{name, file.length()});
            return c;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Apenas leitura");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
