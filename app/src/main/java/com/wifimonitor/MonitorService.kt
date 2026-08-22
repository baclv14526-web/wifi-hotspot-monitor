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

class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var lastHotspotOn: Boolean? = null
    private var alertCount = 0
    private var mediaPlayer: MediaPlayer? = null

    // Pin: tránh báo lặp lại khi pin vẫn ở mức thấp
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
        const val TRIGGER_SCHEDULE = "schedule"
        const val TRIGGER_SCHEDULE_MODE = "schedule_mode"
        const val DEFAULT_INTERVAL = 5
        const val MAX_ALERT_COUNT = 10
        const val PREF_MP3_URI = "mp3_uri"
        const val BATTERY_THRESHOLD = 20  // % pin
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        registerBatteryReceiver()
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
        stopMp3()
        unregisterBatteryReceiver()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ── Battery Receiver ─────────────────────────────────────────────

    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level < 0 || scale <= 0) return

                val percent = (level * 100 / scale)
                val threshold = getSharedPreferences("prefs", Context.MODE_PRIVATE).getInt("battery_threshold", BATTERY_THRESHOLD)
                val alertEnabled = getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("battery_alert_enabled", true)
                val isCharging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let {
                    it == BatteryManager.BATTERY_STATUS_CHARGING ||
                    it == BatteryManager.BATTERY_STATUS_FULL
                }

                if (percent <= threshold && !isCharging && alertEnabled) {
                    if (!batteryAlertSent) {
                        batteryAlertSent = true
                        sendBatteryAlert(percent)
                        playMp3()
                    }
                } else {
                    // Pin đã sạc lên lại hoặc đang sạc → reset để báo lại lần sau
                    if (percent > threshold || isCharging) {
                        batteryAlertSent = false
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(BATTERY_ALERT_ID)
                    }
                }

                // Cập nhật foreground notification với % pin hiện tại
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(FG_ID, buildFgNotification(lastHotspotOn, percent))
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    private fun unregisterBatteryReceiver() {
        try {
            batteryReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) { }
        batteryReceiver = null
    }

    // ── Polling Hotspot ──────────────────────────────────────────────

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

    // ── MP3 Player ───────────────────────────────────────────────────

    private fun playMp3() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PREF_MP3_URI, null)
        stopMp3()
        try {
            if (uriStr != null) {
                val uri = Uri.parse(uriStr)
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(applicationContext, uri)
                    isLooping = false
                    prepare()
                    start()
                    setOnCompletionListener { stopMp3() }
                }
            } else {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                RingtoneManager.getRingtone(applicationContext, ringtoneUri).play()
            }
        } catch (e: Exception) { }
    }

    private fun stopMp3() {
        try {
            mediaPlayer?.apply { if (isPlaying) stop(); release() }
        } catch (e: Exception) { }
        mediaPlayer = null
    }

    // ── Notifications ─────────────────────────────────────────────────

    private fun buildFgNotification(isOn: Boolean?, battery: Int? = null): Notification {
        val hotspotText = when (isOn) {
            true  -> "Hotspot BẬT"
            false -> "Hotspot TẮT [$alertCount lần]"
            null  -> "Đang kiểm tra..."
        }
        val batteryText = if (battery != null) " • 🔋$battery%" else ""
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CH_FG)
            .setContentTitle("Giám sát Hotspot")
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

        val n = NotificationCompat.Builder(this, CH_ALERT)
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

        nm.notify(ALERT_ID, n)
    }

    private fun sendBatteryAlert(percent: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hasMp3 = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_MP3_URI, null) != null

        val n = NotificationCompat.Builder(this, CH_BATTERY)
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

        nm.notify(BATTERY_ALERT_ID, n)
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
