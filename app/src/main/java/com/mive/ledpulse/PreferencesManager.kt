package com.mive.ledpulse

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mive_led_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("KEY_ENABLED", true)
        set(value) = prefs.edit().putBoolean("KEY_ENABLED", value).apply()

    var pulseIntervalSec: Int
        get() = prefs.getInt("KEY_INTERVAL", 2)
        set(value) = prefs.edit().putInt("KEY_INTERVAL", value).apply()

    /**
     * Theo dõi TOÀN BỘ thông báo trên máy:
     * Bất kỳ app nào (Zalo, Messenger, SMS, Cuộc gọi, Shopee, Telegram, Gmail, Banking...)
     * khi có thông báo đến đều được nháy đèn LED liên tục!
     */
    fun isAppWhitelisted(packageName: String): Boolean {
        return true
    }
}
