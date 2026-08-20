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
    private var alertCount = 0

    companion object {
        var running = false
            private set

        const val FG_ID = 1001
        const val ALERT_ID = 1002
        const val CH_FG = "ch_fg"
        const val CH_ALERT = "ch_alert"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_TRIGGER = "trigger"
        const val TRIGGER_SCHEDULE = "schedule"
        const val DEFAULT_INTERVAL = 5

        // Tự động xóa notification sau 10 lần cảnh báo
        const val MAX_ALERT_COUNT = 10
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val intervalMin = intent?.getIntExtra(EXTRA_INTERVAL, DEFAULT_INTERVAL) ?: DEFAULT_INTERVAL
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)

        startForeground(FG_ID, buildFgNotification(null))

        if (trigger == TRIGGER_SCHEDULE) {
            pollHotspot()
        } else {
            startPolling(intervalMin * 60 * 1000L)
        }
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
            !isOn -> {
                alertCount++
                when {
                    // 1–9 lần: báo bình thường có âm thanh
                    alertCount < MAX_ALERT_COUNT -> {
                        sendAlertNotification(nm, alertCount, isFinal = false)
                    }
                    // Đúng lần thứ 10: báo lần cuối, im lặng, tự xóa sau 5 giây
                    alertCount == MAX_ALERT_COUNT -> {
                        sendAlertNotification(nm, alertCount, isFinal = true)
                        handler.postDelayed({ nm.cancel(ALERT_ID) }, 5000L)
                    }
                    // Trên 10 lần: im lặng hoàn toàn
                    else -> { }
                }
            }
            // Hotspot bật lại → xóa alert, reset đếm
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
            false -> "Hotspot đang TẮT — đã cảnh báo $alertCount lần"
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
            .setSmallIcon(R.drawable.ic_wifi_notify)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun sendAlertNotification(nm: NotificationManager, count: Int, isFinal: Boolean) {
        val pi = PendingIntent.getActivity(
            this, 10,
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val title = if (isFinal)
            "⚠️ Hotspot TẮT — Đã ngừng nhắc ($count/$MAX_ALERT_COUNT)"
        else
            "⚠️ Hotspot WiFi đã bị tắt! ($count/$MAX_ALERT_COUNT)"

        val bodyText = if (isFinal)
            "Đã cảnh báo $MAX_ALERT_COUNT lần. Thông báo sẽ tự xóa sau 5 giây.\nNhấn để vào cài đặt bật lại Hotspot."
        else
            "Hotspot WiFi đang TẮT.\nNhấn \"Bật Hotspot\" để vào cài đặt và bật lại ngay."

        val builder = NotificationCompat.Builder(this, CH_ALERT)
            .setContentTitle(title)
            .setContentText("Nhấn để vào cài đặt bật lại Hotspot.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(bodyText)
                    .setSummaryText("Lần cảnh báo thứ $count / $MAX_ALERT_COUNT")
            )
            .setSmallIcon(R.drawable.ic_wifi_notify)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_preferences, "Bật Hotspot", pi)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)

        if (isFinal) {
            // Lần cuối: không âm thanh, không rung
            builder.setSilent(true)
        } else {
            builder
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 400, 200, 400))
        }

        nm.notify(ALERT_ID, builder.build())
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(CH_FG, "Giám sát nền", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttr = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Cảnh báo Hotspot tắt", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
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
