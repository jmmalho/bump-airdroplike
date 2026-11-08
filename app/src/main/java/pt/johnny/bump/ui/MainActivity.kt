package pt.johnny.bump.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.johnny.bump.app
import pt.johnny.bump.receive.ReceiverService
import pt.johnny.bump.ui.screens.DemoScreen
import pt.johnny.bump.ui.screens.HistoryScreen
import pt.johnny.bump.ui.screens.HomeScreen
import pt.johnny.bump.ui.screens.OnboardingScreen
import pt.johnny.bump.ui.screens.SettingsScreen
import pt.johnny.bump.ui.theme.BumpTheme

enum class Screen { HOME, HISTORY, SETTINGS, DEMO }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BumpTheme { BumpRoot() } }
    }

    override fun onResume() {
        super.onResume()
        // Service may have been killed (force stop, update): bring it back if the user wants to receive.
        if (app.settings.current.receiving && ReceiverService.status.value.running.not()) {
            runCatching { ReceiverService.start(this) }
        }
    }
}

@Composable
private fun BumpRoot() {
    val context = LocalContext.current
    val settings by context.app.settings.values.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    if (!settings.onboarded) {
        OnboardingScreen(onDone = { context.app.settings.setOnboarded() })
        return
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> HomeScreen(onOpen = { screen = it })
        Screen.HISTORY -> HistoryScreen(onBack = { screen = Screen.HOME })
        Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME }, onDemo = { screen = Screen.DEMO })
        Screen.DEMO -> DemoScreen(onBack = { screen = Screen.SETTINGS })
    }
    PairingDialog()
}

/** First-contact confirmation on the receiving side, while the app is open. */
@Composable
private fun PairingDialog() {
    val context = LocalContext.current
    val request by ReceiverService.pairRequest.collectAsStateWithLifecycle()
    val r = request ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Emparelhar com ${r.name}?") },
        text = {
            Column {
                Text("Confirma que o outro ecrã mostra exatamente este código. Só precisas de fazer isto uma vez.")
                Text(
                    r.digits, modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = { Button(onClick = { ReceiverService.answer(context, r.endpointId, true) }) { Text("Os códigos coincidem") } },
        dismissButton = { TextButton(onClick = { ReceiverService.answer(context, r.endpointId, false) }) { Text("Recusar") } },
    )
}
