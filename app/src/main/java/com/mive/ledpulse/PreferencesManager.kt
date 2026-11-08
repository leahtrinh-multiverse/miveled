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

    fun isAppWhitelisted(packageName: String): Boolean {
        val whitelistedApps = prefs.getStringSet("KEY_WHITELIST", setOf(
            "com.kakao.talk",
            "com.samsung.android.messaging",
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.whatsapp",
            "com.android.server.telecom",
            "com.google.android.dialer",
            "org.telegram.messenger",
            "jp.naver.line.android"
        )) ?: emptySet()

        return whitelistedApps.contains(packageName)
    }
}