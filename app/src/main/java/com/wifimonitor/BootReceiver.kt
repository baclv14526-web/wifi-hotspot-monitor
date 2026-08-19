package com.wifimonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Tự động khởi động lại service sau khi thiết bị reboot.
 * Hoạt động trên cả Oppo/Realme ColorOS.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val validActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )

        if (intent.action !in validActions) return

        // Kiểm tra người dùng có bật auto-start không
        val prefs = context.getSharedPreferences("wifi_monitor_prefs", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(MainActivity.PREF_AUTO_START, true)

        if (autoStart) {
            val serviceIntent = Intent(context, HotspotMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
