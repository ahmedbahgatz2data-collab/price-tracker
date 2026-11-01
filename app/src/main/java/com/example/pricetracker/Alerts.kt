package com.example.pricetracker

import android.content.Context

object Alerts {
    /** إشعار على الموبايل + رسالة تيليجرام لكل المحادثات المضافة (لو مفعّل). */
    fun send(c: Context, id: Int, title: String, text: String, url: String, image: String? = null) {
        Notifier.send(c, id, title, text, url)
        val token = Prefs.token(c)
        val chats = Prefs.chatIds(c)
        if (token.isNotBlank() && chats.isNotEmpty()) {
            Telegram.sendAll(token, chats, "$title\n$text\n$url", image)
        }
    }
}
