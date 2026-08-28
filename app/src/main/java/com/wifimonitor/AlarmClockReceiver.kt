package com.wifimonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import java.util.Calendar

/**
 * Nhận broadcast đến giờ báo thức đã đặt.
 * Sử dụng AlarmManager.setAlarmClock để đảm bảo đánh thức máy chính xác trong mọi chế độ tiết kiệm pin
 * và được Android 14 đặc cách cho phép khởi động Foreground Service từ chế độ nền.
 */
class AlarmClockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ringIndex = intent.getIntExtra(EXTRA_RING_INDEX, 0)

        // Giữ WakeLock ngắn hạn để CPU không ngủ trước khi Service khởi chạy xong
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiHotspotMonitor::AlarmReceiverWakeLock")
            wl.acquire(5000L)
        } catch (e: Exception) { }

        if (ringIndex == 0) {
            rescheduleTomorrow(context)
            scheduleReRing(context, 1, RERING_OFFSET_1_MS)
            scheduleReRing(context, 2, RERING_OFFSET_2_MS)
        }

        val si = Intent(context, AlarmRingService::class.java).apply {
            putExtra(AlarmRingService.EXTRA_RING_INDEX, ringIndex)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(si)
            } else {
                context.startService(si)
            }
        } catch (e: Exception) {
            // Fallback nếu có lỗi khởi chạy FGS
            try { context.startService(si) } catch (e2: Exception) { }
        }
    }

    companion object {
        const val EXTRA_RING_INDEX = "ring_index"

        private const val REQ_MAIN = 9000
        private const val REQ_RING_1 = 9001
        private const val REQ_RING_2 = 9002

        // 3 lần kêu: lần 1 (0 phút), lần 2 (+5 phút), lần 3 (+10 phút)
        private const val RERING_OFFSET_1_MS = 5 * 60 * 1000L
        private const val RERING_OFFSET_2_MS = 10 * 60 * 1000L

        fun scheduleAlarm(context: Context, hour: Int, minute: Int) {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putInt("alarm_hour", hour)
                .putInt("alarm_minute", minute)
                .putBoolean("alarm_enabled", true)
                .apply()

            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerMs = nextTriggerMs(hour, minute)
            scheduleExactAlarmClock(context, am, triggerMs, buildPendingIntent(context, REQ_MAIN, 0))
        }

        fun cancelAlarm(context: Context) {
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("alarm_enabled", false).apply()
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildPendingIntent(context, REQ_MAIN, 0))
            cancelRemainingRings(context)
            // Nếu đang kêu thì dừng luôn
            val stopIntent = Intent(context, AlarmRingService::class.java).apply {
                action = AlarmRingService.ACTION_STOP
            }
            try { context.startService(stopIntent) } catch (e: Exception) { }
        }

        fun cancelRemainingRings(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildPendingIntent(context, REQ_RING_1, 1))
            am.cancel(buildPendingIntent(context, REQ_RING_2, 2))
        }

        /** Khôi phục báo thức sau khi khởi động lại máy */
        fun restoreIfEnabled(context: Context) {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("alarm_enabled", false)) return
            val hour = prefs.getInt("alarm_hour", 6)
            val minute = prefs.getInt("alarm_minute", 0)
            scheduleAlarm(context, hour, minute)
        }

        private fun rescheduleTomorrow(context: Context) {
            val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("alarm_enabled", false)) return
            val hour = prefs.getInt("alarm_hour", 6)
            val minute = prefs.getInt("alarm_minute", 0)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_YEAR, 1) // Ngày mai
            }
            scheduleExactAlarmClock(context, am, cal.timeInMillis, buildPendingIntent(context, REQ_MAIN, 0))
        }

        private fun scheduleReRing(context: Context, ringIndex: Int, offsetMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val reqCode = if (ringIndex == 1) REQ_RING_1 else REQ_RING_2
            val pi = buildPendingIntent(context, reqCode, ringIndex)
            scheduleExactAlarmClock(context, am, System.currentTimeMillis() + offsetMs, pi)
        }

        /**
         * Đặt báo thức với AlarmClockInfo - cơ chế chuẩn của Android để đảm bảo
         * chuông luôn đổ đúng giờ và miễn trừ kiểm soát nền Android 14.
         */
        private fun scheduleExactAlarmClock(context: Context, am: AlarmManager, triggerMs: Long, pi: PendingIntent) {
            try {
                val showIntent = Intent(context, MainActivity::class.java)
                val showPi = PendingIntent.getActivity(
                    context, 0, showIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPi)
                    am.setAlarmClock(alarmClockInfo, pi)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                }
            } catch (e: SecurityException) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    } else {
                        am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi)
                    }
                } catch (e2: Exception) { }
            } catch (e: Exception) { }
        }

        private fun buildPendingIntent(context: Context, requestCode: Int, ringIndex: Int): PendingIntent {
            val intent = Intent(context, AlarmClockReceiver::class.java).apply {
                action = "com.wifimonitor.ALARM_CLOCK_$requestCode"
                putExtra(EXTRA_RING_INDEX, ringIndex)
            }
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private fun nextTriggerMs(hour: Int, minute: Int): Long {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            return cal.timeInMillis
        }
    }
}
