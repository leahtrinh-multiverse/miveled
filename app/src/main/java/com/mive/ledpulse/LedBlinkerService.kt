package com.mive.ledpulse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * LedBlinkerService - Multi-Mode Persistent LED pulse engine for Mive Style Folder 1.
 * Fixes the 1-blink limit by alternating notification IDs, cancelling before re-notifying,
 * and pulsing both Notification Lights and exterior hardware LED.
 */
class LedBlinkerService : Service() {

    companion object {
        const val ACTION_START_MONITORING = "com.mive.ledpulse.ACTION_START_MONITORING"
        const val ACTION_START_BLINKING = "com.mive.ledpulse.ACTION_START_BLINKING"
        const val ACTION_STOP_BLINKING = "com.mive.ledpulse.ACTION_STOP_BLINKING"
        const val ACTION_TEST_BLINK = "com.mive.ledpulse.ACTION_TEST_BLINK"

        private const val CHANNEL_FOREGROUND_ID = "mive_led_service_channel_v3"
        private const val CHANNEL_PULSE_ID = "mive_led_pulse_channel_v3"
        private const val NOTIFICATION_FOREGROUND_ID = 1001

        fun triggerTestBlink(context: Context, durationMs: Long) {
            val intent = Intent(context, LedBlinkerService::class.java).apply {
                action = ACTION_TEST_BLINK
                putExtra("TEST_DURATION", durationMs)
            }
            context.startService(intent)
        }
    }

    private val TAG = "LedBlinkerService"
    private val handler = Handler(Looper.getMainLooper())
    private var isBlinking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private var cameraManager: CameraManager? = null

    // Alternating ID toggle to force Android Lights HAL to re-blink on every cycle
    private var pulseToggle = false

    private val PULSE_INTERVAL_MS = 2000L // 2 giây nháy 1 lần
    private val PULSE_DURATION_MS = 300   // Mỗi lần sáng 300ms
    private val LED_COLOR_INT = 0xFF00FF00.toInt() // Xanh lá

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!isBlinking) return

            // Thực hiện chớp đèn LED
            performLedPulse()

            // Lên lịch nháy tiếp theo
            handler.postDelayed(this, PULSE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiveLedPulse::WakeLock")

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        } catch (e: Exception) {
            Log.e(TAG, "CameraManager init: ${e.message}")
        }

        startForeground(NOTIFICATION_FOREGROUND_ID, createForegroundNotification("Mive LED Service Active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BLINKING -> {
                val count = intent.getIntExtra("COUNT", 1)
                startBlinking(count)
            }
            ACTION_STOP_BLINKING -> {
                stopBlinking()
            }
            ACTION_TEST_BLINK -> {
                val duration = intent.getLongExtra("TEST_DURATION", 10000L)
                startBlinking(1)
                handler.postDelayed({
                    stopBlinking()
                }, duration)
            }
            ACTION_START_MONITORING -> {
                Log.d(TAG, "Monitoring initiated.")
            }
        }
        return START_STICKY
    }

    private fun startBlinking(count: Int) {
        if (isBlinking) return
        isBlinking = true
        Log.d(TAG, "Starting persistent LED pulse loop.")

        try {
            wakeLock?.acquire(2 * 60 * 60 * 1000L) // Giữ CPU chạy nền không bị ngắt
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }

        handler.removeCallbacks(pulseRunnable)
        handler.post(pulseRunnable)
    }

    private fun stopBlinking() {
        if (!isBlinking) return
        isBlinking = false
        Log.d(TAG, "Halting LED pulse loop.")

        handler.removeCallbacks(pulseRunnable)
        notificationManager.cancel(1002)
        notificationManager.cancel(1003)

        turnOffAllTorches()

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {}
        }
    }

    /**
     * Kỹ thuật ép phần cứng Mive nháy liên tục:
     * 1) Luân phiên đổi ID thông báo (1002 <-> 1003) và huỷ ID cũ
     * 2) Tắt cờ setOnlyAlertOnce để ép HAL chớp đèn
     * 3) Kích hoạt đồng thời qua CameraManager cho đèn ngoài nắp gập
     */
    private fun performLedPulse() {
        try {
            val pulseId = if (pulseToggle) 1002 else 1003
            val oldId = if (pulseToggle) 1003 else 1002
            pulseToggle = !pulseToggle

            // Bước 1: Huỷ ID cũ để hệ điều hành reset trạng thái đèn
            notificationManager.cancel(oldId)

            // Bước 2: Bắn thông báo mới có cờ FLAG_SHOW_LIGHTS
            val pulseNotification = NotificationCompat.Builder(this, CHANNEL_PULSE_ID)
                .setSmallIcon(R.drawable.ic_led_pulse)
                .setContentTitle("Mive Notification Active")
                .setContentText("LED pulsing...")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setLights(LED_COLOR_INT, PULSE_DURATION_MS, 200)
                .setOnlyAlertOnce(false) // QUAN TRỌNG: false để đèn chớp lặp lại!
                .setAutoCancel(true)
                .build()

            pulseNotification.flags = pulseNotification.flags or Notification.FLAG_SHOW_LIGHTS

            notificationManager.notify(pulseId, pulseNotification)

            // Bước 3: Huỷ sau khi sáng xong để vòng lặp sau nhận là sự kiện mới
            handler.postDelayed({
                try {
                    notificationManager.cancel(pulseId)
                } catch (e: Exception) {}
            }, PULSE_DURATION_MS.toLong())

            // Bước 4: Chớp đồng thời bóng LED ở nắp ngoài qua CameraManager
            pulseCameraTorch()

            // Bước 5: Thử kích hoạt trực tiếp vào sysfs MediaTek
            pulseSysfsLeds()

        } catch (e: Exception) {
            Log.e(TAG, "performLedPulse error: ${e.message}")
        }
    }

    private fun pulseCameraTorch() {
        try {
            cameraManager?.let { cm ->
                for (id in cm.cameraIdList) {
                    try {
                        cm.setTorchMode(id, true)
                        handler.postDelayed({
                            try {
                                cm.setTorchMode(id, false)
                            } catch (e: Exception) {}
                        }, PULSE_DURATION_MS.toLong())
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    private fun turnOffAllTorches() {
        try {
            cameraManager?.let { cm ->
                for (id in cm.cameraIdList) {
                    try {
                        cm.setTorchMode(id, false)
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    private fun pulseSysfsLeds() {
        val ledPaths = arrayOf(
            "/sys/class/leds/red/brightness",
            "/sys/class/leds/green/brightness",
            "/sys/class/leds/blue/brightness",
            "/sys/class/leds/led_notification/brightness",
            "/sys/class/leds/charging/brightness",
            "/sys/class/leds/flash/brightness"
        )
        for (path in ledPaths) {
            val file = File(path)
            if (file.exists() && file.canWrite()) {
                try {
                    FileOutputStream(file).use { it.write("255".toByteArray()) }
                    handler.postDelayed({
                        try {
                            FileOutputStream(file).use { it.write("0".toByteArray()) }
                        } catch (e: Exception) {}
                    }, PULSE_DURATION_MS.toLong())
                } catch (e: Exception) {}
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fgChannel = NotificationChannel(
                CHANNEL_FOREGROUND_ID,
                "Mive LED Pulse Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the LED blinker active when clamshell is closed."
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(fgChannel)

            // Kênh thông báo đèn LED mới với quyền ưu tiên cao nhất
            val pulseChannel = NotificationChannel(
                CHANNEL_PULSE_ID,
                "Mive Exterior LED Pulse Channel",
                NotificationManager.IMPORTANCE_MAX
            ).apply {
                description = "Forces repeating hardware pulses to Mive Style Folder exterior LED."
                enableLights(true)
                lightColor = LED_COLOR_INT
                enableVibration(false)
                vibrationPattern = longArrayOf(0)
                setSound(null, null)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(pulseChannel)
        }
    }

    private fun createForegroundNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_FOREGROUND_ID)
            .setContentTitle("Mive LED Pulse Active")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_led_pulse)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopBlinking()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
