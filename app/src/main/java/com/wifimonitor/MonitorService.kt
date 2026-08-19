package com.wifimonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var lastHotspotOn: Boolean? = null
    private var alertCount = 0  // số lần đã cảnh báo

    companion object {
        var running = false
            private set

        const val FG_ID = 1001
        const val ALERT_ID = 1002
        const val CH_FG = "ch_fg"
        const val CH_ALERT = "ch_alert"
        const val EXTRA_INTERVAL = "interval"
        const val DEFAULT_INTERVAL = 5
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val intervalMin = intent?.getIntExtra(EXTRA_INTERVAL, DEFAULT_INTERVAL) ?: DEFAULT_INTERVAL
        startForeground(FG_ID, buildFgNotification(null))
        startPolling(intervalMin * 60 * 1000L)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        pollRunnable?.let { handler.removeCallbacks(it) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ── Polling ──────────────────────────────────────────────────────

    private fun startPolling(intervalMs: Long) {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = object : Runnable {
            override fun run() {
                pollHotspot()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(pollRunnable!!, 3000L)
    }

    private fun pollHotspot() {
        val isOn = HotspotUtils.isEnabled(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FG_ID, buildFgNotification(isOn))

        when {
            // Hotspot vừa tắt hoặc vẫn đang tắt → tăng đếm và gửi alert
            !isOn -> {
                alertCount++
                sendAlertNotification(nm, alertCount)
            }
            // Hotspot đang BẬT → xoá thông báo và reset đếm
            isOn -> {
                nm.cancel(ALERT_ID)
                alertCount = 0
            }
        }
        lastHotspotOn = isOn
    }

    // ── Notifications ─────────────────────────────────────────────────

    private fun buildFgNotification(isOn: Boolean?): Notification {
        val text = when (isOn) {
            true  -> "Hotspot đang BẬT"
            false -> "Hotspot đang TẮT"
            null  -> "Đang kiểm tra..."
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_FG)
            .setContentTitle("Giám sát Hotspot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun sendAlertNotification(nm: NotificationManager, count: Int) {
        val settingsIntent = Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            this, 10, settingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setContentTitle("⚠️ Hotspot WiFi đã bị tắt! ($count)")
            .setContentText("Nhấn để vào cài đặt bật lại Hotspot.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Hotspot WiFi đang TẮT.\nNhấn \"Bật Hotspot\" để vào cài đặt và bật lại ngay.")
                    .setSummaryText("Lần cảnh báo thứ $count")
            )
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_preferences, "Bật Hotspot", pi)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)          // hiện đầu danh sách
            .setCategory(NotificationCompat.CATEGORY_ALARM)        // hiện trên màn hình khóa
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)   // hiện đầy đủ trên lock screen
            .setSound(soundUri)                                     // tiếng chuông
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .setOnlyAlertOnce(false)                               // luôn kêu mỗi lần cập nhật
            .build()

        nm.notify(ALERT_ID, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel nền — im lặng
            nm.createNotificationChannel(
                NotificationChannel(CH_FG, "Giám sát nền", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )

            // Channel alert — âm thanh + rung + hiện lock screen
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ALERT,
                    "Cảnh báo Hotspot tắt",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    enableLights(true)
                    setSound(soundUri, audioAttr)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }
}
