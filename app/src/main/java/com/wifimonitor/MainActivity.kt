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

        binding.btnSettings.setOnClickListener {
            openHotspotSettings()
        }

        binding.btnBattery.setOnClickListener {
            openBatterySettings()
        }

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
    }

    private fun updateUI() {
        val running = MonitorService.running
        binding.btnToggle.text = if (running) "Dung giam sat" else "Bat dau giam sat"
        binding.tvStatus.text = if (running) "Dang chay nen" else "Da dung"

        val interval = prefs.getInt("interval", MonitorService.DEFAULT_INTERVAL)
        binding.tvInterval.text = "$interval phut"
        binding.switchAutoStart.isChecked = prefs.getBoolean("auto_start", true)

        val hotspot = HotspotUtils.isEnabled(this)
        binding.tvHotspot.text = if (hotspot) "Hotspot: BAT" else "Hotspot: TAT"
    }

    private fun requestPermAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED -> startMonitor()
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) ->
                    AlertDialog.Builder(this)
                        .setTitle("Can quyen thong bao")
                        .setMessage("App can quyen gui thong bao de nhac bat Hotspot.")
                        .setPositiveButton("Cap quyen") { _, _ ->
                            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Huy") { d, _ -> d.dismiss() }
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
        Toast.makeText(this, "Da bat dau giam sat", Toast.LENGTH_SHORT).show()
        updateUI()
    }

    private fun stopMonitor() {
        stopService(Intent(this, MonitorService::class.java))
        Toast.makeText(this, "Da dung", Toast.LENGTH_SHORT).show()
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
                // try next
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
            .setTitle("Bi tu choi quyen")
            .setMessage("Vao Cai dat de cap quyen Thong bao.")
            .setPositiveButton("Mo Cai dat") { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                )
            }
            .setNegativeButton("Dong") { d, _ -> d.dismiss() }
            .show()
    }
}
