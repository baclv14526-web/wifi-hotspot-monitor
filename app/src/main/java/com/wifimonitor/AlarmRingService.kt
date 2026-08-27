package com.wifimonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Service phát chuông báo thức. Mỗi lần kêu là 1 lần phát ngắn (không lặp vô hạn),
 * kèm notification có nút "Dừng báo thức" để hủy các lần kêu lại còn sót.
 * Tổng cộng báo thức sẽ kêu tối đa 3 lần trong vòng 15 phút (do AlarmClockReceiver
 * lên lịch), nếu người dùng không bấm Dừng sớm hơn.
 */
class AlarmRingService : Service() {

    private var mediaPlayer: MediaPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null

    companion object {
        const val CH_ALARM = "ch_alarm_clock"
        const val NOTIF_ID = 2001
        const val EXTRA_RING_INDEX = "ring_index" // 0, 1, 2 = lần 1/2/3
        const val ACTION_STOP = "com.wifimonitor.action.ALARM_STOP"
        const val PREF_ALARM_MP3_URI = "alarm_mp3_uri"
        const val TOTAL_RINGS = 3
        private const val AUTO_STOP_MS = 30_000L // tự dừng an toàn nếu file nhạc quá dài
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRingingAndSelf()
            return START_NOT_STICKY
        }

        val ringIndex = intent?.getIntExtra(EXTRA_RING_INDEX, 0) ?: 0
        startForeground(NOTIF_ID, buildNotification(ringIndex))
        playSound()

        autoStopRunnable?.let { handler.removeCallbacks(it) }
        autoStopRunnable = Runnable { stopRingingAndSelf() }
        handler.postDelayed(autoStopRunnable!!, AUTO_STOP_MS)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        autoStopRunnable?.let { handler.removeCallbacks(it) }
        releasePlayer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun playSound() {
        val uriStr = getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .getString(PREF_ALARM_MP3_URI, null)
        try {
            if (uriStr != null) {
                mediaPlayer = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(applicationContext, Uri.parse(uriStr))
                    isLooping = false
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { stopRingingAndSelf() }
                    setOnErrorListener { _, _, _ -> stopRingingAndSelf(); true }
                    prepareAsync()
                }
            } else {
                // Chưa chọn file riêng → dùng chuông BÁO THỨC mặc định của hệ thống
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                RingtoneManager.getRingtone(applicationContext, uri)?.play()
            }
        } catch (e: Exception) {
            stopRingingAndSelf()
        }
    }

    private fun releasePlayer() {
        try { mediaPlayer?.apply { if (isPlaying) stop(); release() } } catch (e: Exception) { }
        mediaPlayer = null
    }

    private fun stopRingingAndSelf() {
        releasePlayer()
        // Hủy các lần kêu lại còn lại (nếu người dùng bấm Dừng giữa chừng)
        AlarmClockReceiver.cancelRemainingRings(applicationContext)
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

    private fun buildNotification(ringIndex: Int): Notification {
        val stopIntent = Intent(this, AlarmRingService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 9100, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openAppPi = PendingIntent.getActivity(
            this, 9101,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CH_ALARM)
            .setContentTitle("⏰ Báo thức! (lần ${ringIndex + 1}/$TOTAL_RINGS)")
            .setContentText("Nhấn \"Dừng báo thức\" để tắt.")
            .setSmallIcon(R.drawable.ic_wifi_notify)
            .setContentIntent(openAppPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Dừng báo thức", stopPi)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVibrate(longArrayOf(0, 500, 250, 500))
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_ALARM, "Đồng hồ báo thức", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 500, 250, 500)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
            )
        }
    }
}
