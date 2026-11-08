package pt.johnny.bump.link

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

/**
 * From late 2026 Nearby Connections no longer switches Bluetooth/Wi-Fi on by
 * itself (Android Developers Blog, 20 Jul 2026). We check before every session
 * and send the user to the right switch instead of failing silently.
 */
object Radios {
    data class State(val bluetooth: Boolean, val wifi: Boolean) {
        val ready get() = bluetooth && wifi
    }

    fun state(c: Context) = State(
        bluetooth = c.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true,
        wifi = c.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true,
    )

    fun bluetoothIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
    fun wifiIntent() = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
}

object Permissions {
    fun runtime(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT <= 32) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }.toTypedArray()

    fun missing(c: Context) = runtime().filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    fun granted(c: Context) = missing(c).isEmpty()

    fun notificationsGranted(c: Context) = Build.VERSION.SDK_INT < 33 ||
        c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun overlayGranted(c: Context) = Settings.canDrawOverlays(c)

    fun overlayIntent(c: Context) = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))

    fun appSettingsIntent(c: Context) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
}
