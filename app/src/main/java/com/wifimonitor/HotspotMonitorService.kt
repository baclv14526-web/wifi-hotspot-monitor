package com.wifimonitor

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService

class HotspotMonitorService : LifecycleService() {

    private lateinit var prefs: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null

    // Theo dõi trạng thái trước đó để chỉ notify khi có thay đổi
    private var lastKnownHotspotState: Boolean? = null
    private var hasNotifiedThisSession = false

    companion object {
        var isRunning = false
            private set

        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002
        const val CHANNEL_ID_FOREGROUND = "hotspot_monitor_fg"
        const val CHANNEL_ID_ALERT = "hotspot_alert"
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("wifi_monitor_prefs", Context.MODE_PRIVATE)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        isRunning = true

        // Hiển thị foreground notification ngay lập tức
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())

        // Bắt đầu vòng lặp kiểm tra
        startCheckingLoop()

        // START_STICKY: Android sẽ khởi động lại service nếu bị kill
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        stopCheckingLoop()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    // ── Check loop ──────────────────────────────────────────────────

    private fun startCheckingLoop() {
        stopCheckingLoop() // Dừng loop cũ nếu có

        val intervalMinutes = prefs.getInt(
            MainActivity.PREF_INTERVAL_MINUTES,
            MainActivity.DEFAULT_INTERVAL_MINUTES
        )
        val intervalMs = intervalMinutes * 60 * 1000L

        checkRunnable = object : Runnable {
            override fun run() {
                performCheck()
                handler.postDelayed(this, intervalMs)
            }
        }

        // Kiểm tra ngay lập tức sau 3 giây khởi động
        handler.postDelayed(checkRunnable!!, 3_000L)
    }

    private fun stopCheckingLoop() {
        checkRunnable?.let { handler.removeCallbacks(it) }
        checkRunnable = null
    }

    private fun performCheck() {
        val isHotspotOn = HotspotUtils.isHotspotEnabled(this)

        // Cập nhật foreground notification
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification(isHotspotOn))

        // Chỉ gửi alert khi hotspot VỪA tắt (trước đó bật, bây giờ tắt)
        val wasOn = lastKnownHotspotState
        if (wasOn == true && !isHotspotOn) {
            // Hotspot vừa bị tắt
            sendHotspotOffAlert()
            hasNotifiedThisSession = true
        } else if (isHotspotOn) {
            // Hotspot đang bật, reset flag
            hasNotifiedThisSession = false
            // Xóa notification alert cũ nếu có
            notificationManager.cancel(ALERT_NOTIFICATION_ID)
        }

        lastKnownHotspotState = isHotspotOn
    }

    // ── Notifications ────────────────────────────────────────────────

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            // Channel foreground (ít quan trọng - không làm phiền)
            val fgChannel = NotificationChannel(
                CHANNEL_ID_FOREGROUND,
                "Giám sát Hotspot (nền)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Thông báo dịch vụ chạy nền"
                setShowBadge(false)
            }

            // Channel alert (quan trọng - bật âm thanh)
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "Cảnh báo Hotspot tắt",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Nhắc nhở khi Hotspot WiFi bị tắt"
                enableVibration(true)
                enableLights(true)
            }

            manager.createNotificationChannel(fgChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }

    private fun buildForegroundNotification(hotspotOn: Boolean? = null): Notification {
        val statusText = when (hotspotOn) {
            true  -> "📶 Hotspot đang BẬT"
            false -> "📵 Hotspot đang TẮT"
            null  -> "Đang kiểm tra..."
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
            .setContentTitle("Giám sát Hotspot")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_wifi_hotspot)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun sendHotspotOffAlert() {
        // Intent mở thẳng màn hình Hotspot settings
        val hotspotSettingsIntent = createHotspotSettingsIntent()
        val pendingIntent = PendingIntent.getActivity(
            this, 100,
            hotspotSettingsIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Action button mở app
        val openAppIntent = PendingIntent.getActivity(
            this, 101,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
            .setContentTitle("⚠️ Hotspot WiFi đã bị tắt!")
            .setContentText("Nhấn để vào cài đặt và bật lại Hotspot ngay.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "Hotspot WiFi của bạn đã bị tắt.\n" +
                        "Nhấn \"Bật Hotspot\" để vào cài đặt và bật lại ngay."
                    )
            )
            .setSmallIcon(R.drawable.ic_wifi_off)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_wifi_hotspot, "Bật Hotspot", pendingIntent)
            .addAction(R.drawable.ic_settings, "Mở App", openAppIntent)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun createHotspotSettingsIntent(): Intent {
        // Thứ tự ưu tiên: ColorOS (Oppo/Realme) → Standard → Fallback
        val candidates = listOf(
            // Oppo/Realme ColorOS
            Intent("com.android.settings.TETHER_SETTINGS"),
            // Standard Android Tethering
            Intent().apply {
                setClassName("com.android.settings", "com.android.settings.TetherSettings")
            },
            // MIUI (Xiaomi fallback)
            Intent("android.settings.WIFI_SETTINGS"),
        )

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(packageManager) != null) {
                return intent
            }
        }

        // Absolute fallback
        return Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
