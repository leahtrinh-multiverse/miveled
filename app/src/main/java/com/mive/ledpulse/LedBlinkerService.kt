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
 * LedBlinkerService - The Persistent LED pulse engine for Mive Style Folder 1.
 *
 * Why this overcomes the stock Mive 1-blink limit:
 * Stock Mive firmware sends a single lights.set_light() call on notification arrival.
 * This service runs a controlled periodic wake-pulse loop:
 * Every ${config.pulseIntervalSec}s, it re-issues the hardware LED notification light pulse
 * with FLAG_SHOW_LIGHTS and FLAG_ONLY_ALERT_ONCE (silent, no annoying re-buzzing).
 * It stops immediately as soon as the user opens the clamshell and dismisses the notification.
 */
class LedBlinkerService : Service() {

    companion object {
        const val ACTION_START_MONITORING = "com.mive.ledpulse.ACTION_START_MONITORING"
        const val ACTION_START_BLINKING = "com.mive.ledpulse.ACTION_START_BLINKING"
        const val ACTION_STOP_BLINKING = "com.mive.ledpulse.ACTION_STOP_BLINKING"
        const val ACTION_TEST_BLINK = "com.mive.ledpulse.ACTION_TEST_BLINK"

        private const val CHANNEL_FOREGROUND_ID = "mive_led_service_channel"
        private const val CHANNEL_PULSE_ID = "mive_led_pulse_channel"
        private const val NOTIFICATION_FOREGROUND_ID = 1001
        private const val NOTIFICATION_PULSE_ID = 1002

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
    private var cameraId: String? = null

    // Pulse configuration from UI generator
    private val PULSE_INTERVAL_MS = 2000L // Configured interval
    private val PULSE_DURATION_MS = 300 // Pulse on duration
    private val LED_COLOR_INT = 0xFF00FF00.toInt()

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!isBlinking) return

            if (isInQuietHours()) {
                Log.d(TAG, "Quiet hours active, skipping LED pulse.")
                handler.postDelayed(this, PULSE_INTERVAL_MS)
                return
            }

            // Execute the physical LED blink pulse
            performLedPulse()

            // Schedule next repetition
            handler.postDelayed(this, PULSE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiveLedPulse::WakeLock")

        

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
        Log.d(TAG, "Started persistent LED blinking for $count notifications.")

        try {
            wakeLock?.acquire(60 * 60 * 1000L) // 1-hour safety limit
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock acquire error: ${e.message}")
        }

        handler.removeCallbacks(pulseRunnable)
        handler.post(pulseRunnable)
    }

    private fun stopBlinking() {
        if (!isBlinking) return
        isBlinking = false
        Log.d(TAG, "Stopped LED blinking (Notification cleared/screen opened).")

        handler.removeCallbacks(pulseRunnable)
        notificationManager.cancel(NOTIFICATION_PULSE_ID)

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {
                Log.e(TAG, "WakeLock release error: ${e.message}")
            }
        }

        
    }

    /**
     * Pulses the physical Mive Style Folder LED using Android's notification light subsystem.
     * Re-posting with FLAG_ONLY_ALERT_ONCE keeps the light pulsing without sound or vibration spam.
     */
    private fun performLedPulse() {
        try {
            val pulseNotification = NotificationCompat.Builder(this, CHANNEL_PULSE_ID)
                .setSmallIcon(R.drawable.ic_led_pulse)
                .setContentTitle("")
                .setContentText("")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setLights(LED_COLOR_INT, PULSE_DURATION_MS, (PULSE_INTERVAL_MS - PULSE_DURATION_MS).toInt())
                .setOnlyAlertOnce(true) // Silent! Only light flashes
                .setAutoCancel(true)
                .build()

            // Enable lights flag directly
            pulseNotification.flags = pulseNotification.flags or Notification.FLAG_SHOW_LIGHTS

            notificationManager.notify(NOTIFICATION_PULSE_ID, pulseNotification)

            // Direct sysfs control attempt (works on many Unisoc/MTK flip phone kernels with root/sys access)
            pulseSysfsLeds()

            

        } catch (e: Exception) {
            Log.e(TAG, "Error performing LED pulse: ${e.message}")
        }
    }

    /**
     * Sysfs hook for direct hardware LED control on Mive Style Folder (AT-M120).
     * If kernel exposes standard sysfs nodes, this toggles the physical LED directly.
     */
    private fun pulseSysfsLeds() {
        val ledPaths = arrayOf(
            "/sys/class/leds/red/brightness",
            "/sys/class/leds/green/brightness",
            "/sys/class/leds/blue/brightness",
            "/sys/class/leds/led_notification/brightness"
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
                    break
                } catch (e: Exception) {
                    // Normal when running without root/OEM direct sysfs permission
                }
            }
        }
    }

    private fun isInQuietHours(): Boolean {
        
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val currentMinutes = hour * 60 + minute

        val startMinutes = 23 * 60 + 0
        val endMinutes = 7 * 60 + 0

        return if (startMinutes < endMinutes) {
            currentMinutes in startMinutes..endMinutes
        } else {
            // Spans midnight, e.g. 23:00 to 07:00
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel for the persistent foreground service (low priority, silent)
            val fgChannel = NotificationChannel(
                CHANNEL_FOREGROUND_ID,
                "Mive LED Pulse Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the LED blinker running when the flip phone lid is closed."
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(fgChannel)

            // High-importance light-pulsing channel specifically for hardware LED trigger
            val pulseChannel = NotificationChannel(
                CHANNEL_PULSE_ID,
                "Mive Exterior LED Pulse Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Sends repeating light pulses to Mive Style Folder's exterior chassis LED."
                enableLights(true)
                lightColor = LED_COLOR_INT
                enableVibration(false)
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