package com.wifimonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private var ringtone: android.media.Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null

    companion object {
        const val CH_ALARM = "ch_alarm_clock"
        const val NOTIF_ID = 2001
        const val EXTRA_RING_INDEX = "ring_index"
        const val ACTION_STOP = "com.wifimonitor.action.ALARM_STOP"
        const val PREF_ALARM_MP3_URI = "alarm_mp3_uri"
        const val TOTAL_RINGS = 3
        private const val AUTO_STOP_MS = 60_000L // tự dừng sau 60 giây nếu người dùng không phản hồi
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAlarm()
            return START_NOT_STICKY
        }

        val ringIndex = intent?.getIntExtra(EXTRA_RING_INDEX, 0) ?: 0
        startForeground(NOTIF_ID, buildNotification(ringIndex))

        // Dừng bất kỳ âm thanh cũ nào trước khi phát mới
        stopSound()
        playSound()

        // Auto stop sau 60 giây
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = Runnable { stopAlarm() }
        handler.postDelayed(autoStopRunnable!!, AUTO_STOP_MS)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        stopSound()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Phát âm thanh ────────────────────────────────────────────────

    private fun playSound() {
        val uriStr = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_ALARM_MP3_URI, null)

        if (uriStr != null) {
            playMp3File(Uri.parse(uriStr))
        } else {
            playDefaultAlarm()
        }
    }

    /**
     * FIX BUG CHÍNH: trước đây dùng apply{} block để gán listener vào MediaPlayer
     * trong khi mediaPlayer = ... chưa gán xong, gây race condition: listener
     * callback có thể chạy và gọi stopSound() trước khi biến mediaPlayer được gán
     * đầy đủ → player bị release giữa chừng → nhạc không phát được.
     *
     * Fix: tạo MediaPlayer riêng, gán hết listener, RỒI MỚI gán vào mediaPlayer,
     * cuối cùng mới gọi prepareAsync() để đảm bảo thứ tự đúng.
     */
    private fun playMp3File(uri: Uri) {
        try {
            // Tạo player trước, chưa gán vào mediaPlayer
            val player = MediaPlayer()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            player.setDataSource(applicationContext, uri)
            player.isLooping = false

            // Gán listener SAU KHI đã tạo xong player
            player.setOnPreparedListener { mp ->
                // Kiểm tra player này còn là player hiện tại không (tránh race)
                if (mp === mediaPlayer) mp.start()
            }
            player.setOnCompletionListener { mp ->
                if (mp === mediaPlayer) {
                    // Nhạc kết thúc bình thường → dừng dịch vụ nhưng KHÔNG hủy
                    // lịch kêu tiếp theo (vẫn còn lần 2, lần 3 sẽ tự đến)
                    stopAlarm(cancelFutureRings = false)
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                if (mp === mediaPlayer) {
                    // Lỗi đọc file → fallback chuông mặc định thay vì im lặng
                    stopSound()
                    playDefaultAlarm()
                }
                true
            }

            // GÁN VÀO mediaPlayer TRƯỚC KHI gọi prepareAsync()
            // để đảm bảo mọi callback đều thấy đúng đối tượng
            mediaPlayer = player
            player.prepareAsync()

        } catch (e: Exception) {
            // File lỗi/hỏng/URI không hợp lệ → fallback ngay
            stopSound()
            playDefaultAlarm()
        }
    }

    private fun playDefaultAlarm() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ringtone?.isLooping = false
            }
            ringtone?.play()
        } catch (e: Exception) {
            // Im lặng — không để crash service
        }
    }

    private fun stopSound() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                reset()
                release()
            }
        } catch (e: Exception) { }
        mediaPlayer = null

        try { ringtone?.stop() } catch (e: Exception) { }
        ringtone = null
    }

    // ── Điều khiển báo thức ───────────────────────────────────────────

    /**
     * @param cancelFutureRings true khi người dùng chủ động dừng (hủy luôn lần 2/3),
     *                          false khi nhạc kết thúc bình thường (vẫn giữ lịch lần 2/3).
     */
    private fun stopAlarm(cancelFutureRings: Boolean = true) {
        stopSound()
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = null

        if (cancelFutureRings) {
            AlarmClockReceiver.cancelRemainingRings(applicationContext)
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ID)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun buildNotification(ringIndex: Int): Notification {
        val stopIntent = Intent(this, AlarmRingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 9100, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openAppPi = PendingIntent.getActivity(
            this, 9101,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CH_ALARM)
            .setContentTitle("⏰ Báo thức! (lần ${ringIndex + 1}/$TOTAL_RINGS)")
            .setContentText("Nhấn \"Dừng báo thức\" để tắt.")
            .setSmallIcon(R.drawable.ic_wifi_notify)
            .setContentIntent(openAppPi)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Dừng báo thức",
                stopPi
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(openAppPi, true)  // Hiện popup khi màn hình khóa
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ALARM,
                    "Đồng hồ báo thức",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 250, 500)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }
}
