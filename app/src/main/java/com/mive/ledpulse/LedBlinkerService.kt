package com.mive.ledpulse

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * LedBlinkerService - Dedicated to Mive Style Folder 1 (AT-M120) Lid Smart LED.
 * Uses Android Notification Lights (FLAG_SHOW_LIGHTS) with monotonically increasing IDs
 * to trigger the exterior chassis LED repeatedly without using the camera flash.
 */
class LedBlinkerService : Service() {

    companion object {
        const val ACTION_START_MONITORING = "com.mive.ledpulse.ACTION_START_MONITORING"
        const val ACTION_START_BLINKING = "com.mive.ledpulse.ACTION_START_BLINKING"
        const val ACTION_STOP_BLINKING = "com.mive.ledpulse.ACTION_STOP_BLINKING"
        const val ACTION_TEST_BLINK = "com.mive.ledpulse.ACTION_TEST_BLINK"

        private const val CHANNEL_FOREGROUND_ID = "mive_bg_service_v6"
        private const val CHANNEL_LID_LED_ID = "mive_lid_led_channel_v6"

        fun triggerTestBlink(context: Context, durationMs: Long) {
            val intent = Intent(context, LedBlinkerService::class.java).apply {
                action = ACTION_TEST_BLINK
                putExtra("TEST_DURATION", durationMs)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val TAG = "LedBlinkerService"
    private val handler = Handler(Looper.getMainLooper())
    private var isBlinking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private var pulseCounter = 3000

    // Khi người dùng lật mở nắp điện thoại -> màn hình BẬT -> dừng nháy ngay lập tức!
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                Log.d(TAG, "Clamshell lid opened -> stopping LED pulses")
                stopBlinking()
            }
        }
    }

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!isBlinking) return

            // Kích hoạt nháy đèn nắp máy
            triggerLidLedPulse()

            // Lặp lại sau mỗi 2.0 giây
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiveLedPulse::WakeLock")
        wakeLock?.setReferenceCounted(false)

        try {
            val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
            registerReceiver(screenReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering screen receiver: ${e.message}")
        }

        startForeground(1001, createForegroundNotification("Mive LED Pulse Active"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BLINKING -> {
                startBlinking()
            }
            ACTION_STOP_BLINKING -> {
                stopBlinking()
            }
            ACTION_TEST_BLINK -> {
                val duration = intent.getLongExtra("TEST_DURATION", 30000L)
                startBlinking()
                handler.postDelayed({
                    stopBlinking()
                }, duration)
            }
            ACTION_START_MONITORING -> {
                Log.d(TAG, "Monitoring active.")
            }
        }
        return START_STICKY
    }

    private fun startBlinking() {
        if (isBlinking) return
        isBlinking = true
        Log.d(TAG, "Starting persistent lid LED loop.")

        try {
            wakeLock?.acquire(2 * 60 * 60 * 1000L)
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }

        handler.removeCallbacks(pulseRunnable)
        handler.post(pulseRunnable)
    }

    private fun stopBlinking() {
        isBlinking = false
        Log.d(TAG, "Halting lid LED loop.")

        handler.removeCallbacks(pulseRunnable)
        for (i in (pulseCounter - 10)..pulseCounter) {
            notificationManager.cancel(i)
        }

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {}
        }
    }

    private fun triggerLidLedPulse() {
        try {
            wakeLock?.acquire(3500L)

            val pulseId = ++pulseCounter
            val previousId = pulseId - 1

            notificationManager.cancel(previousId)

            val pulseNotif = NotificationCompat.Builder(this, CHANNEL_LID_LED_ID)
                .setSmallIcon(R.drawable.ic_led_pulse)
                .setContentTitle("Thông báo mới")
                .setContentText("Đang nháy đèn nắp máy (#$pulseId)")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(false)
                .setLights(0xFF00FF00.toInt(), 500, 500)
                .setWhen(System.currentTimeMillis())
                .setAutoCancel(true)
                .build()

            pulseNotif.flags = pulseNotif.flags or Notification.FLAG_SHOW_LIGHTS

            notificationManager.notify(pulseId, pulseNotif)

        } catch (e: Exception) {
            Log.e(TAG, "Error pulsing lid LED: ${e.message}")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val fgChannel = NotificationChannel(
                CHANNEL_FOREGROUND_ID,
                "Mive LED Pulse Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(fgChannel)

            val pulseChannel = NotificationChannel(
                CHANNEL_LID_LED_ID,
                "Mive Lid LED Channel",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableLights(true)
                lightColor = 0xFF00FF00.toInt()
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
            .setContentTitle("Mive LED Pulse")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_led_pulse)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        stopBlinking()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
