package com.wifimonitor

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.wifimonitor.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }

    private var useSchedule: Boolean
        get() = prefs.getBoolean("use_schedule", false)
        set(v) = prefs.edit().putBoolean("use_schedule", v).apply()

    private var batteryThreshold: Int
        get() = prefs.getInt("battery_threshold", MonitorService.BATTERY_THRESHOLD)
        set(v) = prefs.edit().putInt("battery_threshold", v).apply()

    // Đồng hồ báo thức
    private var alarmHour: Int
        get() = prefs.getInt("alarm_hour", 6)
        set(v) = prefs.edit().putInt("alarm_hour", v).apply()

    private var alarmMinute: Int
        get() = prefs.getInt("alarm_minute", 0)
        set(v) = prefs.edit().putInt("alarm_minute", v).apply()

    private var alarmEnabled: Boolean
        get() = prefs.getBoolean("alarm_enabled", false)
        set(v) = prefs.edit().putBoolean("alarm_enabled", v).apply()

    private var isUpdatingUI = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startMonitor() else showPermDenied() }

    private val mp3Launcher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        prefs.edit().putString(MonitorService.PREF_MP3_URI, uri.toString()).apply()
        updateMp3UI()
        Toast.makeText(this, "Đã chọn file nhạc Hotspot", Toast.LENGTH_SHORT).show()
    }

    // Launcher chọn file MP3 riêng cho cảnh báo pin
    private val batteryMp3Launcher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        prefs.edit().putString(MonitorService.PREF_BATTERY_MP3_URI, uri.toString()).apply()
        updateBatteryMp3UI()
        Toast.makeText(this, "Đã chọn nhạc cảnh báo pin", Toast.LENGTH_SHORT).show()
    }

    // Launcher chọn file MP3 riêng cho báo thức
    private val alarmMp3Launcher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) { }
        prefs.edit().putString(AlarmRingService.PREF_ALARM_MP3_URI, uri.toString()).apply()
        updateAlarmMp3UI()
        Toast.makeText(this, "Đã chọn nhạc báo thức", Toast.LENGTH_SHORT).show()
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

        // Chế độ kiểm tra hotspot
        binding.radioGroup.setOnCheckedChangeListener { _: RadioGroup, checkedId: Int ->
            if (isUpdatingUI) return@setOnCheckedChangeListener

            val newUseSchedule = (checkedId == R.id.radioSchedule)
            if (newUseSchedule == useSchedule) return@setOnCheckedChangeListener

            useSchedule = newUseSchedule
            updateModeUI()

            if (MonitorService.running) {
                startMonitor()
            }
        }

        binding.btnMinus.setOnClickListener {
            val cur = prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL)
            if (cur > 1) {
                prefs.edit().putInt("interval", cur - 1).apply()
                binding.tvInterval.text = "${cur - 1} phút"
                if (MonitorService.running && !useSchedule) startMonitor()
            }
        }
        binding.btnPlus.setOnClickListener {
            val cur = prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL)
            if (cur < 60) {
                prefs.edit().putInt("interval", cur + 1).apply()
                binding.tvInterval.text = "${cur + 1} phút"
                if (MonitorService.running && !useSchedule) startMonitor()
            }
        }

        binding.switchAutoStart.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_start", checked).apply()
        }

        // MP3 Hotspot
        binding.btnPickMp3.setOnClickListener {
            mp3Launcher.launch(arrayOf("audio/*", "application/ogg", "audio/mpeg"))
        }
        binding.btnClearMp3.setOnClickListener {
            prefs.edit().remove(MonitorService.PREF_MP3_URI).apply()
            updateMp3UI()
            Toast.makeText(this, "Đã xóa — dùng chuông mặc định", Toast.LENGTH_SHORT).show()
        }

        // Cảnh báo pin
        binding.switchBatteryAlert.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("battery_alert_enabled", checked).apply()
            updateBatteryUI()
            if (!checked) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(MonitorService.BATTERY_ALERT_ID)
            }
        }
        binding.btnBatteryMinus.setOnClickListener {
            if (batteryThreshold > 5) {
                batteryThreshold -= 5
                updateBatteryUI()
            }
        }
        binding.btnBatteryPlus.setOnClickListener {
            if (batteryThreshold < 90) {
                batteryThreshold += 5
                updateBatteryUI()
            }
        }

        // MP3 riêng cho cảnh báo pin
        binding.btnPickBatteryMp3.setOnClickListener {
            batteryMp3Launcher.launch(arrayOf("audio/*", "application/ogg", "audio/mpeg"))
        }
        binding.btnClearBatteryMp3.setOnClickListener {
            prefs.edit().remove(MonitorService.PREF_BATTERY_MP3_URI).apply()
            updateBatteryMp3UI()
            Toast.makeText(this, "Đã xóa — dùng nhạc Hotspot hoặc chuông mặc định", Toast.LENGTH_SHORT).show()
        }

        // Đồng hồ báo thức
        binding.switchAlarmEnabled.setOnCheckedChangeListener { _, checked ->
            alarmEnabled = checked
            if (checked) {
                checkExactAlarmPermission()
                AlarmClockReceiver.scheduleAlarm(this, alarmHour, alarmMinute)
                Toast.makeText(
                    this,
                    "Đã đặt báo thức lúc ${formatTime(alarmHour, alarmMinute)}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                AlarmClockReceiver.cancelAlarm(this)
                Toast.makeText(this, "Đã tắt báo thức", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPickAlarmTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, hour, minute ->
                    alarmHour = hour
                    alarmMinute = minute
                    binding.tvAlarmTime.text = formatTime(hour, minute)
                    if (alarmEnabled) {
                        checkExactAlarmPermission()
                        AlarmClockReceiver.scheduleAlarm(this, hour, minute)
                        Toast.makeText(this, "Đã cập nhật giờ báo thức", Toast.LENGTH_SHORT).show()
                    }
                },
                alarmHour, alarmMinute, true
            ).show()
        }

        binding.btnPickAlarmMp3.setOnClickListener {
            alarmMp3Launcher.launch(arrayOf("audio/*", "application/ogg", "audio/mpeg"))
        }
        binding.btnClearAlarmMp3.setOnClickListener {
            prefs.edit().remove(AlarmRingService.PREF_ALARM_MP3_URI).apply()
            updateAlarmMp3UI()
            Toast.makeText(this, "Đã xóa — dùng chuông báo thức mặc định", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!am.canScheduleExactAlarms()) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    })
                    Toast.makeText(this, "Vui lòng cho phép quyền Đặt báo thức chính xác", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { }
            }
        }
    }

    private fun updateUI() {
        val running = MonitorService.running
        binding.btnToggle.text = if (running) "Dừng giám sát" else "Bắt đầu giám sát"
        binding.tvStatus.text = if (running) "🟢 Đang chạy nền" else "🔴 Đã dừng"

        val hotspot = HotspotUtils.isEnabled(this)
        binding.tvHotspot.text = if (hotspot) "📶 Hotspot: BẬT" else "📵 Hotspot: TẮT"

        binding.switchAutoStart.isChecked = prefs.getBoolean("auto_start", true)

        isUpdatingUI = true
        binding.radioGroup.check(if (useSchedule) R.id.radioSchedule else R.id.radioInterval)
        isUpdatingUI = false

        val interval = prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL)
        binding.tvInterval.text = "$interval phút"
        binding.tvScheduleTimes.text = ScheduleReceiver.scheduleLabels().joinToString("  •  ")

        updateModeUI()
        updateMp3UI()
        updateBatteryUI()
        updateBatteryMp3UI()
        updateAlarmUI()
    }

    private fun updateModeUI() {
        binding.layoutInterval.visibility =
            if (useSchedule) android.view.View.GONE else android.view.View.VISIBLE
        binding.layoutScheduleInfo.visibility =
            if (useSchedule) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun updateAlarmUI() {
        binding.switchAlarmEnabled.isChecked = alarmEnabled
        binding.tvAlarmTime.text = formatTime(alarmHour, alarmMinute)
    }

    private fun updateAlarmMp3UI() {
        val uriStr = prefs.getString(AlarmRingService.PREF_ALARM_MP3_URI, null)
        if (uriStr != null) {
            val name = getFileName(Uri.parse(uriStr)) ?: "File đã chọn"
            binding.tvAlarmMp3Name.text = "🎵 $name"
            binding.btnClearAlarmMp3.visibility = android.view.View.VISIBLE
        } else {
            binding.tvAlarmMp3Name.text = "Chưa chọn — dùng chuông báo thức mặc định"
            binding.btnClearAlarmMp3.visibility = android.view.View.GONE
        }
    }

    private fun formatTime(hour: Int, minute: Int): String =
        String.format("%02d:%02d", hour, minute)

    private fun updateMp3UI() {
        val uriStr = prefs.getString(MonitorService.PREF_MP3_URI, null)
        if (uriStr != null) {
            val name = getFileName(Uri.parse(uriStr)) ?: "File đã chọn"
            binding.tvMp3Name.text = "🎵 $name"
            binding.btnClearMp3.visibility = android.view.View.VISIBLE
        } else {
            binding.tvMp3Name.text = "Chưa chọn — dùng chuông mặc định"
            binding.btnClearMp3.visibility = android.view.View.GONE
        }
    }

    private fun updateBatteryUI() {
        val enabled = prefs.getBoolean("battery_alert_enabled", true)
        binding.switchBatteryAlert.isChecked = enabled
        binding.tvBatteryThreshold.text = "$batteryThreshold%"
        val alpha = if (enabled) 1.0f else 0.4f
        binding.btnBatteryMinus.alpha = alpha
        binding.btnBatteryPlus.alpha = alpha
        binding.tvBatteryThreshold.alpha = alpha
        binding.btnBatteryMinus.isEnabled = enabled && batteryThreshold > 5
        binding.btnBatteryPlus.isEnabled = enabled && batteryThreshold < 90
    }

    private fun updateBatteryMp3UI() {
        val uriStr = prefs.getString(MonitorService.PREF_BATTERY_MP3_URI, null)
        if (uriStr != null) {
            val name = getFileName(Uri.parse(uriStr)) ?: "File đã chọn"
            binding.tvBatteryMp3Name.text = "🎵 $name"
            binding.btnClearBatteryMp3.visibility = android.view.View.VISIBLE
        } else {
            binding.tvBatteryMp3Name.text = "Chưa chọn — dùng nhạc Hotspot hoặc chuông mặc định"
            binding.btnClearBatteryMp3.visibility = android.view.View.GONE
        }
    }

    private fun getFileName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx < 0 || !cursor.moveToFirst()) null else cursor.getString(idx)
            }
        } catch (e: Exception) { null }
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
        if (useSchedule) {
            ScheduleReceiver.setupDailySchedule(this)
            val si = Intent(this, MonitorService::class.java).apply {
                putExtra(MonitorService.EXTRA_TRIGGER, MonitorService.TRIGGER_SCHEDULE_MODE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si)
            else startService(si)
        } else {
            ScheduleReceiver.cancelDailySchedule(this)
            val si = Intent(this, MonitorService::class.java).apply {
                putExtra(MonitorService.EXTRA_INTERVAL, prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si)
            else startService(si)
        }
        Toast.makeText(this, "Đã bắt đầu giám sát", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopMonitor() {
        stopService(Intent(this, MonitorService::class.java))
        ScheduleReceiver.cancelDailySchedule(this)
        Toast.makeText(this, "Đã dừng giám sát", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun openHotspotSettings() {
        val intents = listOf(
            Intent().apply { setClassName("com.android.settings", "com.android.settings.TetherSettings") },
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in intents) {
            try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); return }
            catch (e: Exception) { }
        }
    }

    private fun openBatterySettings() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !pm.isIgnoringBatteryOptimizations(packageName)) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                })
                return
            }
        } catch (e: Exception) { }

        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
            catch (e2: Exception) { }
        }
    }

    private fun showPermDenied() {
        AlertDialog.Builder(this)
            .setTitle("Bị từ chối quyền")
            .setMessage("Vào Cài đặt để cấp quyền Thông báo cho app.")
            .setPositiveButton("Mở Cài đặt") { _, _ ->
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                })
            }
            .setNegativeButton("Đóng") { d, _ -> d.dismiss() }
            .show()
    }
}
