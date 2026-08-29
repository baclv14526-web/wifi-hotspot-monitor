package com.wifimonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Nhận broadcast đến giờ báo thức đã đặt. Khi kêu lần đầu (ringIndex = 0):
 * - Lên lịch lại cho NGÀY MAI ngay (báo thức lặp lại hàng ngày)
 * - Lên lịch thêm 2 lần kêu lại (ringIndex = 1, 2) cách nhau trong vòng 15 phút,
 *   để tổng cộng kêu tối đa 3 lần nếu người dùng không bấm "Dừng báo thức".
 */
class AlarmClockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ringIndex = intent.getIntExtra(EXTRA_RING_INDEX, 0)

        if (ringIndex == 0) {
            rescheduleTomorrow(context)
            scheduleReRing(context, 1, RERING_OFFSET_1_MS)
            scheduleReRing(context, 2, RERING_OFFSET_2_MS)
        }

        val si = Intent(context, AlarmRingService::class.java).apply {
            putExtra(AlarmRingService.EXTRA_RING_INDEX, ringIndex)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(si)
        } else {
            context.startService(si)
        }
    }

    companion object {
        const val EXTRA_RING_INDEX = "ring_index"

        private const val REQ_MAIN = 9000
        private const val REQ_RING_1 = 9001
        private const val REQ_RING_2 = 9002

        // Tổng 3 lần kêu trong vòng 15 phút: lần 1 (0 phút), lần 2 (+5 phút), lần 3 (+10 phút)
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
            scheduleExact(am, triggerMs, buildPendingIntent(context, REQ_MAIN, 0))
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
            context.startService(stopIntent)
        }

        fun cancelRemainingRings(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(buildPendingIntent(context, REQ_RING_1, 1))
            am.cancel(buildPendingIntent(context, REQ_RING_2, 2))
        }

        /** Gọi lại sau khi reboot máy, để khôi phục báo thức đã bật trước đó. */
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
                add(Calendar.DAY_OF_YEAR, 1) // luôn là ngày mai vì hôm nay vừa kêu
            }
            scheduleExact(am, cal.timeInMillis, buildPendingIntent(context, REQ_MAIN, 0))
        }

        private fun scheduleReRing(context: Context, ringIndex: Int, offsetMs: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val reqCode = if (ringIndex == 1) REQ_RING_1 else REQ_RING_2
            val pi = buildPendingIntent(context, reqCode, ringIndex)
            scheduleExact(am, System.currentTimeMillis() + offsetMs, pi)
        }

        private fun scheduleExact(am: AlarmManager, triggerMs: Long, pi: PendingIntent) {
            try {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                        // FIX QUAN TRỌNG: dùng Api31Compat thay vì gọi trực tiếp
                        // am.canScheduleExactAlarms() — gọi trực tiếp có thể gây
                        // NoSuchMethodError/VerifyError trên Android 9 ColorOS dù
                        // đã bọc if (SDK_INT >= S). Xem giải thích chi tiết trong
                        // file Api31Compat.kt.
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
                try { am.set(AlarmManager.RTC_WAKEUP, triggerMs, pi) } catch (e2: Exception) { }
            } catch (e: Exception) {
            } catch (e: Throwable) {
                // Bắt cả lỗi verify hiếm gặp, tuyệt đối không để crash app
                try { am.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pi) } catch (e2: Exception) { }
            }
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
