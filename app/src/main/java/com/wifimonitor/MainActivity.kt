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
import com.google.android.material.slider.Slider
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
        binding.btnToggleService.setOnClickListener {
            if (HotspotMonitorService.isRunning) {
                stopMonitorService()
            } else {
                checkAndRequestPermissions()
            }
        }

        binding.btnOpenHotspotSettings.setOnClickListener {
            openHotspotSettings()
        }

        // Fix: dùng Slider.OnChangeListener đúng cách
        binding.sliderInterval.addOnChangeListener(Slider.OnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val minutes = value.toInt()
                prefs.edit().putInt(PREF_INTERVAL_MINUTES, minutes).apply()
                binding.tvIntervalValue.text = "$minutes phút"
                if (HotspotMonitorService.isRunning) {
                    restartService()
                }
            }
        })

        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_AUTO_START, checked).apply()
        }

        binding.btnBatteryOpt.setOnClickListener {
            openBatteryOptimizationSettings()
        }
    }

    private fun updateUI() {
        val isRunning = HotspotMonitorService.isRunning
        binding.btnToggleService.text = if (isRunning) "Dừng giám sát" else "Bắt đầu giám sát"
        binding.btnToggleService.setBackgroundColor(
            ContextCompat.getColor(this, if (isRunning) R.color.colorStop else R.color.colorStart)
        )
        binding.tvServiceStatus.text = if (isRunning) "🟢 Đang chạy nền" else "🔴 Đã dừng"

        val savedInterval = prefs.getInt(PREF_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES)
        binding.sliderInterval.value = savedInterval.toFloat()
        binding.tvIntervalValue.text = "$savedInterval phút"
        binding.switchAutoStart.isChecked = prefs.getBoolean(PREF_AUTO_START, true)

        val hotspotOn = HotspotUtils.isHotspotEnabled(this)
        binding.tvHotspotStatus.text = if (hotspotOn) "📶 Hotspot: BẬT" else "📵 Hotspot: TẮT"
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> startMonitorService()

                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ->
                    showNotificationRationaleDialog()

                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startMonitorService()
        }
    }

    private fun startMonitorService() {
        val intent = Intent(this, HotspotMonitorService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Toast.makeText(this, "Đã bắt đầu giám sát Hotspot", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopMonitorService() {
        stopService(Intent(this, HotspotMonitorService::class.java))
        Toast.makeText(this, "Đã dừng giám sát", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun restartService() {
        stopMonitorService()
        startMonitorService()
    }

    private fun openHotspotSettings() {
        val intents = listOf(
            Intent().apply {
                action = Intent.ACTION_MAIN
                setClassName("com.android.settings", "com.android.settings.TetherSettings")
            },
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (e: Exception) {
                // thử intent tiếp theo
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            } catch (e2: Exception) {
                // ignore
            }
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
            .setMessage("Không có quyền thông báo. Vào Cài đặt để cấp quyền.")
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
