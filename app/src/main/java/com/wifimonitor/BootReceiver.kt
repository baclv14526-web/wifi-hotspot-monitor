package com.wifimonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_start", true)) return

        // Khởi động lại service
        val si = Intent(context, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_INTERVAL, prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(si)
        } else {
            context.startService(si)
        }

        // Restore lịch trình nếu đã bật trước khi reboot
        if (prefs.getBoolean("schedule_enabled", false)) {
            ScheduleReceiver.setupDailySchedule(context)
        }
    }
}
