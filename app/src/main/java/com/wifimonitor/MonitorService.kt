package com.wifimonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var lastHotspotOn: Boolean? = null
    private var alertCount = 0
    private var mediaPlayer: MediaPlayer? = null
    private var batteryAlertSent = false
    private var batteryReceiver: BroadcastReceiver? = null

    companion object {
        var running = false
            private set

        const val FG_ID = 1001
        const val ALERT_ID = 1002
        const val BATTERY_ALERT_ID = 1003
        const val CH_FG = "ch_fg"
        const val CH_ALERT = "ch_alert"
        const val CH_BATTERY = "ch_battery"
        const val EXTRA_INTERVAL = "interval"
        const val EXTRA_TRIGGER = "trigger"
        const val TRIGGER_SCHEDULE = "schedule"      // ScheduleReceiver kích hoạt kiểm tra 1 lần
        const val TRIGGER_SCHEDULE_MODE = "schedule_mode" // Chế độ lịch trình (chỉ giữ FG notification)
        const val DEFAULT_INTERVAL = 5
        const val MAX_ALERT_COUNT = 10
        const val PREF_MP3_URI = "mp3_uri"
        const val PREF_BATTERY_MP3_URI = "battery_mp3_uri"
        const val BATTERY_THRESHOLD = 20
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        startForeground(FG_ID, buildFgNotification(null))

        // Luôn dừng polling cũ TRƯỚC khi quyết định chế độ mới.
        // Đây là điểm mấu chốt: đảm bảo không có runnable "theo phút" nào
        // còn sót lại khi service chuyển sang chế độ "lịch trình" (hoặc ngược lại).
        stopPolling()

        // Đọc chế độ từ prefs (bền vững qua START_STICKY restart)
        // intent có thể null khi Android restart service sau khi bị kill
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
        val useSchedule = prefs.getBoolean("use_schedule", true)

        when {
            // ScheduleReceiver kích hoạt → kiểm tra 1 lần ngay lập tức
            trigger == TRIGGER_SCHEDULE -> {
                pollHotspot()
                // Không startPolling — lịch trình do AlarmManager quản lý
            }

            // Chế độ lịch trình (start từ MainActivity hoặc restart sau kill)
            useSchedule || trigger == TRIGGER_SCHEDULE_MODE -> {
                // Chỉ giữ foreground notification, KHÔNG polling.
                // stopPolling() đã được gọi ở trên rồi, không cần gọi lại.
                // AlarmManager sẽ kích hoạt ScheduleReceiver đúng giờ.
            }

            // Chế độ theo phút (start từ MainActivity hoặc restart sau kill)
            else -> {
                val intervalMin = intent?.getIntExtra(EXTRA_INTERVAL, DEFAULT_INTERVAL)
                    ?: prefs.getInt("interval", DEFAULT_INTERVAL)
                startPolling(intervalMin * 60 * 1000L)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopPolling()
        stopMp3()
        unregisterBatteryReceiver()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ── Polling ──────────────────────────────────────────────────────

    private fun startPolling(intervalMs: Long) {
        stopPolling()
        pollRunnable = object : Runnable {
            override fun run() {
                pollHotspot()
                handler.postDelayed(this, intervalMs)
            }
        }
        // Kiểm tra ngay lập tức sau 3 giây
        handler.postDelayed(pollRunnable!!, 3000L)
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
    }

    private fun pollHotspot() {
        val isOn = HotspotUtils.isEnabled(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FG_ID, buildFgNotification(isOn))

        when {
            !isOn -> {
                alertCount++
                when {
                    alertCount < MAX_ALERT_COUNT -> {
                        sendHotspotAlert(nm, alertCount, isFinal = false)
                        playMp3()
                    }
                    alertCount == MAX_ALERT_COUNT -> {
                        sendHotspotAlert(nm, alertCount, isFinal = true)
                        playMp3()
                        handler.postDelayed({ nm.cancel(ALERT_ID) }, 5000L)
                    }
                    else -> { }
                }
            }
            isOn -> {
                nm.cancel(ALERT_ID)
                stopMp3()
                alertCount = 0
            }
        }
        lastHotspotOn = isOn
    }

    // ── Battery Receiver ─────────────────────────────────────────────

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return

                val percent = level * 100 / scale
                val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
                val threshold = prefs.getInt("battery_threshold", BATTERY_THRESHOLD)
                val alertEnabled = prefs.getBoolean("battery_alert_enabled", true)
                val isCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                    it == BatteryManager.BATTERY_STATUS_CHARGING ||
                    it == BatteryManager.BATTERY_STATUS_FULL
                }

                if (percent <= threshold && !isCharging && alertEnabled) {
                    if (!batteryAlertSent) {
                        batteryAlertSent = true
                        sendBatteryAlert(percent)
                        playBatteryMp3()
                    }
                } else if (percent > threshold || isCharging) {
                    batteryAlertSent = false
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(BATTERY_ALERT_ID)
                }

                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(FG_ID, buildFgNotification(lastHotspotOn, percent))
            }
        }
        // FIX QUAN TRỌNG: từ Android 13+ (targetSdk 34), registerReceiver() bắt buộc
        // phải chỉ định rõ RECEIVER_EXPORTED hoặc RECEIVER_NOT_EXPORTED, nếu không
        // sẽ crash với SecurityException ngay khi service khởi động.
        // ContextCompat.registerReceiver tự xử lý đúng cho mọi phiên bản Android.
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterBatteryReceiver() {
        try { batteryReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        batteryReceiver = null
    }

    // ── MP3 Player ───────────────────────────────────────────────────

    private fun playMp3() {
        val uriStr = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_MP3_URI, null)
        playUriOrDefaultRingtone(uriStr)
    }

    private fun playBatteryMp3() {
        // Ưu tiên file riêng cho pin, fallback về file hotspot, rồi chuông mặc định
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PREF_BATTERY_MP3_URI, null)
            ?: prefs.getString(PREF_MP3_URI, null)
        playUriOrDefaultRingtone(uriStr)
    }

    /**
     * Phát 1 file âm thanh (nếu có uriStr) hoặc chuông thông báo mặc định hệ thống.
     * Dùng prepareAsync() thay vì prepare() đồng bộ để KHÔNG chặn main thread —
     * file nhạc lớn hoặc đọc từ content:// chậm có thể gây giật UI/ANR nếu dùng
     * prepare() đồng bộ ngay trên main thread.
     */
    private fun playUriOrDefaultRingtone(uriStr: String?) {
        stopMp3()
        if (uriStr == null) {
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(applicationContext, uri)?.play()
            } catch (e: Exception) { }
            return
        }
        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(applicationContext, Uri.parse(uriStr))
                isLooping = false
                setOnPreparedListener { mp -> mp.start() }
                setOnCompletionListener { stopMp3() }
                setOnErrorListener { _, _, _ ->
                    // File lỗi/hỏng → dọn dẹp, không crash service
                    stopMp3()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            stopMp3()
            // Fallback về chuông thông báo mặc định nếu file người dùng chọn bị lỗi
            try {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(applicationContext, uri)?.play()
            } catch (e2: Exception) { }
        }
    }

    private fun stopMp3() {
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (e: Exception) { }
        mediaPlayer = null
    }

    // ── Notifications ─────────────────────────────────────────────────

    private fun buildFgNotification(isOn: Boolean?, battery: Int? = null): Notification {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val mode = if (prefs.getBoolean("use_schedule", true)) "Lịch trình" else "Theo phút"
        val hotspotText = when (isOn) {
            true  -> "Hotspot BẬT"
            false -> "Hotspot TẮT [$alertCount lần]"
            null  -> "Chờ kiểm tra..."
        }
        val batteryText = if (battery != null) " • 🔋$battery%" else ""
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_FG)
            .setContentTitle("Giám sát Hotspot [$mode]")
            .setContentText("$hotspotText$batteryText")
            .setSmallIcon(R.drawable.ic_wifi_notify)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun sendHotspotAlert(nm: NotificationManager, count: Int, isFinal: Boolean) {
        val pi = PendingIntent.getActivity(
            this, 10,
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (isFinal)
            "⚠️ Hotspot TẮT — Đã ngừng nhắc ($count/$MAX_ALERT_COUNT)"
        else
            "⚠️ Hotspot WiFi đã bị tắt! ($count/$MAX_ALERT_COUNT)"
        val body = if (isFinal)
            "Đã cảnh báo $MAX_ALERT_COUNT lần. Thông báo tự xóa sau 5 giây."
        else
            "Hotspot WiFi đang TẮT.\nNhấn \"Bật Hotspot\" để vào cài đặt và bật lại ngay."
        val hasMp3 = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_MP3_URI, null) != null

        nm.notify(ALERT_ID, NotificationCompat.Builder(this, CH_ALERT)
            .setContentTitle(title)
            .setContentText(if (hasMp3) "🎵 Đang phát nhạc cảnh báo..." else "Nhấn để vào cài đặt.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)
                .setSummaryText("Lần cảnh báo thứ $count / $MAX_ALERT_COUNT"))
            .setSmallIcon(R.drawable.ic_wifi_notify)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_preferences, "Bật Hotspot", pi)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setSilent(true)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()
        )
    }

    private fun sendBatteryAlert(percent: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prefs2 = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val hasMp3 = prefs2.getString(PREF_BATTERY_MP3_URI, null) != null
            || prefs2.getString(PREF_MP3_URI, null) != null

        nm.notify(BATTERY_ALERT_ID, NotificationCompat.Builder(this, CH_BATTERY)
            .setContentTitle("🔋 Pin sắp hết — còn $percent%")
            .setContentText(if (hasMp3) "🎵 Đang phát nhạc cảnh báo..." else "Hãy cắm sạc ngay!")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Pin điện thoại còn $percent%.\nHãy cắm sạc để tránh gián đoạn Hotspot WiFi."))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setSilent(true)
            .setVibrate(longArrayOf(0, 400, 200, 400))
            .build()
        )
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_FG, "Giám sát nền", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_ALERT, "Cảnh báo Hotspot tắt", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 400, 200, 400)
                        enableLights(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_BATTERY, "Cảnh báo pin yếu", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 400, 200, 400)
                        enableLights(true)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
            )
        }
    }
}
