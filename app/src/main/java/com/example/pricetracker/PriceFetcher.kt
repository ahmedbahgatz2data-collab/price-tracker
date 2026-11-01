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

    private class Resp(val code: Int, val body: String, val retryAfter: Long)

    private fun fetchOnce(url: String): Resp {
        val req = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            )
            .header("Accept-Language", "ar,en;q=0.8")
            .build()
        return client.newCall(req).execute().use { resp ->
            Resp(
                resp.code,
                if (resp.isSuccessful) (resp.body?.string() ?: "") else "",
                resp.header("Retry-After")?.toLongOrNull() ?: 0L
            )
        }
    }

    /** بيعيد المحاولة لحد مرتين لو الموقع رد بـ 429/502/503 (Throttle)، مع انتظار متزايد. */
    fun fetchHtml(url: String): String {
        var attempt = 0
        while (true) {
            val r = fetchOnce(url)
            if (r.code in 200..299) return r.body
            if ((r.code == 429 || r.code == 502 || r.code == 503) && attempt < 2) {
                val waitMs = maxOf(r.retryAfter * 1000L, 5000L * (attempt + 1)).coerceAtMost(30000L)
                attempt++
                Thread.sleep(waitMs)
            } else {
                throw RuntimeException("HTTP ${r.code}")
            }
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

    private fun resolveUrl(base: String, v: String): String =
        try {
            java.net.URI(base).resolve(v).toString()
        } catch (e: Exception) {
            v
        }

    fun image(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html)
        val selectors = listOf(
            "meta[property=\"og:image\"]",
            "meta[name=\"twitter:image\"]",
            "meta[property=\"twitter:image\"]",
            "link[rel=\"image_src\"]"
        )
        for (sel in selectors) {
            val el = doc.selectFirst(sel) ?: continue
            val v = el.attr("content").ifBlank { el.attr("href") }.trim()
            if (v.isNotBlank()) return resolveUrl(pageUrl, v)
        }
        return null
    }

    private fun mapAvailability(raw: String): String? {
        val s = raw.lowercase().replace(" ", "").replace("_", "").replace("-", "")
        return when {
            "outofstock" in s || "soldout" in s || "unavailable" in s -> "نفد"
            "preorder" in s || "presale" in s || "backorder" in s -> "طلب مسبق"
            "limitedavailability" in s -> "كمية محدودة"
            "discontinued" in s -> "متوقف"
            "instock" in s || "available" in s -> "متوفر"
            else -> null
        }
    }

    private fun findString(x: Any?, key: String): String? {
        if (x is JSONObject) {
            if (x.has(key)) {
                val v = x.opt(key)
                if (v is String) return v
                if (v is JSONObject) {
                    val id = v.optString("@id", "")
                    if (id.isNotBlank()) return id
                }
            }
            val keys = x.keys()
            while (keys.hasNext()) {
                val r = findString(x.opt(keys.next()), key)
                if (r != null) return r
            }
        } else if (x is JSONArray) {
            for (i in 0 until x.length()) {
                val r = findString(x.opt(i), key)
                if (r != null) return r
            }
        }
        return null
    }

    /** حالة التوفر (متوفر / نفد ...) لو الصفحة بتنشرها في بياناتها، وإلا null. */
    fun availability(html: String): String? {
        val doc = Jsoup.parse(html)
        for (s in doc.select("script[type=application/ld+json]")) {
            try {
                val raw = s.data().trim()
                val json: Any = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw)
                val a = findString(json, "availability")
                if (a != null) {
                    val m = mapAvailability(a)
                    if (m != null) return m
                }
            } catch (e: Exception) {
                continue
            }
        }
        val el = doc.selectFirst(
            "meta[property=\"product:availability\"], meta[property=\"og:availability\"], [itemprop=availability]"
        )
        if (el != null) {
            val raw = el.attr("content").ifBlank { el.attr("href").ifBlank { el.text() } }
            return mapAvailability(raw)
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
