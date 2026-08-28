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
import android.os.PowerManager
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
    private var hotspotReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

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
        const val PREF_BATTERY_MP3_URI = "battery_mp3_uri"
        const val BATTERY_THRESHOLD = 50 // Mặc định cảnh báo khi pin <= 50%
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        initWakeLock()
        registerHotspotReceiver()
        registerBatteryReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        startForeground(FG_ID, buildFgNotification(lastHotspotOn))

        stopPolling()

        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
        val useSchedule = prefs.getBoolean("use_schedule", false) // Mặc định giám sát liên tục theo phút để không bị bỏ sót

        when {
            trigger == TRIGGER_SCHEDULE -> {
                pollHotspot()
            }
            useSchedule || trigger == TRIGGER_SCHEDULE_MODE -> {
                // Chế độ lịch trình: vẫn chạy kiểm tra ngay 1 lần
                pollHotspot()
            }
            else -> {
                val intervalMin = intent?.getIntExtra(EXTRA_INTERVAL, DEFAULT_INTERVAL)
                    ?: prefs.getInt("interval", DEFAULT_INTERVAL)
                startPolling(intervalMin * 60 * 1000L)
            }
        }

        // Đọc ngay trạng thái pin hiện tại
        checkBatteryStateImmediately()

        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopPolling()
        stopMp3()
        unregisterBatteryReceiver()
        unregisterHotspotReceiver()
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = null

    // ── WakeLock Management ──────────────────────────────────────────

    private fun initWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WifiHotspotMonitor::ServiceWakeLock").apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) { }
    }

    private fun acquireWakeLock(timeoutMs: Long = 5000L) {
        try {
            wakeLock?.let {
                if (!it.isHeld) it.acquire(timeoutMs)
            }
        } catch (e: Exception) { }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) { }
    }

    // ── Realtime Hotspot Broadcast Receiver ──────────────────────────

    private fun registerHotspotReceiver() {
        hotspotReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                acquireWakeLock(3000L)
                pollHotspot()
            }
        }
        val filter = IntentFilter().apply {
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.net.conn.TETHER_STATE_CHANGED")
            addAction("android.net.wifi.WIFI_STATE_CHANGED")
        }
        // SYSTEM BROADCAST: Phải dùng RECEIVER_EXPORTED trên Android 13+ (API 33/34)
        ContextCompat.registerReceiver(
            this,
            hotspotReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun unregisterHotspotReceiver() {
        try { hotspotReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        hotspotReceiver = null
    }

    // ── Polling ──────────────────────────────────────────────────────

    private fun startPolling(intervalMs: Long) {
        stopPolling()
        pollRunnable = object : Runnable {
            override fun run() {
                acquireWakeLock(3000L)
                pollHotspot()
                handler.postDelayed(this, intervalMs)
            }
        }
        // Kiểm tra ngay lập tức sau 1 giây
        handler.postDelayed(pollRunnable!!, 1000L)
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
                processBatteryIntent(intent)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // SYSTEM BROADCAST: Phải dùng RECEIVER_EXPORTED trên Android 13+ (API 33/34)
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun checkBatteryStateImmediately() {
        try {
            val stickyIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            stickyIntent?.let { processBatteryIntent(it) }
        } catch (e: Exception) { }
    }

    private fun processBatteryIntent(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return

        val percent = level * 100 / scale
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val threshold = prefs.getInt("battery_threshold", BATTERY_THRESHOLD)
        val alertEnabled = prefs.getBoolean("battery_alert_enabled", true)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        if (percent <= threshold && !isCharging && alertEnabled) {
            if (!batteryAlertSent) {
                batteryAlertSent = true
                acquireWakeLock(5000L)
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

    private fun unregisterBatteryReceiver() {
        try { batteryReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        batteryReceiver = null
    }

    // ── MP3 / Audio Player ───────────────────────────────────────────

    private fun playMp3() {
        val uriStr = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_MP3_URI, null)
        playUriOrDefaultSound(uriStr, isAlarm = false)
    }

    private fun playBatteryMp3() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PREF_BATTERY_MP3_URI, null)
            ?: prefs.getString(PREF_MP3_URI, null)
        playUriOrDefaultSound(uriStr, isAlarm = false)
    }

    /**
     * Phát âm thanh cảnh báo bằng MediaPlayer ổn định (không dùng RingtoneManager trần
     * vì Ringtone bị Garbage Collection thu hồi làm mất tiếng).
     */
    private fun playUriOrDefaultSound(uriStr: String?, isAlarm: Boolean) {
        stopMp3()
        try {
            val soundUri: Uri = if (!uriStr.isNullOrEmpty()) {
                Uri.parse(uriStr)
            } else {
                val ringType = if (isAlarm) RingtoneManager.TYPE_ALARM else RingtoneManager.TYPE_NOTIFICATION
                RingtoneManager.getDefaultUri(ringType) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (isAlarm) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(applicationContext, soundUri)
                isLooping = false
                setOnPreparedListener { mp ->
                    acquireWakeLock(10000L)
                    mp.start()
                }
                setOnCompletionListener {
                    stopMp3()
                    releaseWakeLock()
                }
                setOnErrorListener { _, _, _ ->
                    stopMp3()
                    releaseWakeLock()
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            stopMp3()
        }
    }

    private fun stopMp3() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) { }
        mediaPlayer = null
    }

    // ── Notifications ─────────────────────────────────────────────────

    private fun buildFgNotification(isOn: Boolean?, battery: Int? = null): Notification {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val mode = if (prefs.getBoolean("use_schedule", false)) "Lịch trình" else "Theo phút"
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
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
            .setContentTitle("🔋 Pin yếu — còn $percent%")
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
                NotificationChannel(CH_FG, "Giám sát nền", NotificationManager.IMPORTANCE_LOW)
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
