package pt.johnny.bump;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.webkit.MimeTypeMap;
import java.util.Locale;

/**
 * Opens a completed BUMP transfer, always as a deliberate user action.
 * Delegates to the system's viewer/installer instead of copying into another app.
 * This activity is private (android:exported=false), not a file receiver.
 */
public final class OpenFileActivity extends Activity {
    private static final String URI_EXTRA = "pt.johnny.bump.extra.FILE_URI";
    private static final String NAME_EXTRA = "pt.johnny.bump.extra.FILE_NAME";
    private static final String MIME_EXTRA = "pt.johnny.bump.extra.FILE_MIME";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private Uri fileUri;
    private String filename;
    private String mime;
    private boolean waitingForInstallerPermission;

    static Intent intent(Context context, Uri uri, String name, String type) {
        return new Intent(context, OpenFileActivity.class)
                .putExtra(URI_EXTRA, uri == null ? "" : uri.toString())
                .putExtra(NAME_EXTRA, name == null ? "ficheiro" : name)
                .putExtra(MIME_EXTRA, type == null ? "" : type);
    }

    static String buttonLabel(String name, String type) {
        String resolved = resolveMime(name, type);
        if (APK_MIME.equals(resolved)) return "Instalar APK";
        if ("application/pdf".equals(resolved)) return "Abrir PDF";
        if (resolved.startsWith("image/")) return "Ver fotografia";
        if (resolved.startsWith("video/")) return "Ver vídeo";
        if (resolved.startsWith("audio/")) return "Reproduzir áudio";
        return "Abrir ficheiro";
    }

    /** Apply extension fallback when Nearby's MIME arrives as octet-stream. */
    static String resolveMime(String name, String declared) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
        String type = declared == null ? "" : declared.toLowerCase(Locale.ROOT).trim();
        // Never interpret a received APK as a benign document because metadata is inconsistent.
        if (lower.endsWith(".apk") || APK_MIME.equals(type)) return APK_MIME;
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < lower.length()) {
            String extensionType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substring(dot + 1));
            if (extensionType != null && (type.isEmpty() || type.equals("application/octet-stream") || type.equals("*/*")))
                return extensionType;
        }
        return type.isEmpty() || "*/*".equals(type) ? "application/octet-stream" : type;
    }

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String value = getIntent().getStringExtra(URI_EXTRA);
        filename = getIntent().getStringExtra(NAME_EXTRA);
        mime = resolveMime(filename, getIntent().getStringExtra(MIME_EXTRA));
        try {
            fileUri = Uri.parse(value == null ? "" : value);
            if (!"content".equals(fileUri.getScheme())) throw new SecurityException("URI inválido");
            // A content URI may be stale if user deleted the received file in Files.
            try (ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(fileUri, "r")) {
                if (fd == null) throw new IllegalStateException("Ficheiro indisponível");
            }
        } catch (Exception e) {
            error("Este ficheiro já não está disponível. Pode ter sido apagado ou movido.");
            return;
        }
        if (APK_MIME.equals(mime)) {
            new AlertDialog.Builder(this)
                    .setTitle("Instalar esta APK?")
                    .setMessage("«" + filename + "» é uma aplicação Android. Instala apenas se confiares no ficheiro e na sua origem.")
                    .setPositiveButton("Abrir instalador", (d, w) -> openInstaller())
                    .setNegativeButton("Cancelar", (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
        } else {
            openExternal();
        }
    }

    private void openInstaller() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Permitir instalação a partir do BUMP")
                    .setMessage("O Android exige esta autorização para abrir o instalador. Continua apenas se confiares no ficheiro.")
                    .setPositiveButton("Abrir definições", (d, w) -> {
                        waitingForInstallerPermission = true;
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) { error("Não consegui abrir as definições de instalação."); }
                    })
                    .setNegativeButton("Cancelar", (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
            return;
        }
        openExternal();
    }

    @Override protected void onResume() {
        super.onResume();
        if (waitingForInstallerPermission && Build.VERSION.SDK_INT >= 26) {
            waitingForInstallerPermission = false;
            if (getPackageManager().canRequestPackageInstalls()) {
                // The user already tapped "Abrir instalador" before changing the permission.
                openExternal();
            } else {
                error("A instalação não foi autorizada. O ficheiro continua guardado e podes abri-lo mais tarde no histórico.");
            }
        }
    }

    private void openExternal() {
        try {
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(fileUri, mime);
            view.setClipData(ClipData.newUri(getContentResolver(), "Ficheiro BUMP", fileUri));
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view); // Android selects its document/photo viewer or package installer.
            finish();
        } catch (ActivityNotFoundException e) {
            error("Não encontrei uma aplicação instalada para abrir este tipo de ficheiro.");
        } catch (Exception e) {
            error("Não foi possível abrir o ficheiro. Verifica se está disponível em Downloads/BUMP.");
        }
    }

    private void error(String message) {
        new AlertDialog.Builder(this)
                .setTitle("Não foi possível abrir")
                .setMessage(message)
                .setPositiveButton("OK", (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }
}
