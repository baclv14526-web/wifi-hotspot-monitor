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
        // Hotspot alert: tối đa 3 lần trong 15 phút, mỗi lần cách nhau 7 phút 30 giây
        // (lần 1: 0 phút, lần 2: +7.5 phút, lần 3: +15 phút), sau đó dừng hẳn
        // đến khi hotspot bật lại.
        const val HOTSPOT_MAX_RINGS  = 3
        const val HOTSPOT_RING_INTERVAL_MS = 7 * 60 * 1000L + 30 * 1000L  // 7 phút 30 giây
        const val PREF_MP3_URI = "mp3_uri"
        const val PREF_BATTERY_MP3_URI = "battery_mp3_uri"
        const val BATTERY_THRESHOLD = 20

        // Cảnh báo pin: kêu tối đa 3 lần, mỗi lần cách nhau 4.5 phút (tổng 9 phút)
        const val BATTERY_MAX_RINGS = 3
        const val BATTERY_RING_INTERVAL_MS = 4 * 60 * 1000L + 30 * 1000L  // 4 phút 30 giây

        // Trạng thái chu kỳ cảnh báo được lưu bền vững để khi service bị OS kill
        // giữa chừng (Handler.postDelayed trong bộ nhớ mất trắng), lần khởi động
        // lại (Watchdog / START_STICKY) tự khôi phục đúng vị trí trong chu kỳ.
        private const val PREF_HOTSPOT_RING_ACTIVE = "hotspot_ring_active"
        private const val PREF_HOTSPOT_RING_START_MS = "hotspot_ring_start_ms"
        private const val PREF_HOTSPOT_ALERTED_ONCE = "hotspot_alerted_once"
        private const val PREF_BATTERY_RING_ACTIVE = "battery_ring_active"
        private const val PREF_BATTERY_RING_START_MS = "battery_ring_start_ms"
        private const val PREF_BATTERY_ALERT_SENT = "battery_alert_sent"
        private const val PREF_LAST_MODE = "monitor_last_mode"
        private const val MODE_INTERVAL = 0
        private const val MODE_SCHEDULE = 1

        /**
         * Gọi khi người dùng CHỦ ĐỘNG bấm "Dừng giám sát" — xóa sạch trạng thái
         * chu kỳ bền vững để lần bật sau bắt đầu sạch, không bị khôi phục nhầm.
         * (onDestroy() cố ý KHÔNG xóa vì không phân biệt được OS kill hay người dùng dừng.)
         */
        fun clearPersistedState(context: Context) {
            context.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                .remove(PREF_HOTSPOT_RING_ACTIVE)
                .remove(PREF_HOTSPOT_RING_START_MS)
                .remove(PREF_HOTSPOT_ALERTED_ONCE)
                .remove(PREF_BATTERY_RING_ACTIVE)
                .remove(PREF_BATTERY_RING_START_MS)
                .remove(PREF_BATTERY_ALERT_SENT)
                .remove(PREF_LAST_MODE)
                .apply()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // QUAN TRỌNG: khôi phục TRƯỚC registerBatteryReceiver(). ACTION_BATTERY_CHANGED
        // là sticky broadcast — onReceive() chạy ngay lúc đăng ký; nếu chưa khôi phục
        // batteryAlertSent thì sẽ bắt đầu một chu kỳ mới trùng lặp.
        restoreHotspotStateIfNeeded()
        restoreBatteryStateIfNeeded()
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

        // Chỉ hủy chu kỳ cũ khi ĐỔI CHẾ ĐỘ thật sự (Lịch trình <-> Theo phút), hoặc
        // khi ScheduleReceiver kích hoạt một lần kiểm tra mới (cần chu kỳ mới tinh).
        // KHÔNG hủy vô điều kiện: khi Watchdog/START_STICKY khởi động lại service,
        // chu kỳ vừa được khôi phục trong onCreate() phải được giữ nguyên.
        val newMode = if (trigger == TRIGGER_SCHEDULE || trigger == TRIGGER_SCHEDULE_MODE || useSchedule)
            MODE_SCHEDULE else MODE_INTERVAL
        val lastMode = prefs.getInt(PREF_LAST_MODE, -1)
        if (lastMode != -1 && lastMode != newMode) cancelHotspotRingCycle()
        prefs.edit().putInt(PREF_LAST_MODE, newMode).apply()
        if (trigger == TRIGGER_SCHEDULE) cancelHotspotRingCycle()

        when {
            trigger == TRIGGER_SCHEDULE -> {
                pollHotspot(isScheduleMode = true)
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
        cancelHotspotRingCycle(clearPersisted = false)
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
                pollHotspot(isScheduleMode = false)
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

    /**
     * @param isScheduleMode true nếu được gọi từ chế độ Lịch trình (ScheduleReceiver),
     *   false nếu được gọi từ chế độ Theo phút (polling loop).
     *   - Lịch trình: giữ nguyên chu kỳ cảnh báo 3 lần trong 15 phút.
     *   - Theo phút: chỉ thông báo 1 lần duy nhất, không lặp lại, cho đến khi
     *     hotspot bật lại (theo đúng yêu cầu: "chỉ thông báo 1 lần rồi thôi").
     */
    private fun pollHotspot(isScheduleMode: Boolean) {
        val isOn = HotspotUtils.isEnabled(this)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(FG_ID, buildFgNotification(isOn))

        when {
            !isOn -> {
                // Chỉ xử lý khi CHƯA từng cảnh báo cho lần tắt này —
                // (các lần poll tiếp theo trong khi hotspot vẫn tắt sẽ bị bỏ qua)
                if (!hotspotRingStarted) {
                    hotspotRingStarted = true
                    if (isScheduleMode) {
                        startHotspotRingCycle()
                    } else {
                        // Chế độ theo phút: thông báo đúng 1 lần, không lặp lại
                        sendHotspotAlertOnce(nm)
                        playMp3()
                        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                            .putBoolean(PREF_HOTSPOT_ALERTED_ONCE, true).apply()
                    }
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
     * - Lần 1 (0 phút):    gửi alert + phát nhạc
     * - Lần 2 (+7.5 phút): gửi alert + phát nhạc (có re-check trạng thái)
     * - Lần 3 (+15 phút):  gửi alert cuối + phát nhạc + xóa notification sau 5 giây
     * Mỗi lần kêu lại (2, 3) đều kiểm tra lại HotspotUtils.isEnabled() trước khi
     * kêu — nếu hotspot đã được bật lại giữa chừng thì hủy luôn, không kêu nhầm.
     * Thời điểm bắt đầu được lưu bền vững để khôi phục nếu service bị kill.
     */
    private fun startHotspotRingCycle() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_HOTSPOT_RING_ACTIVE, true)
            .putLong(PREF_HOTSPOT_RING_START_MS, System.currentTimeMillis())
            .apply()

        // Lần 1 — ngay lập tức (0 phút)
        sendHotspotAlert(nm, ringIndex = 1, isFinal = false)
        playMp3()

        scheduleHotspotRing2(nm, HOTSPOT_RING_INTERVAL_MS)
        scheduleHotspotRing3(nm, HOTSPOT_RING_INTERVAL_MS * 2)
    }

    private fun scheduleHotspotRing2(nm: NotificationManager, delayMs: Long) {
        hotspotRing2Runnable = Runnable {
            if (hotspotRingStarted) {
                if (HotspotUtils.isEnabled(this)) {
                    cancelHotspotRingCycle()
                    nm.cancel(ALERT_ID)
                } else {
                    sendHotspotAlert(nm, ringIndex = 2, isFinal = false)
                    playMp3()
                }
            }
        }
        handler.postDelayed(hotspotRing2Runnable!!, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleHotspotRing3(nm: NotificationManager, delayMs: Long) {
        hotspotRing3Runnable = Runnable {
            if (hotspotRingStarted) {
                if (HotspotUtils.isEnabled(this)) {
                    cancelHotspotRingCycle()
                    nm.cancel(ALERT_ID)
                } else {
                    sendHotspotAlert(nm, ringIndex = 3, isFinal = true)
                    playMp3()
                    handler.postDelayed({ nm.cancel(ALERT_ID) }, 5000L)
                    // Chu kỳ hoàn tất tự nhiên: xóa cờ "đang chạy" bền vững, nhưng
                    // GIỮ hotspotRingStarted = true để im lặng đến khi hotspot bật lại
                    // (pollHotspot() thấy isOn sẽ tự gọi cancelHotspotRingCycle()).
                    getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                        .putBoolean(PREF_HOTSPOT_RING_ACTIVE, false)
                        .apply()
                }
            }
        }
        handler.postDelayed(hotspotRing3Runnable!!, delayMs.coerceAtLeast(0L))
    }

    /**
     * Khôi phục trạng thái cảnh báo Hotspot nếu service vừa bị OS kill giữa chừng.
     * - Chế độ Lịch trình: đặt lại lần 2/3 theo mốc thời gian TUYỆT ĐỐI tính từ lúc
     *   chu kỳ bắt đầu ban đầu (tổng vẫn đúng 15 phút, không bị kéo dài do restart).
     * - Chế độ Theo phút: nhớ là đã báo 1 lần, tránh báo trùng cho cùng 1 lần tắt.
     * Luôn re-check trạng thái thực tế; nếu hotspot đã bật lại thì dọn sạch.
     */
    private fun restoreHotspotStateIfNeeded() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val active = prefs.getBoolean(PREF_HOTSPOT_RING_ACTIVE, false)
        val alertedOnce = prefs.getBoolean(PREF_HOTSPOT_ALERTED_ONCE, false)
        if (!active && !alertedOnce) return

        if (HotspotUtils.isEnabled(this)) {
            prefs.edit()
                .putBoolean(PREF_HOTSPOT_RING_ACTIVE, false)
                .putBoolean(PREF_HOTSPOT_ALERTED_ONCE, false)
                .apply()
            return
        }

        hotspotRingStarted = true  // chặn pollHotspot() báo trùng cho lần tắt này
        if (!active) return

        val startMs = prefs.getLong(PREF_HOTSPOT_RING_START_MS, 0L)
        val elapsed = System.currentTimeMillis() - startMs
        // elapsed < 0: đồng hồ hệ thống bị chỉnh lùi → dữ liệu không còn đáng tin
        if (startMs <= 0L || elapsed < 0L) {
            prefs.edit().putBoolean(PREF_HOTSPOT_RING_ACTIVE, false).apply()
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when {
            elapsed < HOTSPOT_RING_INTERVAL_MS -> {
                scheduleHotspotRing2(nm, HOTSPOT_RING_INTERVAL_MS - elapsed)
                scheduleHotspotRing3(nm, HOTSPOT_RING_INTERVAL_MS * 2 - elapsed)
            }
            elapsed < HOTSPOT_RING_INTERVAL_MS * 2 -> {
                // Lần 2 coi như bị lỡ do service chết đúng lúc → bù ngay, rồi hẹn lần 3
                sendHotspotAlert(nm, ringIndex = 2, isFinal = false)
                playMp3()
                scheduleHotspotRing3(nm, HOTSPOT_RING_INTERVAL_MS * 2 - elapsed)
            }
            else -> {
                // Đã quá hạn cả lần 3 (restart quá trễ) → không bù nữa, chỉ dọn cờ
                prefs.edit().putBoolean(PREF_HOTSPOT_RING_ACTIVE, false).apply()
            }
        }
    }

    /**
     * @param clearPersisted true (mặc định) khi chu kỳ kết thúc thật sự (hotspot bật
     *   lại, đổi chế độ...). false khi gọi từ onDestroy() để giữ lại dữ liệu khôi phục.
     */
    private fun cancelHotspotRingCycle(clearPersisted: Boolean = true) {
        hotspotRingStarted = false
        hotspotRing2Runnable?.let { handler.removeCallbacks(it) }
        hotspotRing3Runnable?.let { handler.removeCallbacks(it) }
        hotspotRing2Runnable = null
        hotspotRing3Runnable = null
        if (clearPersisted) {
            getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_HOTSPOT_RING_ACTIVE, false)
                .putBoolean(PREF_HOTSPOT_ALERTED_ONCE, false)
                .apply()
        }
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
                // Dùng chung 1 tham chiếu NotificationManager cho cả 2 nhánh bên dưới
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

                if (percent <= threshold && !isCharging && alertEnabled) {
                    if (!batteryAlertSent) {
                        // Pin vừa xuống dưới ngưỡng → bắt đầu chu kỳ 3 lần kêu
                        batteryAlertSent = true
                        batteryRingCount = 0
                        startBatteryRingCycle(percent)
                    }
                } else if (percent > threshold || isCharging || !alertEnabled) {
                    // Pin đã sạc lên / đang sạc / người dùng đã TẮT cảnh báo pin →
                    // reset hoàn toàn để báo lại lần sau (kể cả khi bật lại lúc pin còn thấp)
                    cancelBatteryRingCycle()
                    batteryAlertSent = false
                    nm.cancel(BATTERY_ALERT_ID)
                }

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
        cancelBatteryRingCycle(clearPersisted = false)
        try { batteryReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) { }
        batteryReceiver = null
    }

    /**
     * Bắt đầu chu kỳ kêu chuông cảnh báo pin:
     * - Lần 1: ngay lập tức
     * - Lần 2: sau 4 phút 30 giây
     * - Lần 3: sau 9 phút (4.5 + 4.5)
     * - Sau lần 3: xóa notification, không kêu nữa cho đến khi pin sạc lên rồi xuống lại
     * Thời điểm bắt đầu được lưu bền vững để khôi phục nếu service bị kill.
     */
    private fun startBatteryRingCycle(percent: Int) {
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putBoolean(PREF_BATTERY_RING_ACTIVE, true)
            .putBoolean(PREF_BATTERY_ALERT_SENT, true)
            .putLong(PREF_BATTERY_RING_START_MS, System.currentTimeMillis())
            .apply()

        doOneBatteryRing(percent, ringIndex = 1)

        scheduleBatteryRing2(BATTERY_RING_INTERVAL_MS)
        scheduleBatteryRing3(BATTERY_RING_INTERVAL_MS * 2)
    }

    /**
     * Re-check % pin + trạng thái sạc MỚI NHẤT. Nếu đã hồi phục (cắm sạc hoặc
     * lên trên ngưỡng) hoặc không đọc được → hủy toàn bộ chu kỳ, trả về null.
     * Ngược lại trả về % pin hiện tại để hiển thị (không dùng giá trị cũ).
     */
    private fun checkBatteryStillLowOrCancel(): Int? {
        val threshold = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getInt("battery_threshold", BATTERY_THRESHOLD)
        val current = currentBatteryStatus()
        if (current == null || current.second || current.first > threshold) {
            cancelBatteryRingCycle()
            batteryAlertSent = false
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(BATTERY_ALERT_ID)
            return null
        }
        return current.first
    }

    private fun scheduleBatteryRing2(delayMs: Long) {
        batteryRing2Runnable = Runnable {
            if (batteryAlertSent) {
                checkBatteryStillLowOrCancel()?.let { doOneBatteryRing(it, ringIndex = 2) }
            }
        }
        handler.postDelayed(batteryRing2Runnable!!, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleBatteryRing3(delayMs: Long) {
        batteryRing3Runnable = Runnable {
            if (batteryAlertSent) {
                checkBatteryStillLowOrCancel()?.let { percent ->
                    doOneBatteryRing(percent, ringIndex = 3)
                    handler.postDelayed({
                        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        nm.cancel(BATTERY_ALERT_ID)
                    }, 5000L)
                    // Hoàn tất tự nhiên: xóa cờ "đang chạy", giữ batteryAlertSent = true
                    // (và cờ bền vững PREF_BATTERY_ALERT_SENT) để im lặng đến khi sạc.
                    getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                        .putBoolean(PREF_BATTERY_RING_ACTIVE, false)
                        .apply()
                }
            }
        }
        handler.postDelayed(batteryRing3Runnable!!, delayMs.coerceAtLeast(0L))
    }

    /**
     * Khôi phục trạng thái cảnh báo pin nếu service vừa bị OS kill giữa chừng.
     * Phải chạy TRƯỚC registerBatteryReceiver() (xem onCreate).
     */
    private fun restoreBatteryStateIfNeeded() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val active = prefs.getBoolean(PREF_BATTERY_RING_ACTIVE, false)
        val sent = prefs.getBoolean(PREF_BATTERY_ALERT_SENT, false)
        if (!active && !sent) return

        batteryAlertSent = true  // sticky broadcast sẽ không coi đây là sự kiện pin yếu mới
        if (!active) return

        val startMs = prefs.getLong(PREF_BATTERY_RING_START_MS, 0L)
        val elapsed = System.currentTimeMillis() - startMs
        if (startMs <= 0L || elapsed < 0L) {
            prefs.edit().putBoolean(PREF_BATTERY_RING_ACTIVE, false).apply()
            return
        }

        when {
            elapsed < BATTERY_RING_INTERVAL_MS -> {
                scheduleBatteryRing2(BATTERY_RING_INTERVAL_MS - elapsed)
                scheduleBatteryRing3(BATTERY_RING_INTERVAL_MS * 2 - elapsed)
            }
            elapsed < BATTERY_RING_INTERVAL_MS * 2 -> {
                // Lần 2 bị lỡ → bù ngay nếu pin vẫn thấp, rồi hẹn lần 3
                checkBatteryStillLowOrCancel()?.let { percent ->
                    doOneBatteryRing(percent, ringIndex = 2)
                    scheduleBatteryRing3(BATTERY_RING_INTERVAL_MS * 2 - elapsed)
                }
            }
            else -> {
                prefs.edit().putBoolean(PREF_BATTERY_RING_ACTIVE, false).apply()
            }
        }
    }

    /**
     * Đọc % pin và trạng thái sạc MỚI NHẤT tại thời điểm gọi (không dùng giá trị
     * cũ từ closure). Trả về Pair(phầnTrămPin, đangSạc) hoặc null nếu không đọc được.
     */
    private fun currentBatteryStatus(): Pair<Int, Boolean>? {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (percent < 0) return null
            val isCharging = bm.isCharging
            percent to isCharging
        } catch (e: Exception) {
            null
        }
    }

    private fun doOneBatteryRing(percent: Int, ringIndex: Int) {
        batteryRingCount = ringIndex
        sendBatteryAlert(percent, ringIndex)
        playBatteryMp3()
    }

    private fun cancelBatteryRingCycle(clearPersisted: Boolean = true) {
        batteryRing2Runnable?.let { handler.removeCallbacks(it) }
        batteryRing3Runnable?.let { handler.removeCallbacks(it) }
        batteryRing2Runnable = null
        batteryRing3Runnable = null
        batteryRingCount = 0
        if (clearPersisted) {
            getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_BATTERY_RING_ACTIVE, false)
                .putBoolean(PREF_BATTERY_ALERT_SENT, false)
                .apply()
        }
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

    /**
     * Gửi cảnh báo Hotspot đúng 1 lần cho chế độ Theo phút — không đánh số
     * "lần X/3" vì sẽ không có lần kêu lại nào cả. Notification vẫn hiển thị
     * cho đến khi hotspot bật lại (không tự xóa sau vài giây như bản Lịch trình).
     */
    private fun sendHotspotAlertOnce(nm: NotificationManager) {
        val pi = PendingIntent.getActivity(
            this, 10,
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val hasMp3 = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_MP3_URI, null) != null
        val quietNow = isQuietHours()

        nm.notify(ALERT_ID, NotificationCompat.Builder(this, CH_ALERT)
            .setContentTitle("⚠️ Hotspot WiFi đã bị tắt!")
            .setContentText(
                when {
                    quietNow -> "🌙 Im lặng ban đêm — không kêu chuông"
                    hasMp3   -> "🎵 Đang phát nhạc cảnh báo..."
                    else     -> "Nhấn để vào cài đặt bật lại Hotspot."
                }
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Hotspot WiFi đang TẮT.\nNhấn \"Bật Hotspot\" để vào cài đặt và bật lại ngay."
            ))
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
