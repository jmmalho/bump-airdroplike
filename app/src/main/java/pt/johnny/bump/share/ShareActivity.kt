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
import pt.johnny.bump.ui.handoff.HandoffView
import pt.johnny.bump.ui.theme.BumpTheme
import pt.johnny.bump.ui.theme.Brand

/** Sharesheet target: a translucent sheet over the app you shared from. */
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm.load(sharedUris(intent))
        setContent { BumpTheme { ShareSheet(vm, onClose = ::finish) } }
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

    LaunchedEffect(s.phase) { if (s.phase == Phase.SENT) { delay(1700); onClose() } }

    // Scrim + light, drawn by the same view the receiver uses.
    val scrim by animateFloatAsState(if (s.phase == Phase.SENDING || s.phase == Phase.SENT) .0f else .35f, tween(400), label = "scrim")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)).clickable(remember { MutableInteractionSource() }, null) { if (s.phase != Phase.SENDING) onClose() }) {
        AndroidView(
            factory = { HandoffView(it, sender = true).apply { showCapsule = false } },
            update = { v ->
                when (s.phase) {
                    Phase.READY -> if (v.mode != HandoffView.Mode.READY || v.tag != "ready") { v.play(HandoffView.Mode.READY); v.tag = "ready" }
                    Phase.SENDING -> if (v.tag != "send${s.waveTick}") { v.play(HandoffView.Mode.SEND); v.tag = "send${s.waveTick}" }
                    Phase.ERROR -> if (v.tag != "error") { v.play(HandoffView.Mode.ERROR); v.tag = "error" }
                    else -> Unit
                }
                v.visibility = if (s.phase in listOf(Phase.READY, Phase.SENDING, Phase.SENT, Phase.ERROR)) android.view.View.VISIBLE else android.view.View.INVISIBLE
            },
            modifier = Modifier.fillMaxSize(),
        )

        Surface(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 560.dp).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) { },
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
                FileHeader(s, onClose)
                Spacer(Modifier.height(18.dp))
                AnimatedContent(targetState = s.phase, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "phase") { phase ->
                    when (phase) {
                        Phase.PREPARING -> Center { CircularProgressIndicator(Modifier.size(28.dp)); Caption("A preparar o ficheiro…") }
                        Phase.PERMISSIONS -> Center {
                            Caption("O BUMP precisa de acesso a dispositivos próximos para encontrar o outro aparelho.")
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
                                Text("Emparelhar com ${p.name}", style = MaterialTheme.typography.titleMedium)
                                Caption("Confirma que o outro ecrã mostra o mesmo código. Só fazes isto uma vez.")
                                Text(p.digits, style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp), color = MaterialTheme.colorScheme.primary)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    TextButton(onClick = { vm.confirmPairing(false) }, Modifier.weight(1f)) { Text("Cancelar") }
                                    Button(onClick = { vm.confirmPairing(true) }, Modifier.weight(1f)) { Text("Coincidem") }
                                }
                            }
                        } ?: Spacer(Modifier.height(1.dp))
                        Phase.SENDING, Phase.SENT -> SendingBlock(s)
                        Phase.ERROR -> Center {
                            Text(s.error ?: "Algo correu mal.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                            Button(onClick = vm::retry, Modifier.fillMaxWidth().height(52.dp)) { Text("Tentar de novo") }
                        }
                        else -> DevicesBlock(s, vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun FileHeader(s: ShareState, onClose: () -> Unit) {
    // The thumbnail "launches" upward when the wave starts, then lands back with a check.
    val lift = remember { Animatable(0f) }
    LaunchedEffect(s.waveTick) {
        if (s.waveTick > 0) {
            lift.snapTo(0f)
            lift.animateTo(1f, tween(650, easing = FastOutSlowInEasing))
        }
    }
    val landed = s.phase == Phase.SENT
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.graphicsLayer {
                val up = if (landed) 0f else lift.value
                translationY = -up * 420f
                alpha = if (landed) 1f else 1f - up
                scaleX = 1f - .4f * up; scaleY = 1f - .4f * up
            },
            contentAlignment = Alignment.Center,
        ) {
            FileThumb(s.firstUri, s.firstName, s.firstMime)
            if (landed) Box(Modifier.size(56.dp).background(Brand.Success.copy(alpha = .85f), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
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

@Composable
private fun DevicesBlock(s: ShareState, vm: ShareViewModel) {
    Column(Modifier.fillMaxWidth()) {
        if (s.devices.isEmpty()) {
            Row(Modifier.fillMaxWidth().height(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text("À procura dos teus dispositivos…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "No outro aparelho, abre o BUMP e confirma que a receção está ativa.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                items(s.devices, key = { it.endpointId }) { d -> DeviceChip(d, s, onTap = { vm.tapDevice(d) }) }
            }
        }

        if (s.phase == Phase.READY && s.bumpSupported) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                BumpIllustration(Modifier.size(84.dp))
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(s.title, style = MaterialTheme.typography.titleMedium)
                    Text(s.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.proximityNear) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Box(Modifier.size(8.dp).background(Brand.Success, CircleShape))
                        Text("  Muito perto", style = MaterialTheme.typography.labelMedium, color = Brand.Success)
                    }
                }
            }
        } else if (s.hint.isNotBlank()) {
            Text(s.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        }

        Spacer(Modifier.height(18.dp))
        Button(onClick = vm::sendNow, enabled = s.phase == Phase.READY, modifier = Modifier.fillMaxWidth().height(54.dp)) {
            Text(if (s.phase == Phase.READY) "Enviar sem tocar" else if (s.phase == Phase.CONNECTING) "A ligar…" else "Enviar")
        }
    }
}

@Composable
private fun DeviceChip(d: NearbyLink.Remote, s: ShareState, onTap: () -> Unit) {
    val isTarget = s.target?.endpointId == d.endpointId
    val ring = when {
        isTarget && s.phase == Phase.READY -> Brand.Success
        isTarget -> MaterialTheme.colorScheme.primary
        else -> null
    }
    val connecting = isTarget && s.phase == Phase.CONNECTING
    Box(Modifier.clickable(onClick = onTap).padding(4.dp)) {
        DeviceAvatar(
            d.name, d.tablet, ring = ring,
            badge = if (connecting || !d.trusted) ({ ChipBadge(connecting) }) else null,
        )
    }
}

@Composable
private fun ChipBadge(connecting: Boolean) {
    if (connecting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    else Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Icon(Icons.Rounded.Link, "Novo", Modifier.padding(3.dp).size(14.dp))
    }
}

@Composable
private fun SendingBlock(s: ShareState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            if (s.phase == Phase.SENT) s.title else "A enviar para ${s.target?.name ?: "…"}",
            style = MaterialTheme.typography.titleMedium,
        )
        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(6.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
        Text(
            if (s.phase == Phase.SENT) "Pronto" else "${Files.humanSize(s.transferred)} de ${Files.humanSize(s.totalBytes)}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(54.dp))
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
