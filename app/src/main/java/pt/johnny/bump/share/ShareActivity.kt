package pt.johnny.bump.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import pt.johnny.bump.ui.pressScale
import pt.johnny.bump.ui.theme.Motion
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pt.johnny.bump.data.Files
import pt.johnny.bump.link.NearbyLink
import pt.johnny.bump.link.Permissions
import pt.johnny.bump.link.Radios
import pt.johnny.bump.ui.BumpIllustration
import pt.johnny.bump.ui.DeviceAvatar
import pt.johnny.bump.ui.FileThumb
import pt.johnny.bump.ui.handoff.NameDropView
import pt.johnny.bump.ui.theme.BumpTheme
import pt.johnny.bump.ui.theme.Brand

/** Sharesheet target: a translucent sheet over the app you shared from. */
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.load(sharedInput(intent))
        setContent { BumpTheme { ShareSheet(vm, onClose = ::finish) } }
    }

    /** Files first; otherwise a web page (Chrome sends the URL as text, the title as subject) or plain text. */
    private fun sharedInput(i: Intent?): ShareInput {
        val uris = sharedUris(i)
        if (uris.isNotEmpty() || i == null) return ShareInput.Items(uris)
        val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val subject = i.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val url = pt.johnny.bump.link.Protocol.findUrl(text)
        return when {
            url != null -> ShareInput.Link(url, subject.ifBlank { text.replace(url, "").trim().take(120) })
            text.isNotBlank() -> ShareInput.Text(text)
            else -> ShareInput.Items(emptyList())
        }
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        val out = mutableListOf<Uri>()
        when (i.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(out::add)
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.let(out::addAll)
        }
        if (out.isEmpty()) i.clipData?.let { c -> for (k in 0 until c.itemCount) c.getItemAt(k).uri?.let(out::add) }
        return out
    }
}

@Composable
private fun ShareSheet(vm: ShareViewModel, onClose: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshRadios(); if (s.phase == Phase.PERMISSIONS && Permissions.granted(context)) vm.begin() }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.begin() }

    LaunchedEffect(s.phase) { if (s.phase == Phase.SENT) { delay(1800); onClose() } }

    val handoff = s.waveTick > 0 // the bump happened: NameDrop takes over the whole screen
    // Where the big preview sits, so NameDrop starts the "pull into the notch" from exactly there.
    var heroRect by remember { mutableStateOf<android.graphics.RectF?>(null) }
    val hostView = androidx.compose.ui.platform.LocalView.current

    // Sheet physics: springs in, follows the finger, and leaves with the finger's velocity.
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(3000f) }
    var sheetHeight by remember { mutableFloatStateOf(0f) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { offset.animateTo(0f, Motion.snappy()) }
    fun dismiss(velocity: Float = 0f) {
        if (leaving) return
        leaving = true
        scope.launch {
            offset.animateTo(maxOf(sheetHeight, 1200f), Motion.spring(0.32f, 1f), initialVelocity = velocity)
            onClose()
        }
    }
    val drag = rememberDraggableState { delta ->
        scope.launch {
            val next = offset.value + delta
            // Rubber band when pulled upward past rest.
            offset.snapTo(if (next < 0f) offset.value + delta * 0.18f else next)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (!handoff) {
            val shown = if (sheetHeight > 0f) (1f - offset.value / sheetHeight).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = .38f * shown))
                    .clickable(remember { MutableInteractionSource() }, null) { dismiss() },
            )
            // The item itself, large, floating above the sheet ("aparece a foto").
            val sheetDp = with(androidx.compose.ui.platform.LocalDensity.current) { sheetHeight.toDp() }
            Box(
                Modifier.fillMaxSize().padding(bottom = sheetDp).statusBarsPadding().padding(24.dp)
                    .graphicsLayer { alpha = shown; translationY = (1f - shown) * 80f },
                contentAlignment = Alignment.Center,
            ) {
                HeroPreview(s, Modifier.onGloballyPositioned { c ->
                    val b = c.boundsInRoot()
                    heroRect = android.graphics.RectF(b.left, b.top, b.right, b.bottom)
                })
            }
            Surface(
                Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                    .onSizeChanged { sheetHeight = it.height.toFloat() }
                    .offset { IntOffset(0, offset.value.roundToInt()) }
                    .draggable(
                        state = drag,
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity ->
                            if (offset.value > sheetHeight * 0.28f || velocity > 1600f) dismiss(velocity)
                            else offset.animateTo(0f, Motion.bouncy(), initialVelocity = velocity)
                        },
                    )
                    .clickable(remember { MutableInteractionSource() }, null) { },
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 16.dp,
            ) {
                Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp)) {
                    Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    FileHeader(s) { dismiss() }
                    Spacer(Modifier.height(8.dp))
                    when (s.phase) {
                        Phase.PREPARING -> Center { CircularProgressIndicator(Modifier.size(28.dp)); Caption("A preparar o ficheiro…") }
                        Phase.PERMISSIONS -> Center {
                            Caption("O BUMP precisa de acesso a dispositivos próximos para sentir o outro aparelho.")
                            Button(onClick = { permLauncher.launch(Permissions.missing(context).toTypedArray()) }, Modifier.fillMaxWidth().height(52.dp)) { Text("Autorizar") }
                        }
                        Phase.RADIOS_OFF -> Center {
                            Caption("Liga o Bluetooth e o Wi-Fi. Não precisas de internet: os aparelhos falam diretamente.")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (!s.radios.bluetooth) FilledTonalButton(onClick = { runCatching { context.startActivity(Radios.bluetoothIntent()) } }, Modifier.weight(1f)) { Text("Bluetooth") }
                                if (!s.radios.wifi) FilledTonalButton(onClick = { runCatching { context.startActivity(Radios.wifiIntent()) } }, Modifier.weight(1f)) { Text("Wi-Fi") }
                            }
                        }
                        Phase.PAIRING -> s.pairing?.let { p ->
                            Center {
                                Text("Novo dispositivo: ${p.name}", style = MaterialTheme.typography.titleMedium)
                                Caption("Confirma que o outro ecrã mostra o mesmo código. Só fazes isto uma vez.")
                                Text(p.digits, style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp), color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TextButton(onClick = { vm.confirmPairing(false) }, Modifier.weight(1f)) { Text("Cancelar") }
                                    Button(onClick = { vm.confirmPairing(true) }, Modifier.weight(1f)) { Text("Coincidem") }
                                }
                            }
                        } ?: Spacer(Modifier.height(1.dp))
                        Phase.ERROR -> Center {
                            Text(s.error ?: "Algo correu mal.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                            Button(onClick = vm::retry, Modifier.fillMaxWidth().height(52.dp)) { Text("Tentar de novo") }
                        }
                        // SEARCHING, CONNECTING and READY look exactly the same on purpose.
                        else -> BumpPrompt(s, vm)
                    }
                }
            }
        } else {
            AndroidView(
                factory = { c ->
                    NameDropView(c).apply {
                        params = vm.ripple
                        configure(NameDropView.Role.SENDER, notchPoint(hostView), heroRect)
                        setFile(s.firstName, s.firstMime)
                        setLinkHost(s.linkHost)
                        setHeroImage(s.preview)
                        setHeadline("A enviar", "Para ${s.target?.name ?: "outro dispositivo"}")
                        play()
                    }
                },
                update = { v ->
                    v.setHeadline(if (s.phase == Phase.SENT) "Enviado" else "A enviar", "Para ${s.target?.name ?: "outro dispositivo"}")
                    if (s.progress > 0f) v.setProgress(s.progress)
                    when (s.phase) {
                        Phase.SENT -> if (v.tag != "done") { v.tag = "done"; v.showDone("Para ${s.target?.name ?: "outro dispositivo"}", null, null) }
                        Phase.ERROR -> if (v.tag != "error") { v.tag = "error"; v.showError(s.error ?: "A transferência falhou") }
                        else -> Unit
                    }
                },
                modifier = Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {
                    if (s.phase == Phase.SENT || s.phase == Phase.ERROR) onClose()
                },
            )
        }
    }
}

/** Centre of the camera cutout (the "notch"), in window coordinates; null when there is none. */
private fun notchPoint(view: android.view.View): android.graphics.PointF? {
    if (Build.VERSION.SDK_INT < 28) return null
    val rects = view.rootWindowInsets?.displayCutout?.boundingRects ?: return null
    val top = rects.minByOrNull { it.top } ?: return null
    if (top.top > view.height / 4) return null // a side or bottom cutout is not where the devices touch
    return android.graphics.PointF(top.exactCenterX(), top.exactCenterY())
}

/** The big preview: photo, video frame, PDF first page, link card, or a large kind tile. */
@Composable
private fun HeroPreview(s: ShareState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val bmp = s.preview
    when {
        bmp != null -> {
            val ratio = (bmp.width.toFloat() / bmp.height).coerceIn(0.56f, 1.8f)
            androidx.compose.foundation.Image(
                bmp.asImageBitmap(), null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = modifier.widthIn(max = 440.dp).fillMaxWidth(0.8f).aspectRatio(ratio)
                    .shadow(24.dp, shape).clip(shape),
            )
        }
        s.firstMime == Files.LINK_MIME -> Surface(
            modifier.widthIn(max = 420.dp).fillMaxWidth(0.8f).aspectRatio(1.55f), shape = shape, shadowElevation = 24.dp, color = Color.White,
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(56.dp).background(Brand.Blue, CircleShape), contentAlignment = Alignment.Center) {
                    Text(s.linkHost.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
                Text(s.linkHost, style = MaterialTheme.typography.titleMedium, color = Brand.Ink, modifier = Modifier.padding(top = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.firstName, style = MaterialTheme.typography.bodySmall, color = Color(0xFF606C82), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
        s.firstMime == Files.TEXT_MIME -> Surface(
            modifier.widthIn(max = 420.dp).fillMaxWidth(0.8f), shape = shape, shadowElevation = 24.dp, color = Color.White,
        ) {
            Text("“${s.firstName}”", style = MaterialTheme.typography.bodyLarge, color = Brand.Ink, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(22.dp))
        }
        else -> pt.johnny.bump.ui.KindTile(s.firstName, s.firstMime, modifier.shadow(24.dp, RoundedCornerShape(40.dp)), size = 132.dp)
    }
}

@Composable
private fun FileHeader(s: ShareState, onClose: () -> Unit) {
    // The big preview floats above the sheet, so the header is just the name and size.
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(start = 4.dp, end = 14.dp)) {
            Text(
                if (s.fileCount > 1) "${s.fileCount} ficheiros" else s.firstName.ifBlank { "Ficheiro" },
                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val kind = Files.kind(s.firstName, s.firstMime).label
            Text(
                listOf(if (s.fileCount > 1) null else kind, Files.humanSize(s.totalBytes).ifBlank { null }).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Fechar") }
    }
}

/**
 * The only thing the user sees before the touch. It never says whether a device is
 * connected, which one, or that anything is loading: the reveal happens on contact.
 */
@Composable
private fun BumpPrompt(s: ShareState, vm: ShareViewModel) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BumpIllustration(Modifier.size(104.dp))
        Text("Encosta ao outro dispositivo", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            when {
                s.weakSignal -> "Quase. Junta mesmo o topo dos dois ecrãs."
                s.manualQueued -> "Vai seguir assim que o outro dispositivo responder."
                s.nobodyAround -> "Não sinto nenhum dispositivo. Confirma que o BUMP está a receber no outro aparelho."
                else -> "Junta o topo dos dois ecrãs com um toque leve."
            },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 12.dp, end = 12.dp),
        )

        // New (never-paired) devices are the only ones listed: pairing needs an explicit choice.
        val fresh = s.devices.filter { !it.trusted }
        if (fresh.isNotEmpty()) {
            Text(
                "Novo por perto", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp),
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(fresh, key = { it.endpointId }) { d ->
                    val press = remember { MutableInteractionSource() }
                    Box(Modifier.pressScale(press).clickable(interactionSource = press, indication = null) { vm.tapDevice(d) }.padding(4.dp)) {
                        DeviceAvatar(d.name, d.tablet, size = 54.dp, badge = { ChipBadge() })
                    }
                }
            }
        }

        TextButton(onClick = vm::sendNow, enabled = !s.manualQueued, modifier = Modifier.padding(top = 12.dp)) { Text("Enviar sem tocar") }
    }
}

@Composable
private fun ChipBadge() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Icon(Icons.Rounded.Link, "Novo", Modifier.padding(3.dp).size(14.dp))
    }
}

@Composable
private fun Center(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}
