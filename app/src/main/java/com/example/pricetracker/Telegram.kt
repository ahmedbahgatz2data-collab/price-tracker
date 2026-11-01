package com.example.pricetracker

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Telegram {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun call(token: String, method: String, form: FormBody?): JSONObject {
        val b = Request.Builder().url("https://api.telegram.org/bot$token/$method")
        val req = if (form != null) b.post(form).build() else b.get().build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            try {
                return JSONObject(body)
            } catch (e: Exception) {
                return JSONObject().put("ok", false).put("description", "HTTP ${resp.code}")
            }
        }
    }

    /** يرجع null لو الإرسال نجح، أو رسالة الخطأ. لازم يتنادى من thread خلفي. */
    fun send(token: String, chatId: String, text: String, imageUrl: String? = null): String? {
        try {
            if (!imageUrl.isNullOrBlank()) {
                val r = call(
                    token, "sendPhoto",
                    FormBody.Builder()
                        .add("chat_id", chatId)
                        .add("photo", imageUrl)
                        .add("caption", text.take(1000))
                        .build()
                )
                if (r.optBoolean("ok")) return null
                // لو فشلت الصورة نبعت النص لوحده
            }
            val r = call(
                token, "sendMessage",
                FormBody.Builder().add("chat_id", chatId).add("text", text).build()
            )
            return if (r.optBoolean("ok")) null else r.optString("description", "فشل الإرسال")
        } catch (e: Exception) {
            return e.message ?: "تعذر الاتصال بتيليجرام"
        }
    }

    /** يرجّع null لو الإرسال نجح لكل المحادثات، أو ملخص الأخطاء. */
    fun sendAll(token: String, chatIds: List<String>, text: String, imageUrl: String? = null): String? {
        val errors = ArrayList<String>()
        for (id in chatIds) {
            val e = send(token, id, text, imageUrl)
            if (e != null) errors.add("$id: $e")
        }
        return if (errors.isEmpty()) null else errors.joinToString(" | ")
    }

    /** يجيب كل الـ chat ids اللي بعتت رسائل للبوت (آخر 24 ساعة تقريبًا). لازم يتنادى من thread خلفي. */
    fun detectChatIds(token: String): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val r = call(token, "getUpdates", null)
            if (!r.optBoolean("ok")) return emptyList()
            val arr = r.optJSONArray("result") ?: return emptyList()
            for (i in arr.length() - 1 downTo 0) {
                val u = arr.getJSONObject(i)
                for (k in listOf("message", "edited_message", "channel_post", "my_chat_member")) {
                    val chat = u.optJSONObject(k)?.optJSONObject("chat")
                    if (chat != null) out.add(chat.get("id").toString())
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out.toList()
    }
}
