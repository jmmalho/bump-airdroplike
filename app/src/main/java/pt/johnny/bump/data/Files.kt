package pt.johnny.bump.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Locale
import java.util.UUID

data class LocalFile(val file: File, val name: String, val mime: String, val size: Long, val openUri: Uri)

object Files {
    const val AUTHORITY = "pt.johnny.bump.files"
    const val APK_MIME = "application/vnd.android.package-archive"

    fun clean(name: String?): String {
        if (name.isNullOrBlank()) return "ficheiro"
        return name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifBlank { "ficheiro" }
    }

    fun displayName(c: Context, uri: Uri): String = runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q ->
            if (q.moveToFirst()) q.getString(0) else null
        }
    }.getOrNull()?.let(::clean) ?: clean(uri.lastPathSegment)

    fun size(c: Context, uri: Uri): Long = runCatching {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { q ->
            if (q.moveToFirst() && !q.isNull(0)) q.getLong(0) else -1L
        }
    }.getOrNull() ?: -1L

    /** Extension wins over a vague declared type; an .apk is never treated as a harmless document. */
    fun resolveMime(name: String?, declared: String?): String {
        val lower = name.orEmpty().lowercase(Locale.ROOT).trim()
        val type = declared.orEmpty().lowercase(Locale.ROOT).trim()
        if (lower.endsWith(".apk") || type == APK_MIME) return APK_MIME
        if (lower.endsWith(".pdf")) return "application/pdf"
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic"
        val dot = lower.lastIndexOf('.')
        if (dot >= 0 && dot + 1 < lower.length) {
            val ext = MimeTypeMap.getSingleton().getMimeTypeFromExtension(lower.substring(dot + 1))
            if (ext != null && (type.isEmpty() || type == "application/octet-stream" || type == "*/*")) return ext
        }
        return if (type.isEmpty() || type == "*/*") "application/octet-stream" else type
    }

    fun mime(c: Context, uri: Uri, name: String) = resolveMime(name, c.contentResolver.getType(uri))

    /** Copies a shared URI into private storage so the transfer survives the sharing app going away. */
    @Throws(IOException::class)
    fun stage(c: Context, source: Uri): LocalFile {
        val name = displayName(c, source)
        val mime = mime(c, source, name)
        val uuid = UUID.randomUUID().toString()
        val dir = File(c.filesDir, "sent").apply { mkdirs() }
        val file = File(dir, uuid)
        val input = c.contentResolver.openInputStream(source) ?: throw IOException("Não foi possível ler o ficheiro")
        input.use { i -> file.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
        writeSidecar(c, uuid, name, mime)
        return LocalFile(file, name, mime, file.length(), Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(uuid).build())
    }

    /** Saves to Downloads/BUMP via MediaStore (visible in Files), pending until fully written. */
    @Throws(IOException::class)
    fun saveReceived(c: Context, from: Uri, rawName: String, mime: String): Uri {
        val name = clean(rawName)
        val r = c.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BUMP")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Não foi possível criar o ficheiro em Transferências")
            try {
                val i = r.openInputStream(from) ?: throw IOException("Ficheiro recebido ilegível")
                val o = r.openOutputStream(target) ?: throw IOException("Destino indisponível")
                i.use { input -> o.use { output -> input.copyTo(output, 64 * 1024) } }
                r.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                return target
            } catch (e: Exception) {
                r.delete(target, null, null)
                throw if (e is IOException) e else IOException(e.message, e)
            }
        }
        val uuid = UUID.randomUUID().toString()
        val dest = File(File(c.filesDir, "received").apply { mkdirs() }, uuid)
        val i = r.openInputStream(from) ?: throw IOException("Ficheiro recebido ilegível")
        i.use { input -> dest.outputStream().use { o -> input.copyTo(o, 64 * 1024) } }
        writeSidecar(c, uuid, name, mime)
        return Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(uuid).build()
    }

    private fun writeSidecar(c: Context, uuid: String, name: String, mime: String) {
        c.getSharedPreferences("bump_files", Context.MODE_PRIVATE).edit()
            .putString("name_$uuid", name).putString("mime_$uuid", mime).apply()
    }

    fun sidecarName(c: Context, uuid: String) = c.getSharedPreferences("bump_files", Context.MODE_PRIVATE).getString("name_$uuid", null)
    fun sidecarMime(c: Context, uuid: String) = c.getSharedPreferences("bump_files", Context.MODE_PRIVATE).getString("mime_$uuid", null)

    fun purgeStaged(c: Context, olderThanMs: Long = 24 * 3600_000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        File(c.filesDir, "sent").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    fun humanSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.ROOT, "%.2f GB", bytes / 1073741824.0)
    }

    enum class Kind(val label: String) { PDF("PDF"), IMAGE("IMG"), VIDEO("VÍDEO"), AUDIO("ÁUDIO"), APK("APK"), DOC("DOC") }

    fun kind(name: String, mime: String): Kind {
        val m = resolveMime(name, mime)
        return when {
            m == APK_MIME -> Kind.APK
            m == "application/pdf" -> Kind.PDF
            m.startsWith("image/") -> Kind.IMAGE
            m.startsWith("video/") -> Kind.VIDEO
            m.startsWith("audio/") -> Kind.AUDIO
            else -> Kind.DOC
        }
    }

    fun openLabel(name: String, mime: String) = when (kind(name, mime)) {
        Kind.APK -> "Instalar"
        Kind.PDF -> "Abrir PDF"
        Kind.IMAGE -> "Ver foto"
        Kind.VIDEO -> "Ver vídeo"
        Kind.AUDIO -> "Ouvir"
        Kind.DOC -> "Abrir"
    }
}

/** Read-only provider for files kept privately (staged outgoing files, pre-Android-10 downloads). */
class BumpProvider : ContentProvider() {
    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException()
        val id = uri.lastPathSegment
        if (id == null || !Regex("[0-9a-fA-F-]{36}").matches(id)) throw FileNotFoundException()
        val sent = File(ctx.filesDir, "sent/$id")
        val received = File(ctx.filesDir, "received/$id")
        return when {
            sent.isFile -> sent
            received.isFile -> received
            else -> throw FileNotFoundException()
        }
    }

    override fun onCreate() = true
    override fun getType(uri: Uri): String =
        context?.let { Files.sidecarMime(it, uri.lastPathSegment.orEmpty()) } ?: "application/octet-stream"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? =
        try {
            val f = resolve(uri)
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
                addRow(arrayOf<Any>(Files.sidecarName(context!!, f.name) ?: f.name, f.length()))
            }
        } catch (_: FileNotFoundException) { null }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Apenas leitura")
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = throw UnsupportedOperationException()
}
