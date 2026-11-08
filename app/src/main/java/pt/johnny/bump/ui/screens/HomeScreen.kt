package pt.johnny.bump.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.johnny.bump.app
import pt.johnny.bump.data.Direction
import pt.johnny.bump.data.Files
import pt.johnny.bump.data.Peer
import pt.johnny.bump.data.Transfer
import pt.johnny.bump.link.Permissions
import pt.johnny.bump.link.Radios
import pt.johnny.bump.receive.ReceiverService
import pt.johnny.bump.ui.Banner
import pt.johnny.bump.ui.BumpIllustration
import pt.johnny.bump.ui.DeviceAvatar
import pt.johnny.bump.ui.KindTile
import pt.johnny.bump.ui.OpenFileActivity
import pt.johnny.bump.ui.PresenceOrb
import pt.johnny.bump.ui.Screen
import pt.johnny.bump.ui.SectionHeader
import pt.johnny.bump.ui.relativeTime

@Composable
fun HomeScreen(onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val settings by app.settings.values.collectAsStateWithLifecycle()
    val status by ReceiverService.status.collectAsStateWithLifecycle()
    val peers by app.peers.peers.collectAsStateWithLifecycle()
    val transfers by app.transfers.items.collectAsStateWithLifecycle()

    // Re-read permission/radio state every time the screen comes back.
    var tick by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val radios = remember(tick, status) { Radios.state(context) }
    val permsOk = remember(tick) { Permissions.granted(context) }
    val overlayOk = remember(tick) { Permissions.overlayGranted(context) }
    val notifOk = remember(tick) { Permissions.notificationsGranted(context) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
        if (Permissions.granted(context)) { app.settings.setReceiving(true); ReceiverService.start(context) }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    fun toggleReceiving() {
        if (settings.receiving) {
            app.settings.setReceiving(false); ReceiverService.stop(context)
        } else if (!Permissions.granted(context)) {
            permLauncher.launch(Permissions.missing(context).toTypedArray())
        } else {
            app.settings.setReceiving(true); ReceiverService.start(context)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Row(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("BUMP", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Black), modifier = Modifier.weight(1f))
                IconButton(onClick = { onOpen(Screen.SETTINGS) }) { Icon(Icons.Rounded.Settings, "Definições") }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 12.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PresenceOrb(active = status.running, tablet = app.identity.isTablet())
                    Text(app.identity.displayName, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 4.dp))
                    Text(
                        if (status.running) status.text else "Ativa a receção para que os teus dispositivos te encontrem",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    if (settings.receiving) {
                        FilledTonalButton(onClick = ::toggleReceiving, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Parar receção") }
                    } else {
                        Button(onClick = ::toggleReceiving, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Ativar receção") }
                    }
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (settings.receiving && !radios.bluetooth) Banner(Icons.Rounded.Bluetooth, "Bluetooth desligado", "Necessário para encontrar e sentir o outro dispositivo.", "Ligar", { runCatching { context.startActivity(Radios.bluetoothIntent()) } })
                if (settings.receiving && !radios.wifi) Banner(Icons.Rounded.Wifi, "Wi-Fi desligado", "Os ficheiros viajam por Wi-Fi direto, sem router nem internet.", "Ligar", { runCatching { context.startActivity(Radios.wifiIntent()) } })
                if (!permsOk) Banner(Icons.Rounded.NearMe, "Dispositivos próximos", "Autoriza para descobrir e ligar aos teus dispositivos.", "Autorizar", { permLauncher.launch(Permissions.missing(context).toTypedArray()) })
                if (!overlayOk) Banner(Icons.Rounded.Layers, "Animação por cima das apps", "Mostra a chegada do ficheiro mesmo com outra app aberta.", "Permitir", { runCatching { context.startActivity(Permissions.overlayIntent(context)) } })
                if (!notifOk) Banner(Icons.Rounded.Notifications, "Notificações", "Para abrires o ficheiro recebido com um toque.", "Permitir", {
                    if (android.os.Build.VERSION.SDK_INT >= 33) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else runCatching { context.startActivity(Permissions.appSettingsIntent(context)) }
                })
            }
        }

        if (transfers.isEmpty()) item { HowToCard(Modifier.fillMaxWidth().widthIn(max = 640.dp)) }

        item { SectionHeader("Dispositivos", Modifier.widthIn(max = 640.dp)) }
        if (peers.isEmpty()) {
            item {
                Text(
                    "Ainda não há dispositivos emparelhados. No outro aparelho, partilha um ficheiro com o BUMP e confirma o código nos dois ecrãs.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                )
            }
        } else items(peers, key = { "p" + it.id }) { PeerRow(it, Modifier.widthIn(max = 640.dp)) }

        if (transfers.isNotEmpty()) {
            item { SectionHeader("Recentes", Modifier.widthIn(max = 640.dp), action = "Ver tudo") { onOpen(Screen.HISTORY) } }
            items(transfers.take(5), key = { "t" + it.id }) { TransferRow(it, Modifier.widthIn(max = 640.dp)) }
        }
        item { Spacer(Modifier.navigationBarsPadding()) }
    }
}

@Composable
private fun HowToCard(modifier: Modifier) {
    Surface(modifier.padding(top = 18.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            BumpIllustration(Modifier.size(96.dp))
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Como enviar", style = MaterialTheme.typography.titleMedium)
                Text("1  Num ficheiro, toca em Partilhar › BUMP", style = MaterialTheme.typography.bodyMedium)
                Text("2  Encosta o topo dos dois ecrãs", style = MaterialTheme.typography.bodyMedium)
                Text("3  O ficheiro passa para o outro lado", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, modifier: Modifier) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        DeviceAvatar("", peer.tablet, size = 44.dp)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(peer.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Emparelhado · visto ${relativeTime(peer.lastSeen)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Esquecer dispositivo") }, onClick = { menu = false; context.app.peers.forget(peer.id) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransferRow(t: Transfer, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val uri = Uri.parse(t.uri)
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = { context.startActivity(OpenFileActivity.intent(context, uri, t.name, t.mime)) }, onLongClick = { menu = true })
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KindTile(t.name, t.mime)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (t.direction == Direction.RECEIVED) Icons.Rounded.SouthWest else Icons.Rounded.NorthEast, null,
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        listOf(t.peer.ifBlank { null }, Files.humanSize(t.size).ifBlank { null }, relativeTime(t.time)).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(Files.openLabel(t.name, t.mime), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Partilhar") }, onClick = {
                menu = false
                runCatching { context.startActivity(OpenFileActivity.share(context, uri, t.mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
            DropdownMenuItem(text = { Text("Remover do histórico") }, onClick = { menu = false; context.app.transfers.remove(t.id) })
        }
    }
}
