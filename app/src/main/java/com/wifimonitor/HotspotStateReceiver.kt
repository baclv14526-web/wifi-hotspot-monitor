package com.wifimonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build

class HotspotStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.net.wifi.WIFI_AP_STATE_CHANGED") return

        val state = intent.getIntExtra(
            WifiManager.EXTRA_WIFI_AP_STATE,
            WifiManager.WIFI_AP_STATE_DISABLED
        )

        if (state == WifiManager.WIFI_AP_STATE_DISABLED) {
            ensureServiceRunning(context)
        }
    }

    private fun ensureServiceRunning(context: Context) {
        if (!HotspotMonitorService.isRunning) {
            val serviceIntent = Intent(context, HotspotMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
