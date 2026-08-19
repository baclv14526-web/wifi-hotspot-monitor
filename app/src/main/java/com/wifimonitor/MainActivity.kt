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
    private val prefs by lazy { getSharedPreferences("wifi_monitor_prefs", MODE_PRIVATE) }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMonitorService()
        } else {
            showPermissionDeniedDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        checkAndRequestPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun setupUI() {
        // Toggle service button
        binding.btnToggleService.setOnClickListener {
            val isRunning = HotspotMonitorService.isRunning
            if (isRunning) {
                stopMonitorService()
            } else {
                checkAndRequestPermissions()
            }
        }

        // Open hotspot settings button
        binding.btnOpenHotspotSettings.setOnClickListener {
            openHotspotSettings()
        }

        // Set reminder interval
        binding.sliderInterval.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val minutes = value.toInt()
                prefs.edit().putInt(PREF_INTERVAL_MINUTES, minutes).apply()
                binding.tvIntervalValue.text = "$minutes phút"
                // Restart service with new interval if running
                if (HotspotMonitorService.isRunning) {
                    restartService()
                }
            }
        }

        // Auto-start on boot toggle
        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_START, checked).apply()
        }

        // Open battery optimization settings
        binding.btnBatteryOpt.setOnClickListener {
            openBatteryOptimizationSettings()
        }
    }

    private fun updateUI() {
        val isRunning = HotspotMonitorService.isRunning
        binding.btnToggleService.text = if (isRunning) "Dừng giám sát" else "Bắt đầu giám sát"
        binding.btnToggleService.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (isRunning) R.color.colorStop else R.color.colorStart
            )
        )

        val statusText = if (isRunning) "🟢 Đang chạy nền" else "🔴 Đã dừng"
        binding.tvServiceStatus.text = statusText

        val savedInterval = prefs.getInt(PREF_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        binding.sliderInterval.value = savedInterval.toFloat()
        binding.tvIntervalValue.text = "$savedInterval phút"

        binding.switchAutoStart.isChecked = prefs.getBoolean(PREF_AUTO_START, true)

        // Hotspot status
        val hotspotOn = HotspotUtils.isHotspotEnabled(this)
        binding.tvHotspotStatus.text = if (hotspotOn) "📶 Hotspot: BẬT" else "📵 Hotspot: TẮT"
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startMonitorService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    showNotificationRationaleDialog()
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        val serviceIntent = Intent(this, HotspotMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Đã bắt đầu giám sát Hotspot", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopMonitorService() {
        val serviceIntent = Intent(this, HotspotMonitorService::class.java)
        stopService(serviceIntent)
        Toast.makeText(this, "Đã dừng giám sát", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun restartService() {
        stopMonitorService()
        startMonitorService()
    }

    private fun openHotspotSettings() {
        // Try Oppo/Realme specific first, fallback to standard
        val intents = listOf(
            // ColorOS (Oppo/Realme)
            Intent().apply {
                action = Intent.ACTION_MAIN
                setClassName(
                    "com.android.settings",
                    "com.android.settings.TetherSettings"
                )
            },
            // Standard Android
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            // Fallback
            Intent(Settings.ACTION_SETTINGS)
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) { }
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
        }
    }

    private fun showNotificationRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Cần quyền thông báo")
            .setMessage("Ứng dụng cần quyền gửi thông báo để nhắc bạn bật Hotspot khi bị tắt.")
            .setPositiveButton("Cho phép") { _, _ ->
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            .setNegativeButton("Từ chối") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showPermissionDeniedDialog() {
        AlertDialog.Builder(this)
            .setTitle("Quyền bị từ chối")
            .setMessage("Không có quyền thông báo, ứng dụng sẽ không hoạt động đúng. Vào Cài đặt để cấp quyền.")
            .setPositiveButton("Mở Cài đặt") { _, _ ->
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
            .setNegativeButton("Đóng") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    companion object {
        const val PREF_INTERVAL_MINUTES = "interval_minutes"
        const val PREF_AUTO_START = "auto_start"
        const val DEFAULT_INTERVAL_MINUTES = 5
    }
}
