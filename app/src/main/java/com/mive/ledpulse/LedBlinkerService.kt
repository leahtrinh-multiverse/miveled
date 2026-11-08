package com.mive.ledpulse

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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * LedBlinkerService - Chuyên biệt cho đèn LED nắp gập của Mive Style Folder 1 (AT-M120).
 * Đã loại bỏ hoàn toàn đèn Flash camera.
 * Sử dụng cơ chế đảo ID thông báo (3001 <-> 3002) mỗi 2.5s để kích hoạt chip MediaTek chớp đèn nắp.
 */
class LedBlinkerService : Service() {

    companion object {
        const val ACTION_START_MONITORING = "com.mive.ledpulse.ACTION_START_MONITORING"
        const val ACTION_START_BLINKING = "com.mive.ledpulse.ACTION_START_BLINKING"
        const val ACTION_STOP_BLINKING = "com.mive.ledpulse.ACTION_STOP_BLINKING"
        const val ACTION_TEST_BLINK = "com.mive.ledpulse.ACTION_TEST_BLINK"

        private const val CHANNEL_FOREGROUND_ID = "mive_bg_service_v5"
        private const val CHANNEL_LID_LED_ID = "mive_lid_led_channel_v5"
    }

    private val TAG = "LedBlinkerService"
    private val handler = Handler(Looper.getMainLooper())
    private var isBlinking = false
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private var pulseToggle = false

    private val pulseRunnable = object : Runnable {
        override fun run() {
            if (!isBlinking) return

            // Kích hoạt đèn LED trên nắp máy
            triggerLidLedPulse()

            // Lặp lại mỗi 2.5 giây
            handler.postDelayed(this, 2500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MiveLedPulse::WakeLock")

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
        Log.d(TAG, "Bắt đầu chu kỳ nháy đèn LED nắp gập.")

        try {
            wakeLock?.acquire(2 * 60 * 60 * 1000L) // Giữ CPU tỉnh để điều khiển đèn
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }

        handler.removeCallbacks(pulseRunnable)
        handler.post(pulseRunnable)
    }

    private fun stopBlinking() {
        isBlinking = false
        Log.d(TAG, "Dừng nháy đèn LED nắp.")

        handler.removeCallbacks(pulseRunnable)
        notificationManager.cancel(3001)
        notificationManager.cancel(3002)

        if (wakeLock?.isHeld == true) {
            try {
                wakeLock?.release()
            } catch (e: Exception) {}
        }
    }

    /**
     * Kỹ thuật đảo ID thông báo (3001 <-> 3002) mỗi 2.5s.
     * Huỷ ID cũ và bắn ID mới kèm cờ FLAG_SHOW_LIGHTS để chip MediaTek nhận diện là
     * thông báo mới và kích hoạt chiếc đèn LED trên vỏ nắp máy Mive chớp sáng!
     */
    private fun triggerLidLedPulse() {
        try {
            val pulseId = if (pulseToggle) 3001 else 3002
            val oldId = if (pulseToggle) 3002 else 3001
            pulseToggle = !pulseToggle

            // Huỷ thông báo cũ để reset trạng thái đèn
            notificationManager.cancel(oldId)

            val pulseNotif = NotificationCompat.Builder(this, CHANNEL_LID_LED_ID)
                .setSmallIcon(R.drawable.ic_led_pulse)
                .setContentTitle("Thông báo mới")
                .setContentText("Nháy đèn LED nắp")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setLights(0xFF00FF00.toInt(), 500, 500)
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

            // Kênh chuyên biệt điều khiển đèn LED nắp máy
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
