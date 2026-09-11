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
    private var mediaPlayer: MediaPlayer? = null

    // Hotspot alert ring cycle (3 lần / 15 phút)
    private var hotspotRingStarted = false       // đã bắt đầu chu kỳ kêu chưa
    private var hotspotRing2Runnable: Runnable? = null
    private var hotspotRing3Runnable: Runnable? = null

    // Battery alert ring cycle (3 lần / 9 phút)
    private var batteryAlertSent = false
    private var batteryRingCount = 0
    private var batteryRing2Runnable: Runnable? = null
    private var batteryRing3Runnable: Runnable? = null
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
        // Hotspot alert: tối đa 3 lần trong 15 phút (giống cảnh báo pin),
        // mỗi lần cách nhau 4.5 phút, sau đó dừng hẳn đến khi hotspot bật lại
        const val HOTSPOT_MAX_RINGS  = 3
        const val HOTSPOT_RING_INTERVAL_MS = 4 * 60 * 1000L + 30 * 1000L  // 4 phút 30 giây
        const val PREF_MP3_URI = "mp3_uri"
        const val PREF_BATTERY_MP3_URI = "battery_mp3_uri"
        const val BATTERY_THRESHOLD = 20

        // Cảnh báo pin: kêu tối đa 3 lần, mỗi lần cách nhau 4.5 phút (tổng 9 phút)
        const val BATTERY_MAX_RINGS = 3
        const val BATTERY_RING_INTERVAL_MS = 4 * 60 * 1000L + 30 * 1000L  // 4 phút 30 giây
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
        stopPolling()

        // Khởi động / gia hạn watchdog mỗi khi service được start hoặc restart.
        // Watchdog ping mỗi 15 phút để kiểm tra service còn sống không.
        WatchdogReceiver.start(this)

        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
        val useSchedule = prefs.getBoolean("use_schedule", true)

        when {
            trigger == TRIGGER_SCHEDULE -> {
                pollHotspot()
            }
            useSchedule || trigger == TRIGGER_SCHEDULE_MODE -> {
                // Chỉ giữ foreground notification, AlarmManager lo phần lịch trình
            }
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
        // Dừng watchdog khi service bị dừng CHỦ ĐỘNG (người dùng bấm Dừng).
        // Nếu bị kill bởi OS (không qua onDestroy), watchdog vẫn chạy và sẽ
        // restart service sau tối đa 15 phút — đây chính là mục đích của watchdog.
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto_start", true)) {
            // Người dùng đã tắt auto_start → dừng luôn watchdog
            WatchdogReceiver.stop(this)
        }
        stopPolling()
        cancelHotspotRingCycle()
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
                // Hotspot đang TẮT: chỉ bắt đầu chu kỳ kêu khi chưa bắt đầu lần này
                // (các lần poll tiếp theo trong khi hotspot vẫn tắt sẽ bị bỏ qua —
                // ring cycle tự lên lịch bằng Handler, không phụ thuộc polling interval)
                if (!hotspotRingStarted) {
                    hotspotRingStarted = true
                    startHotspotRingCycle()
                }
            }
            isOn -> {
                // Hotspot BẬT lại → dừng mọi chuông còn lại, reset để chu kỳ mới
                cancelHotspotRingCycle()
                nm.cancel(ALERT_ID)
                stopMp3()
            }
        }
        lastHotspotOn = isOn
    }

    /**
     * Bắt đầu chu kỳ cảnh báo Hotspot 3 lần trong 15 phút:
     * - Lần 1 (ngay lập tức): gửi alert + phát nhạc
     * - Lần 2 (+4.5 phút):    gửi alert + phát nhạc
     * - Lần 3 (+9 phút):      gửi alert cuối + phát nhạc + xóa notification sau 5 giây
     * Sau đó im lặng hoàn toàn cho đến khi hotspot bật lại.
     */
    private fun startHotspotRingCycle() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Lần 1 — ngay lập tức
        sendHotspotAlert(nm, ringIndex = 1, isFinal = false)
        playMp3()

        // Lần 2 — sau 4.5 phút
        hotspotRing2Runnable = Runnable {
            if (hotspotRingStarted) {
                sendHotspotAlert(nm, ringIndex = 2, isFinal = false)
                playMp3()
            }
        }

        // Lần 3 — sau 9 phút, rồi tắt hẳn
        hotspotRing3Runnable = Runnable {
            if (hotspotRingStarted) {
                sendHotspotAlert(nm, ringIndex = 3, isFinal = true)
                playMp3()
                handler.postDelayed({ nm.cancel(ALERT_ID) }, 5000L)
            }
        }

        handler.postDelayed(hotspotRing2Runnable!!, HOTSPOT_RING_INTERVAL_MS)
        handler.postDelayed(hotspotRing3Runnable!!, HOTSPOT_RING_INTERVAL_MS * 2)
    }

    private fun cancelHotspotRingCycle() {
        hotspotRingStarted = false
        hotspotRing2Runnable?.let { handler.removeCallbacks(it) }
        hotspotRing3Runnable?.let { handler.removeCallbacks(it) }
        hotspotRing2Runnable = null
        hotspotRing3Runnable = null
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
                        // Pin vừa xuống dưới ngưỡng → bắt đầu chu kỳ 3 lần kêu
                        batteryAlertSent = true
                        batteryRingCount = 0
                        startBatteryRingCycle(percent)
                    }
                } else if (percent > threshold || isCharging) {
                    // Pin đã sạc lên / đang sạc → reset hoàn toàn để báo lại lần sau
                    cancelBatteryRingCycle()
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
        cancelBatteryRingCycle()
        try { batteryReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        batteryReceiver = null
    }

    /**
     * Bắt đầu chu kỳ kêu chuông cảnh báo pin:
     * - Lần 1: ngay lập tức
     * - Lần 2: sau 4 phút 30 giây
     * - Lần 3: sau 9 phút (4.5 + 4.5)
     * - Sau lần 3: xóa notification, không kêu nữa cho đến khi pin sạc lên rồi xuống lại
     */
    private fun startBatteryRingCycle(percent: Int) {
        // Lần 1 — ngay lập tức
        doOneBatteryRing(percent, ringIndex = 1)

        // Lần 2 — sau 4.5 phút
        batteryRing2Runnable = Runnable {
            if (batteryAlertSent) doOneBatteryRing(percent, ringIndex = 2)
        }
        // Lần 3 — sau 9 phút, rồi tắt hẳn notification
        batteryRing3Runnable = Runnable {
            if (batteryAlertSent) {
                doOneBatteryRing(percent, ringIndex = 3)
                // Sau lần 3: đợi nhạc phát xong rồi xóa notification (5 giây)
                handler.postDelayed({
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(BATTERY_ALERT_ID)
                }, 5000L)
            }
        }

        handler.postDelayed(batteryRing2Runnable!!, BATTERY_RING_INTERVAL_MS)
        handler.postDelayed(batteryRing3Runnable!!, BATTERY_RING_INTERVAL_MS * 2)
    }

    private fun doOneBatteryRing(percent: Int, ringIndex: Int) {
        batteryRingCount = ringIndex
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sendBatteryAlert(percent, ringIndex)
        playBatteryMp3()
    }

    private fun cancelBatteryRingCycle() {
        batteryRing2Runnable?.let { handler.removeCallbacks(it) }
        batteryRing3Runnable?.let { handler.removeCallbacks(it) }
        batteryRing2Runnable = null
        batteryRing3Runnable = null
        batteryRingCount = 0
    }

    // ── MP3 Player ───────────────────────────────────────────────────

    private fun playMp3() {
        if (isQuietHours()) return  // Im lặng ban đêm — notification vẫn hiện, chỉ tắt âm
        val uriStr = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_MP3_URI, null)
        playUriOrDefaultRingtone(uriStr)
    }

    private fun playBatteryMp3() {
        if (isQuietHours()) return  // Im lặng ban đêm — notification vẫn hiện, chỉ tắt âm
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val uriStr = prefs.getString(PREF_BATTERY_MP3_URI, null)
            ?: prefs.getString(PREF_MP3_URI, null)
        playUriOrDefaultRingtone(uriStr)
    }

    /**
     * Kiểm tra thời điểm hiện tại có nằm trong khung giờ im lặng ban đêm không.
     * Hỗ trợ khung giờ vắt qua nửa đêm (ví dụ 22:00 – 06:00).
     * Trả về false nếu người dùng chưa bật tính năng này.
     */
    private fun isQuietHours(): Boolean {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("quiet_hours_enabled", false)) return false

        val startH = prefs.getInt("quiet_start_hour", 22)
        val startM = prefs.getInt("quiet_start_minute", 0)
        val endH   = prefs.getInt("quiet_end_hour", 6)
        val endM   = prefs.getInt("quiet_end_minute", 0)

        val cal = java.util.Calendar.getInstance()
        val nowMinutes  = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                          cal.get(java.util.Calendar.MINUTE)
        val startMinutes = startH * 60 + startM
        val endMinutes   = endH   * 60 + endM

        return if (startMinutes <= endMinutes) {
            // Khung giờ KHÔNG vắt qua nửa đêm (ví dụ 01:00 – 06:00)
            nowMinutes in startMinutes until endMinutes
        } else {
            // Khung giờ vắt qua nửa đêm (ví dụ 22:00 – 06:00)
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
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
            false -> if (hotspotRingStarted) "Hotspot TẮT — đang cảnh báo" else "Hotspot TẮT"
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

    private fun sendHotspotAlert(nm: NotificationManager, ringIndex: Int, isFinal: Boolean) {
        val pi = PendingIntent.getActivity(
            this, 10,
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (isFinal)
            "⚠️ Hotspot TẮT — Đã ngừng nhắc ($ringIndex/$HOTSPOT_MAX_RINGS)"
        else
            "⚠️ Hotspot WiFi đã bị tắt! ($ringIndex/$HOTSPOT_MAX_RINGS)"
        val body = if (isFinal)
            "Đã cảnh báo $HOTSPOT_MAX_RINGS lần trong 15 phút. Thông báo tự xóa sau 5 giây."
        else
            "Hotspot WiFi đang TẮT.\nNhấn \"Bật Hotspot\" để vào cài đặt và bật lại ngay."
        val hasMp3 = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_MP3_URI, null) != null
        val quietNow = isQuietHours()

        nm.notify(ALERT_ID, NotificationCompat.Builder(this, CH_ALERT)
            .setContentTitle(title)
            .setContentText(
                when {
                    quietNow  -> "🌙 Im lặng ban đêm — không kêu chuông"
                    hasMp3    -> "🎵 Đang phát nhạc cảnh báo..."
                    else      -> "Nhấn để vào cài đặt bật lại Hotspot."
                }
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)
                .setSummaryText("Lần cảnh báo thứ $ringIndex / $HOTSPOT_MAX_RINGS"))
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

    private fun sendBatteryAlert(percent: Int, ringIndex: Int = 1) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 20,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val prefs2 = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val hasMp3 = prefs2.getString(PREF_BATTERY_MP3_URI, null) != null
            || prefs2.getString(PREF_MP3_URI, null) != null

        val isFinal = ringIndex >= BATTERY_MAX_RINGS
        val title = if (isFinal)
            "🔋 Pin sắp hết $percent% — Đã ngừng nhắc ($ringIndex/$BATTERY_MAX_RINGS)"
        else
            "🔋 Pin sắp hết — còn $percent%! ($ringIndex/$BATTERY_MAX_RINGS)"
        val body = if (isFinal)
            "Đã cảnh báo $BATTERY_MAX_RINGS lần. Hãy cắm sạc ngay! Thông báo sẽ tự xóa."
        else
            "Pin điện thoại còn $percent%.\nHãy cắm sạc để tránh gián đoạn Hotspot WiFi."

        nm.notify(BATTERY_ALERT_ID, NotificationCompat.Builder(this, CH_BATTERY)
            .setContentTitle(title)
            .setContentText(if (hasMp3) "🎵 Đang phát nhạc cảnh báo..." else "Hãy cắm sạc ngay!")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(body)
                .setSummaryText("Lần cảnh báo thứ $ringIndex / $BATTERY_MAX_RINGS"))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
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
