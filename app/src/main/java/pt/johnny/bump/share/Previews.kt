package pt.johnny.bump.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Size
import pt.johnny.bump.data.Files
import pt.johnny.bump.data.LocalFile
import pt.johnny.bump.link.Protocol
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** Big previews of what is being shared: photo/video frame, or the first page of a PDF. */
object Previews {
    private const val HERO_PX = 1080

    fun render(c: Context, original: Uri, staged: LocalFile, maxPx: Int = HERO_PX): Bitmap? = runCatching {
        when (Files.kind(staged.name, staged.mime)) {
            Files.Kind.IMAGE, Files.Kind.VIDEO -> thumbnail(c, original, maxPx) ?: thumbnail(c, staged.openUri, maxPx)
            Files.Kind.PDF -> pdfFirstPage(staged, maxPx)
            else -> null
        }
    }.getOrNull()

    private fun thumbnail(c: Context, uri: Uri, maxPx: Int): Bitmap? =
        if (Build.VERSION.SDK_INT < 29) null
        else runCatching { c.contentResolver.loadThumbnail(uri, Size(maxPx, maxPx), null) }.getOrNull()

    private fun pdfFirstPage(file: LocalFile, maxPx: Int): Bitmap? {
        ParcelFileDescriptor.open(file.file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                if (pdf.pageCount == 0) return null
                pdf.openPage(0).use { page ->
                    val scale = maxPx.toFloat() / max(page.width, page.height)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    Canvas(bmp).drawColor(Color.WHITE) // PDF pages are transparent by default
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bmp
                }
            }
        }
    }

    /** JPEG small enough to travel as a single control payload before the bump. */
    fun jpeg(src: Bitmap, maxSide: Int = 720): ByteArray? {
        var side = maxSide
        while (side >= 240) {
            val s = side.toFloat() / max(src.width, src.height)
            val scaled = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(1), (src.height * s).toInt().coerceAtLeast(1), true) else src
            for (q in intArrayOf(82, 70, 58)) {
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= Protocol.MAX_PREVIEW_BYTES) return out.toByteArray()
            }
            side = (side * 0.75f).toInt()
        }
        return null
    }
}
