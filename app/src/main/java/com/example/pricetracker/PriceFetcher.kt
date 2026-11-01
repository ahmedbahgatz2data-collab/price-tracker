package com.example.pricetracker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

object PriceFetcher {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchHtml(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            .header("Accept-Language", "ar,en;q=0.8")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            return resp.body?.string() ?: ""
        }
    }

    private val numberRegex = Regex("""\d[\d,]*\.?\d*""")

    fun parsePrice(text: String): Double? {
        val normalized = text.map {
            when (it) {
                in '٠'..'٩' -> '0' + (it - '٠')
                '٫' -> '.'
                '٬' -> ','
                else -> it
            }
        }.joinToString("")
        return numberRegex.find(normalized)?.value?.replace(",", "")?.toDoubleOrNull()
    }

    private fun findPrice(x: Any?): Double? {
        if (x is JSONObject) {
            for (k in listOf("price", "lowPrice")) {
                if (x.has(k)) {
                    val p = parsePrice(x.get(k).toString())
                    if (p != null) return p
                }
            }
            val keys = x.keys()
            while (keys.hasNext()) {
                val p = findPrice(x.opt(keys.next()))
                if (p != null) return p
            }
        } else if (x is JSONArray) {
            for (i in 0 until x.length()) {
                val p = findPrice(x.opt(i))
                if (p != null) return p
            }
        }
        return null
    }

    fun title(html: String): String? {
        val doc = Jsoup.parse(html)
        val og = doc.selectFirst("meta[property=\"og:title\"]")?.attr("content")?.trim()
        val t = if (!og.isNullOrBlank()) og else doc.title().trim()
        return t.takeIf { it.isNotBlank() }?.take(90)
    }

    fun extract(html: String, selector: String?): Double? {
        val doc = Jsoup.parse(html)

        if (!selector.isNullOrBlank()) {
            try {
                val el = doc.selectFirst(selector)
                if (el != null) {
                    val p = parsePrice(el.attr("content").ifBlank { el.text() })
                    if (p != null) return p
                }
            } catch (e: Exception) {
                // selector غير صالح، نكمل بالطرق التلقائية
            }
        }

        for (s in doc.select("script[type=application/ld+json]")) {
            try {
                val raw = s.data().trim()
                val json: Any = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
                val p = findPrice(json)
                if (p != null) return p
            } catch (e: Exception) {
                continue
            }
        }

        // موقع أوريمو: السعر بيتحمّل بجافاسكربت، لكنه موجود في meta تويتر في الصفحة
        val label = doc.selectFirst(
            "meta[name=\"twitter:label1\"], meta[property=\"twitter:label1\"]"
        )?.attr("content") ?: ""
        if (label.equals("Price", ignoreCase = true)) {
            val tp = doc.selectFirst(
                "meta[name=\"twitter:data1\"], meta[property=\"twitter:data1\"]"
            )?.attr("content")?.let { parsePrice(it) }
            if (tp != null) return tp
        }

        val el = doc.selectFirst(
            "meta[property=\"product:price:amount\"], meta[property=\"og:price:amount\"], [itemprop=price]"
        )
        if (el != null) {
            val p = parsePrice(el.attr("content").ifBlank { el.text() })
            if (p != null) return p
        }
        return null
    }
}
