package pt.johnny.bump.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import pt.johnny.bump.data.Files

/**
 * Opens a finished transfer with the system's own viewer or installer — always a
 * deliberate user action. APKs get an explicit warning before the installer.
 */
class OpenFileActivity : Activity() {
    private lateinit var uri: Uri
    private var name = "ficheiro"
    private var mime = "application/octet-stream"
    private var waitingForInstallPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        name = intent.getStringExtra(EXTRA_NAME) ?: "ficheiro"
        val rawUri = intent.getStringExtra(EXTRA_URI).orEmpty()
        if (intent.getStringExtra(EXTRA_MIME) == Files.LINK_MIME) {
            openLink(rawUri)
            return
        }
        mime = Files.resolveMime(name, intent.getStringExtra(EXTRA_MIME))
        try {
            uri = Uri.parse(intent.getStringExtra(EXTRA_URI).orEmpty())
            require(uri.scheme == "content")
            contentResolver.openFileDescriptor(uri, "r")?.close() ?: error("indisponível")
        } catch (_: Exception) {
            fail("Este ficheiro já não está disponível. Pode ter sido apagado ou movido.")
            return
        }
        if (mime == Files.APK_MIME) {
            AlertDialog.Builder(this)
                .setTitle("Instalar esta app?")
                .setMessage("«$name» é uma aplicação Android. Instala apenas se confiares em quem a enviou.")
                .setPositiveButton("Continuar") { _, _ -> openInstaller() }
                .setNegativeButton("Cancelar") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
        } else openExternal()
    }

    private fun openLink(url: String) {
        if (!pt.johnny.bump.link.Protocol.isWebUrl(url)) { fail("Este link não é válido."); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            finish()
        } catch (_: ActivityNotFoundException) {
            fail("Não há nenhum navegador instalado.")
        }
    }

    private fun openInstaller() {
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Autorizar instalação")
                .setMessage("O Android pede que autorizes o BUMP a abrir o instalador. O ficheiro continua guardado mesmo que não autorizes.")
                .setPositiveButton("Abrir definições") { _, _ ->
                    waitingForInstallPermission = true
                    runCatching { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                        .onFailure { fail("Não consegui abrir as definições.") }
                }
                .setNegativeButton("Cancelar") { _, _ -> finish() }
                .setOnCancelListener { finish() }
                .show()
            return
        }
        openExternal()
    }

    override fun onResume() {
        super.onResume()
        if (waitingForInstallPermission) {
            waitingForInstallPermission = false
            if (packageManager.canRequestPackageInstalls()) openExternal()
            else fail("A instalação não foi autorizada. Podes abrir o ficheiro mais tarde no histórico.")
        }
    }

    private fun openExternal() {
        try {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                clipData = ClipData.newUri(contentResolver, name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(view)
            finish()
        } catch (_: ActivityNotFoundException) {
            fail("Não há nenhuma app instalada para abrir este tipo de ficheiro.")
        } catch (_: Exception) {
            fail("Não foi possível abrir. Procura-o em Transferências › BUMP.")
        }
    }

    private fun fail(message: String) {
        AlertDialog.Builder(this)
            .setTitle("Não foi possível abrir")
            .setMessage(message)
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_MIME = "mime"

        fun intent(c: Context, uri: Uri, name: String, mime: String): Intent =
            Intent(c, OpenFileActivity::class.java)
                .putExtra(EXTRA_URI, uri.toString())
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_MIME, mime)

        fun share(c: Context, uri: Uri, mime: String): Intent =
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                null,
            )
    }
}
