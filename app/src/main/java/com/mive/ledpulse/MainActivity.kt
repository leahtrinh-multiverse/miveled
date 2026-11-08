package com.mive.ledpulse

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * MainActivity for Mive Style Folder 1 (AT-M120 / Android 12 Go)
 * Designed with large tactile touch & keypad D-pad navigation support.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvNotificationAccess: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var switchService: SwitchCompat
    private lateinit var btnGrantNotification: Button
    private lateinit var btnBatteryOptimization: Button
    private lateinit var btnTestBlink: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvServiceStatus)
        tvNotificationAccess = findViewById(R.id.tvNotificationAccess)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)
        switchService = findViewById(R.id.switchMaster)
        btnGrantNotification = findViewById(R.id.btnGrantNotification)
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization)
        btnTestBlink = findViewById(R.id.btnTestBlink)

        val prefs = PreferencesManager(this)
        switchService.isChecked = prefs.isServiceEnabled

        switchService.setOnCheckedChangeListener { _, isChecked ->
            prefs.isServiceEnabled = isChecked
            if (isChecked) {
                if (!isNotificationServiceEnabled()) {
                    Toast.makeText(this, "Please grant Notification Access first!", Toast.LENGTH_LONG).show()
                    openNotificationAccessSettings()
                } else {
                    startBlinkerService()
                }
            } else {
                stopBlinkerService()
            }
            updateStatus()
        }

        btnGrantNotification.setOnClickListener {
            openNotificationAccessSettings()
        }

        btnBatteryOptimization.setOnClickListener {
            requestBatteryOptimizationExemption()
        }

        btnTestBlink.setOnClickListener {
            if (!isNotificationServiceEnabled()) {
                Toast.makeText(this, "Enable Notification Access first!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Triggering 10s test blink! Close phone lid to observe LED.", Toast.LENGTH_LONG).show()
            LedBlinkerService.triggerTestBlink(this, 10000)
        }
    }

    private fun updateStatus() {
        val hasNotifAccess = isNotificationServiceEnabled()
        val isBatteryExempt = isIgnoringBatteryOptimizations()

        tvNotificationAccess.text = if (hasNotifAccess) "✓ Granted (OK)" else "✗ Missing Access"
        tvNotificationAccess.setTextColor(if (hasNotifAccess) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        btnGrantNotification.isEnabled = !hasNotifAccess

        tvBatteryStatus.text = if (isBatteryExempt) "✓ Unrestricted (OK)" else "⚠ Optimized (May stop asleep)"
        tvBatteryStatus.setTextColor(if (isBatteryExempt) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())

        val prefs = PreferencesManager(this)
        tvStatus.text = if (prefs.isServiceEnabled && hasNotifAccess) "RUNNING (Watching for notifications)" else "STOPPED"
        tvStatus.setTextColor(if (prefs.isServiceEnabled && hasNotifAccess) 0xFF4CAF50.toInt() else 0xFF757575.toInt())
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!TextUtils.isEmpty(flat)) {
            val names = flat.split(":").toTypedArray()
            for (name in names) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && TextUtils.equals(pkgName, cn.packageName)) {
                    return true
                }
            }
        }
        return false
    }

    private fun openNotificationAccessSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not open settings automatically", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimizationExemption() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun startBlinkerService() {
        val intent = Intent(this, LedBlinkerService::class.java).apply {
            action = LedBlinkerService.ACTION_START_MONITORING
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopBlinkerService() {
        val intent = Intent(this, LedBlinkerService::class.java).apply {
            action = LedBlinkerService.ACTION_STOP_BLINKING
        }
        startService(intent)
    }
}