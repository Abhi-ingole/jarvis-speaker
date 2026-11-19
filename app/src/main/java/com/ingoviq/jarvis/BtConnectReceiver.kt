package com.ingoviq.jarvis

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Jarvis band asel ani speaker connect zala tar: Jarvis start karaycha prayatna karto,
 * Android ne parvangi dili nahi tar "tap to start" notification dakhavto.
 */
class BtConnectReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
        val prefs = Prefs(ctx)
        if (!prefs.autoStart || JarvisService.running) return
        if (prefs.geminiKey.isBlank() || prefs.picoKey.isBlank()) return

        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
        if (prefs.deviceAddress.isNotEmpty() && device?.address != prefs.deviceAddress) return

        try {
            JarvisService.start(ctx)
        } catch (e: Exception) {
            Notifs.showTapToStart(ctx)
        }
    }
}
