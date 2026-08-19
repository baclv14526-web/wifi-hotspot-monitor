package com.wifimonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var lastHotspotOn: Boolean? = null

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

    // ── polling ──────────────────────────────────────────────────────

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

        if (lastHotspotOn == true && !isOn) {
            sendAlertNotification(nm)
        } else if (isOn) {
            nm.cancel(ALERT_ID)
        }
        lastHotspotOn = isOn
    }

    // ── notifications ────────────────────────────────────────────────

    private fun buildFgNotification(isOn: Boolean?): Notification {
        val text = when (isOn) {
            true  -> "Hotspot dang BAT"
            false -> "Hotspot dang TAT"
            null  -> "Dang kiem tra..."
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CH_FG)
            .setContentTitle("Giam sat Hotspot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)

        builder.setSilent(true)

        return builder.build()
    }

    private fun sendAlertNotification(nm: NotificationManager) {
        val pi = PendingIntent.getActivity(
            this, 10,
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CH_ALERT)
            .setContentTitle("Hotspot WiFi da bi tat!")
            .setContentText("Nhan de vao cai dat bat lai Hotspot.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_menu_preferences, "Bat Hotspot", pi)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVibrate(longArrayOf(0, 500, 200, 500))
            .build()
        nm.notify(ALERT_ID, n)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CH_FG, "Giam sat nen", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) }
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_ALERT,
                    "Canh bao Hotspot tat",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    enableVibration(true)
                    enableLights(true)
                }
            )
        }
    }
}
