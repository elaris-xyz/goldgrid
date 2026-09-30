package com.vamahan.dailydraw

import android.content.Context
import android.util.Base64

/**
 * What the app keeps between launches so it opens ready: the connected wallet
 * (until the player disconnects), and the draw's config with the chain-clock
 * offset, so the round, the timer and the number grid render before the first
 * RPC answer. The config changes rarely and the next poll corrects it anyway.
 */
class Session(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var wallet: String?
        get() = prefs.getString("wallet", null)
        set(value) = prefs.edit().apply { if (value == null) remove("wallet") else putString("wallet", value) }.apply()

    var configData: ByteArray?
        get() = prefs.getString("config", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(value) = prefs.edit().putString("config", value?.let { Base64.encodeToString(it, Base64.NO_WRAP) }).apply()

    var tokenProgram: String?
        get() = prefs.getString("tokenProgram", null)
        set(value) = prefs.edit().putString("tokenProgram", value).apply()

    var notifyWins: Boolean
        get() = prefs.getBoolean("notifyWins", true)
        set(value) = prefs.edit().putBoolean("notifyWins", value).apply()

    var notifyResults: Boolean
        get() = prefs.getBoolean("notifyResults", true)
        set(value) = prefs.edit().putBoolean("notifyResults", value).apply()

    var notifySound: Boolean
        get() = prefs.getBoolean("notifySound", true)
        set(value) = prefs.edit().putBoolean("notifySound", value).apply()

    var roundReminders: RoundReminders
        get() = runCatching { RoundReminders.valueOf(prefs.getString("roundReminders", null)!!) }.getOrDefault(RoundReminders.Off)
        set(value) = prefs.edit().putString("roundReminders", value.name).apply()

    var lastRoundReminder: Long
        get() = prefs.getLong("lastRoundReminder", 0)
        set(value) = prefs.edit().putLong("lastRoundReminder", value).apply()

    var clockOffset: Long
        get() = prefs.getLong("clockOffset", 0)
        set(value) = prefs.edit().putLong("clockOffset", value).apply()
}
