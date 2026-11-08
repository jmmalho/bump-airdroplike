package pt.johnny.bump

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import pt.johnny.bump.data.AppSettings
import pt.johnny.bump.data.Files
import pt.johnny.bump.data.Identity
import pt.johnny.bump.data.PeerStore
import pt.johnny.bump.data.TransferStore
import pt.johnny.bump.feedback.Feedback

/** Tiny manual DI container: one instance of each store for the whole process. */
class BumpApp : Application() {
    lateinit var settings: AppSettings private set
    lateinit var identity: Identity private set
    lateinit var peers: PeerStore private set
    lateinit var transfers: TransferStore private set
    lateinit var feedback: Feedback private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        identity = Identity(this, settings)
        peers = PeerStore(this)
        transfers = TransferStore(this)
        feedback = Feedback(this, settings)
        createChannels()
        Thread { Files.purgeStaged(this) }.start()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PRESENCE, "Receção ativa", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Mantém o BUMP disponível para os teus dispositivos emparelhados"
                setShowBadge(false); setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_FILES, "Ficheiros recebidos", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisa quando um ficheiro chega e permite abri-lo"
                setSound(null, null) // BUMP plays its own cue
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PAIRING, "Pedidos de emparelhamento", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Confirmar um dispositivo novo com o código de segurança"
            }
        )
    }

    companion object {
        const val CH_PRESENCE = "presence"
        const val CH_FILES = "files"
        const val CH_PAIRING = "pairing"
    }
}

val Context.app: BumpApp get() = applicationContext as BumpApp
