package com.wifimonitor

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wifimonitor.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startMonitor() else showPermDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setup()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun setup() {
        binding.btnToggle.setOnClickListener {
            if (MonitorService.running) stopMonitor() else requestPermAndStart()
        }
        binding.btnSettings.setOnClickListener { openHotspotSettings() }
        binding.btnBattery.setOnClickListener { openBatterySettings() }

        binding.btnMinus.setOnClickListener {
            val cur = prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL)
            if (cur > 1) {
                prefs.edit().putInt("interval", cur - 1).apply()
                updateUI()
            }
        }
        binding.btnPlus.setOnClickListener {
            val cur = prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL)
            if (cur < 60) {
                prefs.edit().putInt("interval", cur + 1).apply()
                updateUI()
            }
        }
        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_start", checked).apply()
        }
        binding.switchSchedule.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("schedule_enabled", checked).apply()
            if (checked) {
                ScheduleReceiver.setupDailySchedule(this)
                Toast.makeText(this, "Đã bật lịch kiểm tra tự động", Toast.LENGTH_SHORT).show()
            } else {
                ScheduleReceiver.cancelDailySchedule(this)
                Toast.makeText(this, "Đã tắt lịch kiểm tra tự động", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUI() {
        val running = MonitorService.running
        binding.btnToggle.text = if (running) "Dừng giám sát" else "Bắt đầu giám sát"
        binding.tvStatus.text = if (running) "🟢 Đang chạy nền" else "🔴 Đã dừng"

        val interval = prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL)
        binding.tvInterval.text = "$interval phút"
        binding.switchAutoStart.isChecked = prefs.getBoolean("auto_start", true)
        binding.switchSchedule.isChecked = prefs.getBoolean("schedule_enabled", false)

        val hotspot = HotspotUtils.isEnabled(this)
        binding.tvHotspot.text = if (hotspot) "📶 Hotspot: BẬT" else "📵 Hotspot: TẮT"

        // Hiển thị danh sách giờ lịch trình
        val labels = ScheduleReceiver.scheduleLabels()
        binding.tvScheduleTimes.text = labels.joinToString("  •  ")
    }

    private fun requestPermAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED -> startMonitor()
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ->
                    AlertDialog.Builder(this)
                        .setTitle("Cần quyền thông báo")
                        .setMessage("App cần quyền gửi thông báo để nhắc bật Hotspot khi bị tắt.")
                        .setPositiveButton("Cấp quyền") { _, _ ->
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Huỷ") { d, _ -> d.dismiss() }
                        .show()
                else -> permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startMonitor()
        }
    }

    private fun startMonitor() {
        val si = Intent(this, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_INTERVAL, prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(si)
        } else {
            startService(si)
        }
        // Bật lịch trình nếu đã được bật trước đó
        if (prefs.getBoolean("schedule_enabled", false)) {
            ScheduleReceiver.setupDailySchedule(this)
        }
        Toast.makeText(this, "Đã bắt đầu giám sát", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopMonitor() {
        stopService(Intent(this, MonitorService::class.java))
        Toast.makeText(this, "Đã dừng giám sát", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun openHotspotSettings() {
        val intents = listOf(
            Intent().apply {
                setClassName("com.android.settings", "com.android.settings.TetherSettings")
            },
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                return
            } catch (e: Exception) {
                // thử intent tiếp theo
            }
        }
    }

    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            } catch (e2: Exception) {
                // ignore
            }
        }
    }

    private fun showPermDenied() {
        AlertDialog.Builder(this)
            .setTitle("Bị từ chối quyền")
            .setMessage("Vào Cài đặt để cấp quyền Thông báo cho app.")
            .setPositiveButton("Mở Cài đặt") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                )
            }
            .setNegativeButton("Đóng") { d, _ -> d.dismiss() }
            .show()
    }
}
