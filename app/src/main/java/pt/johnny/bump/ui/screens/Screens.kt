package pt.johnny.bump.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.johnny.bump.BuildConfig
import pt.johnny.bump.app
import pt.johnny.bump.data.Direction
import pt.johnny.bump.feedback.Cue
import pt.johnny.bump.link.Permissions
import pt.johnny.bump.ui.BumpIllustration
import pt.johnny.bump.ui.pressScale
import pt.johnny.bump.ui.handoff.NameDropView
import pt.johnny.bump.ui.handoff.RippleParams
import pt.johnny.bump.ui.theme.Brand

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable (Modifier) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
                actions = { actions() },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad -> content(Modifier.padding(pad)) }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onDemo: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val s by app.settings.values.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(s.displayName) }
    var rssi by remember { mutableFloatStateOf(s.rssiThreshold.toFloat()) }
    var confirmForget by remember { mutableStateOf(false) }

    Page("Definições", onBack) { m ->
        Column(m.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 640.dp)) {
                Group("Este dispositivo")
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(32); app.settings.setDisplayName(name) },
                    label = { Text("Nome visível") }, placeholder = { Text(app.identity.displayName) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("É assim que os outros dispositivos te veem. Aplica-se na próxima ligação.") },
                )

                Group("Toque")
                Text("Força necessária para reconhecer o encosto", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                val options = listOf("Firme", "Normal", "Leve")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = s.bumpSensitivity == i, onClick = { app.settings.setBump(i) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    "Usa «Leve» se o tablet estiver numa capa grossa. Usa «Firme» se o envio disparar sem quereres.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )

                Spacer(Modifier.height(18.dp))
                Text("Distância de confirmação (Bluetooth): ${rssi.toInt()} dBm", style = MaterialTheme.typography.titleSmall)
                Slider(value = rssi, onValueChange = { rssi = it }, onValueChangeFinished = { app.settings.setRssi(rssi.toInt()) }, valueRange = -80f..-45f)
                Text("Valores mais altos exigem os dispositivos mais juntos. O toque físico continua a ser o gatilho.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Group("Som e vibração")
                ToggleRow("Efeitos sonoros", "Respeitam o modo silencioso", s.sounds) { app.settings.setSounds(it) }
                ToggleRow("Vibração", "Um toque no momento do encosto", s.haptics) { app.settings.setHaptics(it) }
                OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Laboratório da animação") }

                Group("Privacidade")
                Text(
                    "Os ficheiros vão diretamente de um aparelho para o outro, cifrados, sem passar pela internet. Só dispositivos que confirmaste com o código podem enviar-te ficheiros.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Esquecer todos os dispositivos") }

                Group("Sobre")
                Text("BUMP ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                Text("Nearby Connections · deteção de toque por acelerómetro · Bluetooth LE", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Esquecer todos?") },
        text = { Text("Vais ter de confirmar o código outra vez em cada dispositivo. Faz o mesmo no outro aparelho.") },
        confirmButton = { TextButton(onClick = { app.peers.forgetAll(); confirmForget = false }) { Text("Esquecer") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun Group(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier.padding(horizontal = 0.dp),
    )
}

// ---------------------------------------------------------------- History

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val items by context.app.transfers.items.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<Direction?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val shown = items.filter { filter == null || it.direction == filter }

    Page("Ficheiros", onBack, actions = { if (items.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Limpar") } }) { m ->
        LazyColumn(m.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item {
                Row(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Todos") })
                    FilterChip(selected = filter == Direction.RECEIVED, onClick = { filter = Direction.RECEIVED }, label = { Text("Recebidos") })
                    FilterChip(selected = filter == Direction.SENT, onClick = { filter = Direction.SENT }, label = { Text("Enviados") })
                }
            }
            if (shown.isEmpty()) item {
                Text("Nada por aqui.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 48.dp))
            }
            items(shown, key = { it.id }) {
                TransferRow(it, Modifier.widthIn(max = 640.dp))
                HorizontalDivider(Modifier.widthIn(max = 640.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            item { Spacer(Modifier.navigationBarsPadding()) }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Limpar histórico?") },
        text = { Text("Os ficheiros recebidos continuam em Transferências › BUMP. Só a lista é apagada.") },
        confirmButton = { TextButton(onClick = { context.app.transfers.clear(); confirmClear = false }) { Text("Limpar") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
    )
}

// ---------------------------------------------------------------- Demo / NameDrop lab

@Composable
fun DemoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val fb = context.app.feedback
    var view by remember { mutableStateOf<NameDropView?>(null) }
    var p by remember { mutableStateOf(RippleParams.load(context)) }
    var panel by remember { mutableStateOf(true) }

    fun replay() {
        val v = view ?: return
        v.params = p
        v.setHeadline("A receber", "De Galaxy S24 Ultra")
        v.setFile("apontamentos.pdf", "application/pdf")
        v.onReleaseCue = { fb.play(Cue.BUMP) }
        fb.playCharge(p.chargeSeconds)
        v.play()
        val start = System.currentTimeMillis()
        val revealMs = ((p.chargeSeconds + p.rippleSeconds * .3f + .55f) * 1000).toLong()
        val tick = object : Runnable {
            override fun run() {
                val e = System.currentTimeMillis() - start
                val prog = ((e - p.chargeSeconds * 1000) / 1600f).coerceIn(0f, 1f)
                v.setProgress(prog)
                if (prog < 1f || e < revealMs) v.postDelayed(this, 50)
                else { fb.play(Cue.RECEIVED); v.showDone("De Galaxy S24 Ultra · 2,4 MB", "Abrir PDF") { } }
            }
        }
        v.postDelayed(tick, 50)
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { c -> NameDropView(c).also { view = it; it.post { replay() } } },
            modifier = Modifier.fillMaxSize(),
        )
        IconButton(onClick = onBack, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", tint = androidx.compose.ui.graphics.Color.White)
        }
        Surface(
            Modifier.align(Alignment.BottomCenter).padding(12.dp).navigationBarsPadding().widthIn(max = 560.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge, tonalElevation = 3.dp, shadowElevation = 10.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { replay() }) { Text("Repetir") }
                    OutlinedButton(onClick = { p = RippleParams(); replay() }) { Text("Repor") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { RippleParams.save(context, p) }) { Text("Usar nas transferências") }
                }
                TextButton(onClick = { panel = !panel }) { Text(if (panel) "Esconder parâmetros" else "Mostrar parâmetros") }
                if (panel) Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Param("Velocidade", p.speed, .1f..1.5f) { p = p.copy(speed = it) }
                    Param("Amplitude", p.amplitude, 1f..50f, "%.0f") { p = p.copy(amplitude = it) }
                    Param("Nitidez", p.sharpness, .05f..2f) { p = p.copy(sharpness = it) }
                    Param("Largura do anel", p.width, .02f..0.4f) { p = p.copy(width = it) }
                    Param("Distorção", p.distortion, 0f..4f) { p = p.copy(distortion = it) }
                    Param("Brilho da onda", p.highlight, 0f..4f) { p = p.copy(highlight = it) }
                    Param("Tempo de carga (s)", p.chargeSeconds, 0f..2.5f) { p = p.copy(chargeSeconds = it) }
                    Param("Tamanho do brilho", p.glowSize, .3f..2f) { p = p.copy(glowSize = it) }
                    Param("Intensidade do brilho", p.glowIntensity, 0f..1.5f) { p = p.copy(glowIntensity = it) }
                }
            }
        }
    }
}

@Composable
private fun Param(title: String, value: Float, range: ClosedFloatingPointRange<Float>, format: String = "%.2f", onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(String.format(java.util.Locale.ROOT, format, value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

// ---------------------------------------------------------------- Onboarding

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val nearby = remember(tick) { Permissions.granted(context) }
    val notif = remember(tick) { Permissions.notificationsGranted(context) }
    val overlay = remember(tick) { Permissions.overlayGranted(context) }
    val nearbyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        BumpIllustration(Modifier.size(220.dp))
        Text("Encosta e está enviado.", style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 520.dp).padding(top = 12.dp))
        Text(
            "Partilha um ficheiro com o BUMP e toca com o topo do telemóvel no outro dispositivo. Os dois sentem o toque ao mesmo tempo e o ficheiro passa, cifrado, sem internet.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp).padding(top = 12.dp),
        )
        Column(Modifier.widthIn(max = 520.dp).padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Step(Icons.Rounded.NearMe, "Dispositivos próximos", "Obrigatório. Para encontrar e ligar aos teus aparelhos.", nearby) {
                nearbyLauncher.launch(Permissions.missing(context).toTypedArray())
            }
            Step(Icons.Rounded.Notifications, "Notificações", "Para abrires o que recebes com um toque.", notif) {
                if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            Step(Icons.Rounded.Layers, "Por cima de outras apps", "Opcional. Mostra a chegada do ficheiro em qualquer ecrã.", overlay) {
                runCatching { context.startActivity(Permissions.overlayIntent(context)) }
            }
        }
        Spacer(Modifier.height(28.dp))
        val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Button(onClick = onDone, enabled = nearby, interactionSource = press, modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().height(56.dp).pressScale(press)) { Text("Começar") }
    }
}

@Composable
private fun Step(icon: ImageVector, title: String, body: String, done: Boolean, onClick: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (done) Icon(Icons.Rounded.CheckCircle, "Feito", tint = Brand.Success)
            else TextButton(onClick = onClick) { Text("Permitir") }
        }
    }
}
