package com.wifimonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build

/**
 * Nhận broadcast khi trạng thái Hotspot thay đổi.
 * Trên Android 9+, broadcast này vẫn hoạt động nhưng cần permission.
 * Đây là cơ chế phụ bên cạnh polling trong Service.
 */
class HotspotStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.net.wifi.WIFI_AP_STATE_CHANGED") return

        val state = intent.getIntExtra(
            WifiManager.EXTRA_WIFI_AP_STATE,
            WifiManager.WIFI_AP_STATE_DISABLED
        )

        when (state) {
            WifiManager.WIFI_AP_STATE_DISABLED -> {
                // Hotspot vừa tắt - đảm bảo service đang chạy để gửi notification
                ensureServiceRunning(context)
            }
            WifiManager.WIFI_AP_STATE_ENABLED -> {
                // Hotspot bật lại - có thể cancel alert notification
            }
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
