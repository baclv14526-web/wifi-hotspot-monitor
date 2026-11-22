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

        // Sau reboot, trạng thái chu kỳ cảnh báo lưu từ trước là DỮ LIỆU CŨ: hotspot
        // luôn tắt sau khởi động nên nếu giữ cờ "đã báo" thì sẽ im lặng sai, còn mốc
        // thời gian thì không còn ý nghĩa. Xóa sạch để bắt đầu mới.
        MonitorService.clearPersistedState(context)

        // Khôi phục báo thức nếu đã bật — độc lập với cài đặt auto_start của Hotspot monitor
        AlarmClockReceiver.restoreIfEnabled(context)

        // Chỉ tự chạy lại giám sát khi giám sát đang ở trạng thái BẬT (người dùng đã
        // bấm "Bắt đầu" và chưa bấm "Dừng"). Mới cài, hoặc đã bấm Dừng → không tự chạy;
        // người dùng phải vào app bật thủ công.
        if (!prefs.getBoolean("monitoring_enabled", false)) return

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
