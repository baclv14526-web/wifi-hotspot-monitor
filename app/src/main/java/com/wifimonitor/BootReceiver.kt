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

        // Khôi phục báo thức nếu đã bật — độc lập với cài đặt auto_start của Hotspot monitor
        AlarmClockReceiver.restoreIfEnabled(context)

        // FIX QUAN TRỌNG: chỉ auto-start khi người dùng ĐÃ TỪNG bấm "Bắt đầu giám sát"
        // ít nhất 1 lần. Nếu chưa từng bấm (mới cài xong), không tự chạy nền —
        // tránh làm người dùng bất ngờ khi app chạy ngầm mà họ chưa biết.
        if (!prefs.getBoolean("has_ever_started", false)) return

        if (!prefs.getBoolean("auto_start", true)) return

        val useSchedule = prefs.getBoolean("use_schedule", true)

        if (useSchedule) {
            ScheduleReceiver.setupDailySchedule(context)
            val si = Intent(context, MonitorService::class.java).apply {
                putExtra(MonitorService.EXTRA_TRIGGER, MonitorService.TRIGGER_SCHEDULE_MODE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(si)
            } else {
                context.startService(si)
            }
        } else {
            val si = Intent(context, MonitorService::class.java).apply {
                putExtra(MonitorService.EXTRA_INTERVAL,
                    prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(si)
            } else {
                context.startService(si)
            }
        }

        // Khởi động lại watchdog sau reboot — AlarmManager bị xóa khi tắt máy
        // nên phải đặt lại. Watchdog sẽ tiếp tục ping MonitorService mỗi 15 phút.
        WatchdogReceiver.start(context)
    }
}
