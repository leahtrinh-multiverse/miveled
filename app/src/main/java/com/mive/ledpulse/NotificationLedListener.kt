package com.mive.ledpulse

import android.app.Notification
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * NotificationListenerService tailored for Mive Style Folder 1.
 * Intercepts incoming messages, missed calls, and alerts.
 * When active notifications exist, instructs LedBlinkerService to pulse continuously.
 */
class NotificationLedListener : NotificationListenerService() {

    private val TAG = "MiveNotificationListener"

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected successfully.")
        evaluateActiveNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        // Ignore ongoing notifications like music players, call in progress, download progress
        if (isIgnoredNotification(sbn)) {
            return
        }

        Log.d(TAG, "New notification posted by: ${sbn.packageName}")
        evaluateActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        Log.d(TAG, "Notification dismissed/removed: ${sbn?.packageName}")
        evaluateActiveNotifications()
    }

    private fun isIgnoredNotification(sbn: StatusBarNotification): Boolean {
        // Ignore this app's own foreground notification
        if (sbn.packageName == packageName) return true

        val notification = sbn.notification ?: return true

        // Ignore ongoing foreground services (media players, weather, persistent battery icons)
        val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val isForeground = (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0

        return isOngoing || isForeground
    }

    /**
     * Checks all currently active status bar notifications against user whitelist.
     */
    private fun evaluateActiveNotifications() {
        val prefs = PreferencesManager(this)
        if (!prefs.isServiceEnabled) return

        try {
            val activeNotifs = activeNotifications ?: emptyArray()
            val relevantNotifications = activeNotifs.filter { sbn ->
                !isIgnoredNotification(sbn) && prefs.isAppWhitelisted(sbn.packageName)
            }

            val hasUnread = relevantNotifications.isNotEmpty()

            Log.d(TAG, "Active unread notifications count: ${relevantNotifications.size}")

            val serviceIntent = Intent(this, LedBlinkerService::class.java).apply {
                if (hasUnread) {
                    action = LedBlinkerService.ACTION_START_BLINKING
                    putExtra("COUNT", relevantNotifications.size)
                    putExtra("LATEST_PACKAGE", relevantNotifications.firstOrNull()?.packageName ?: "")
                } else {
                    action = LedBlinkerService.ACTION_STOP_BLINKING
                }
            }

            startService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error evaluating notifications: ${e.message}")
        }
    }
}