package com.wifimonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Watchdog / Heartbeat — kiểm tra MonitorService còn sống không mỗi 15 phút.
 *
 * ColorOS (Oppo/Realme) có thể kill Foreground Service sau vài giờ không tương tác,
 * ngay cả khi đã tắt battery optimization. START_STICKY giúp Android restart service
 * nhưng có thể trễ hàng chục phút hoặc không restart trên một số ROM.
 *
 * Giải pháp: dùng AlarmManager đặt alarm lặp lại mỗi 15 phút. Mỗi lần alarm kích
 * hoạt, WatchdogReceiver kiểm tra MonitorService.running:
 * - Còn sống (running = true): không làm gì, chỉ lên lịch lần ping tiếp theo
 * - Đã chết (running = false): restart service ngay lập tức
 *
 * Điều này đảm bảo service bị chết tối đa 15 phút trước khi được hồi phục —
 * đủ nhanh để không bỏ sót cảnh báo Hotspot trong thực tế.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Chỉ watchdog khi người dùng đã bật giám sát
        if (!prefs.getBoolean("has_ever_started", false)) return
        if (!prefs.getBoolean("auto_start", true)) return

        if (!MonitorService.running) {
            // Service đã chết → restart ngay
            restartMonitorService(context, prefs)
        }

        // Luôn lên lịch ping tiếp theo (dù vừa restart hay không)
        scheduleNextPing(context)
    }

    companion object {
        const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L  // 15 phút
        private const val REQ_CODE = 8000
        private const val ACTION = "com.wifimonitor.WATCHDOG_PING"

        /**
         * Bắt đầu vòng lặp watchdog — gọi khi người dùng bấm "Bắt đầu giám sát".
         * Không dùng setRepeating() vì trên Android 6+ nó không exact và có thể
         * bị gộp lại. Thay vào đó mỗi lần kích hoạt tự lên lịch lần tiếp theo
         * (self-scheduling pattern), đảm bảo chính xác hơn.
         */
        fun start(context: Context) {
            scheduleNextPing(context)
        }

        /**
         * Dừng vòng lặp watchdog — gọi khi người dùng bấm "Dừng giám sát".
         */
        fun stop(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildPendingIntent(context))
        }

        private fun scheduleNextPing(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerMs = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            val pi = buildPendingIntent(context)

            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        if (Api31Compat.canScheduleExactAlarms(am)) {
                            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                        } else {
                            // Không có quyền exact alarm → dùng inexact, vẫn hoạt động
                            // chỉ có thể trễ vài phút so với 15 phút đúng hẹn
                            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                        }
                    }
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    }
                    else -> {
                        am.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    }
                }
            } catch (e: SecurityException) {
                try { am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi) } catch (e2: Exception) { }
            } catch (e: Throwable) {
                // Không để bất kỳ lỗi nào phá vỡ watchdog
                try { am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi) } catch (e2: Exception) { }
            }
        }

        private fun restartMonitorService(
            context: Context,
            prefs: android.content.SharedPreferences
        ) {
            val useSchedule = prefs.getBoolean("use_schedule", true)
            val si = Intent(context, MonitorService::class.java).apply {
                if (useSchedule) {
                    putExtra(MonitorService.EXTRA_TRIGGER, MonitorService.TRIGGER_SCHEDULE_MODE)
                } else {
                    putExtra(MonitorService.EXTRA_INTERVAL,
                        prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL))
                }
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(si)
                } else {
                    context.startService(si)
                }
            } catch (e: Exception) {
                // Log nhưng không crash — watchdog sẽ thử lại sau 15 phút
            }
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = ACTION
            }
            return PendingIntent.getBroadcast(
                context, REQ_CODE, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
