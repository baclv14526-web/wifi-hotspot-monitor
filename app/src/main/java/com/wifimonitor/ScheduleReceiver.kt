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
        // Kiểm tra ngay khi alarm kích hoạt
        val isOn = HotspotUtils.isEnabled(context)
        if (!isOn) {
            // Đảm bảo service đang chạy để gửi alert
            val si = Intent(context, MonitorService::class.java).apply {
                putExtra(MonitorService.EXTRA_TRIGGER, MonitorService.TRIGGER_SCHEDULE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(si)
            } else {
                context.startService(si)
            }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                }
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
