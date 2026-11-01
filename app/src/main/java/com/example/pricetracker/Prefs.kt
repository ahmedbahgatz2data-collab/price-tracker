package com.example.pricetracker

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun token(c: Context): String = p(c).getString("tg_token", "") ?: ""

    /** يقسّم النص (فاصلة / فاصلة منقوطة / مسافة / سطر جديد) إلى قائمة Chat IDs بدون تكرار. */
    fun parseIds(raw: String): List<String> =
        raw.split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    fun chatIds(c: Context): List<String> = parseIds(p(c).getString("tg_chat", "") ?: "")

    fun saveTelegram(c: Context, token: String, chats: String) {
        p(c).edit().putString("tg_token", token).putString("tg_chat", chats).apply()
    }
}
