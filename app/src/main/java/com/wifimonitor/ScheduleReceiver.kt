package com.wifimonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

class ScheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Luôn "ping" service tại đúng mốc giờ để cập nhật trạng thái trong
        // notification nền — trước đây chỉ start khi hotspot TẮT, khiến người
        // dùng không có cách nào biết lịch trình có thực sự chạy hay không khi
        // hotspot đang BẬT. Logic gửi cảnh báo (chỉ khi tắt) vẫn nằm trong
        // MonitorService.pollHotspot(), không đổi.
        val si = Intent(context, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_TRIGGER, MonitorService.TRIGGER_SCHEDULE)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(si)
            } else {
                context.startService(si)
            }
        } catch (e: Exception) {
            // Không để lỗi start service làm crash ứng dụng
        }

        // Lên lịch lại cho ngày mai
        scheduleNext(context)
    }

    companion object {
        // 4 khung giờ cố định: 7:00, 11:00, 16:00, 21:00
        private val SCHEDULE_HOURS = listOf(7, 11, 16, 21)

        fun setupDailySchedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            SCHEDULE_HOURS.forEachIndexed { index, hour ->
                val pi = buildPendingIntent(context, index)
                val triggerMs = nextTriggerMs(hour)
                scheduleOne(am, triggerMs, pi)
            }
        }

        /**
         * Đặt 1 alarm, có kiểm tra quyền và fallback an toàn.
         * FIX QUAN TRỌNG (crash trên Android 9/ColorOS): việc gọi trực tiếp
         * am.canScheduleExactAlarms() (API 31+) NGAY TRONG hàm này — dù có bọc
         * if (SDK_INT >= S) — vẫn có thể khiến ART trên một số ROM cũ (đặc biệt
         * ColorOS) ném NoSuchMethodError/VerifyError khi class này được nạp,
         * vì trình verify kiểm tra TOÀN BỘ method chứ không chỉ nhánh sẽ chạy.
         * Cách fix chuẩn: tách lời gọi API mới ra 1 object riêng (Api31Compat)
         * — object đó chỉ được nạp/verify khi thực sự bị tham chiếu tới, tức
         * chỉ trên máy Android 12+. Trên Android 9, object này không bao giờ
         * được chạm tới nên không gây crash.
         */
        private fun scheduleOne(am: AlarmManager, triggerMs: Long, pi: PendingIntent) {
            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        if (Api31Compat.canScheduleExactAlarms(am)) {
                            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                        } else {
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
                // Fallback cuối cùng nếu quyền bị thu hồi đột ngột giữa lúc chạy
                try { am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi) } catch (e2: Exception) { }
            } catch (e: Exception) {
                // Không để bất kỳ lỗi AlarmManager nào làm crash app
            } catch (e: Throwable) {
                // Bắt cả NoSuchMethodError/VerifyError phòng trường hợp ROM đặc biệt
                // vẫn gặp lỗi verify dù đã tách class — tuyệt đối không để crash app
                try { am.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pi) } catch (e3: Exception) { }
            }
        }

        fun cancelDailySchedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            SCHEDULE_HOURS.forEachIndexed { index, _ ->
                am.cancel(buildPendingIntent(context, index))
            }
        }

        private fun scheduleNext(context: Context) {
            setupDailySchedule(context)
        }

        private fun buildPendingIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, ScheduleReceiver::class.java).apply {
                action = "com.wifimonitor.SCHEDULE_CHECK_$requestCode"
            }
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun nextTriggerMs(hour: Int): Long {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                // Nếu giờ đã qua hôm nay → sang ngày mai
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            return cal.timeInMillis
        }

        fun scheduleLabels(): List<String> = listOf("7:00 SA", "11:00 SA", "4:00 CH", "9:00 TỐI")
    }
}
