package pt.johnny.bump.ui.viewer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pt.johnny.bump.data.Files
import pt.johnny.bump.receive.Incoming
import pt.johnny.bump.ui.OpenFileActivity
import pt.johnny.bump.ui.theme.BumpTheme
import pt.johnny.bump.ui.theme.Motion
import kotlin.math.max
import kotlin.math.min

/**
 * The item "already open" as it leaves the notch. It starts on the preview the sender pushed
 * before the bump — framed exactly like the last frame of the NameDrop overlay (fit to the
 * full window) — and quietly swaps in the full-resolution file once it has been saved.
 */
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // Same canvas as the overlay: the whole display, bars hidden.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val id = intent.getLongExtra(EXTRA_ID, -1)
        setContent { BumpTheme(dark = true) { ViewerScreen(id, onClose = ::finish) } }
    }

    companion object {
        private const val EXTRA_ID = "id"

        fun intent(c: Context, id: Long): Intent =
            Intent(c, ViewerActivity::class.java).putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}

@Composable
private fun ViewerScreen(id: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    val current by Incoming.item.collectAsStateWithLifecycle()
    // Keep showing the last state we had for this id even if a newer transfer starts later
    // (a plain holder: no state writes during composition).
    val holder = remember { arrayOfNulls<Incoming.Item>(1) }
    current?.takeIf { it.id == id }?.let { holder[0] = it }
    val item = holder[0]
    val latest by rememberUpdatedState(item)
    var chrome by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { delay(1200); if (latest == null) onClose() }
    LaunchedEffect(item?.error) {
        item?.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); onClose() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (item != null) {
            val toggle = { chrome = !chrome }
            when (Files.kind(item.name, item.mime)) {
                Files.Kind.PDF -> PdfPages(item, toggle)
                Files.Kind.VIDEO -> VideoPlayer(item)
                else -> ZoomImage(item, toggle)
            }
            if (item.uri == null && item.progress in 0f..0.999f) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .18f))) {
                    Box(Modifier.fillMaxWidth(item.progress.coerceIn(0f, 1f)).height(3.dp).background(Color.White))
                }
            }
            val video = Files.kind(item.name, item.mime) == Files.Kind.VIDEO
            AnimatedVisibility(
                chrome || video,
                enter = fadeIn(tween(220, easing = Motion.EaseOut)),
                exit = fadeOut(tween(180, easing = Motion.EaseOut)),
            ) { TopChrome(item, onClose) }
        }
    }
}

@Composable
private fun TopChrome(item: Incoming.Item, onClose: () -> Unit) {
    val context = LocalContext.current
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .65f), Color.Transparent)))
            .statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fechar", tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(item.name, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("De ${item.peer}", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            val uri = item.uri
            IconButton(enabled = uri != null, onClick = {
                uri?.let { runCatching { context.startActivity(OpenFileActivity.share(context, it, item.mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }) { Icon(Icons.Rounded.Share, "Partilhar", tint = Color.White) }
            TextButton(enabled = uri != null, onClick = {
                uri?.let { runCatching { context.startActivity(OpenFileActivity.intent(context, it, item.name, item.mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }) { Text("Abrir com…", color = Color.White) }
        }
    }
}

// ---------------------------------------------------------------- photo

@Composable
private fun ZoomImage(item: Incoming.Item, onTap: () -> Unit) {
    val context = LocalContext.current
    val full by produceState<Bitmap?>(null, item.uri) {
        val u = item.uri ?: return@produceState
        value = withContext(Dispatchers.IO) { decodeImage(context, u, 2560) }
    }
    val bmp = full ?: item.preview
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        val target = if (scale > 1.05f) 1f else 2.5f
                        val startScale = scale
                        val startOffset = offset
                        // Zoom toward the tapped point.
                        val focus = Offset(tap.x - size.width / 2f, tap.y - size.height / 2f)
                        val endOffset = if (target == 1f) Offset.Zero else -focus * (target - 1f)
                        scope.launch {
                            animate(0f, 1f, animationSpec = Motion.snappy()) { v, _ ->
                                scale = startScale + (target - startScale) * v
                                offset = startOffset + (endOffset - startOffset) * v
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale <= 1.001f) Offset.Zero else offset + pan
                }
            },
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(), item.name, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

/** Full image, EXIF-rotated (like the system thumbnail used for the preview), capped at [maxSide]. */
private fun decodeImage(c: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val s = min(1f, maxSide.toFloat() / max(w, h))
            if (s < 1f) decoder.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }
}.getOrNull()

// ---------------------------------------------------------------- PDF

private class PdfDoc(private val fd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    private val lock = Mutex()
    private var closed = false
    val pageCount: Int get() = renderer.pageCount

    suspend fun render(index: Int, widthPx: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            runCatching {
                renderer.openPage(index).use { page ->
                    val h = (widthPx.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                }
            }.getOrNull()
        }
    }

    fun close() {
        closed = true
        runCatching { renderer.close() }
        runCatching { fd.close() }
    }

    companion object {
        fun open(c: Context, uri: Uri): PdfDoc? = runCatching {
            val fd = c.contentResolver.openFileDescriptor(uri, "r") ?: return null
            PdfDoc(fd, PdfRenderer(fd))
        }.getOrNull()
    }
}

@Composable
private fun PdfPages(item: Incoming.Item, onTap: () -> Unit) {
    val context = LocalContext.current
    val uri = item.uri
    val doc = remember(uri) { uri?.let { PdfDoc.open(context, it) } }
    DisposableEffect(doc) { onDispose { doc?.close() } }

    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }) {
        val screenW = constraints.maxWidth
        val screenH = constraints.maxHeight
        // Page width that frames page 1 exactly like the preview did (fit to the window).
        val aspect = item.preview?.let { it.width.toFloat() / it.height } ?: (1f / 1.414f)
        val pageW = min(screenW.toFloat(), screenH * aspect).toInt().coerceAtLeast(1)
        val pageH = (pageW / aspect).toInt()
        if (doc == null) {
            item.preview?.let {
                Image(it.asImageBitmap(), item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        } else {
            val density = LocalDensity.current
            val topPad = with(density) { ((screenH - pageH).coerceAtLeast(0) / 2).toDp() }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = topPad, bottom = topPad),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(doc.pageCount) { i ->
                    val page by produceState(if (i == 0) item.preview else null, doc, i) {
                        doc.render(i, pageW)?.let { value = it }
                    }
                    val h = page?.let { pageW.toFloat() * it.height / it.width } ?: (pageW * 1.414f)
                    Box(Modifier.size(with(density) { pageW.toDp() }, with(density) { h.toDp() }).background(Color.White)) {
                        page?.let { Image(it.asImageBitmap(), "Página ${i + 1}", contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
                    }
                    if (i < doc.pageCount - 1) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- video

@Composable
private fun VideoPlayer(item: Incoming.Item) {
    val uri = item.uri
    var playing by remember(uri) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (uri != null) {
            AndroidView(
                factory = { c ->
                    FrameLayout(c).apply {
                        val video = VideoView(c)
                        addView(video, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                        video.setMediaController(MediaController(c).also { it.setAnchorView(video) })
                        video.setOnPreparedListener { mp -> mp.isLooping = false; playing = true; video.start() }
                        video.setVideoURI(uri)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // The frame the overlay ended on stays until the first video frame is ready.
        if (!playing) item.preview?.let {
            Image(it.asImageBitmap(), item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
    }
}
