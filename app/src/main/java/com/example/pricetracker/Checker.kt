package com.example.pricetracker

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

const val CHECK_EVERY_MINUTES = 30L  // أندرويد لا يسمح بأقل من 15 دقيقة
const val DELAY_BETWEEN_PRODUCTS_MS = 2500L  // فاصل بين كل منتج والتاني لتفادي الـ Throttle

class PriceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Checker.checkAll(applicationContext)
        Result.success()
    }
}

object Checker {
    const val GONE = "الصفحة غير موجودة"

    /** متزامن: لو فحصين اشتغلوا مع بعض، التاني يستنى الأول (عشان ما يتبعتش إشعار مرتين). */
    @Synchronized
    fun checkAll(c: Context) {
        val list = Store.load(c)
        for ((i, p) in list.withIndex()) {
            if (i > 0) Thread.sleep(DELAY_BETWEEN_PRODUCTS_MS)
            check(c, p)
        }
    }

    private fun fmt(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(d)

    fun check(c: Context, p: Product) {
        val now = System.currentTimeMillis()
        val nid = p.id.hashCode()
        try {
            val html = PriceFetcher.fetchHtml(p.url)
            val price = PriceFetcher.extract(html, p.selector)
            val title = if (p.nameAuto) PriceFetcher.title(html) else null
            val image = PriceFetcher.image(html, p.url) ?: p.imageUrl
            val status = PriceFetcher.availability(html)
            val label = title ?: p.name

            // تغيّر الحالة (متوفر / نفد ...)
            val oldStatus = p.status
            if (status != null && oldStatus != null && status != oldStatus) {
                Alerts.send(c, nid + 2, "🔔 حالة $label اتغيرت", "من $oldStatus إلى $status", p.url, image)
            }
            val newStatus = status ?: (if (oldStatus == GONE) null else oldStatus)

            if (price == null) {
                Store.update(c, p.id) {
                    it.copy(
                        error = "ما قدرتش أستخرج السعر من الصفحة",
                        lastChecked = now,
                        name = title ?: it.name,
                        nameAuto = if (title != null) false else it.nameAuto,
                        imageUrl = image,
                        status = newStatus
                    )
                }
                return
            }

            val old = p.lastPrice
            if (old != null && price != old) {
                val arrow = if (price < old) "⬇️ نزل" else "⬆️ زاد"
                Alerts.send(c, nid, "$arrow سعر $label", "من ${fmt(old)} إلى ${fmt(price)}", p.url, image)
            }
            val t = p.target
            if (t != null && price <= t && (old == null || old > t)) {
                Alerts.send(c, nid + 1, "🎯 $label وصل لسعرك المستهدف", "السعر الآن ${fmt(price)}", p.url, image)
            }

            Store.update(c, p.id) { cur ->
                val h = if (cur.history.lastOrNull() != price) (cur.history + price).takeLast(40) else cur.history
                cur.copy(
                    lastPrice = price,
                    lastChecked = now,
                    error = null,
                    history = h,
                    name = title ?: cur.name,
                    nameAuto = if (title != null) false else cur.nameAuto,
                    imageUrl = image,
                    status = newStatus
                )
            }
        } catch (e: Exception) {
            val msg = e.message ?: "خطأ في الاتصال"
            if (msg == "HTTP 404" || msg == "HTTP 410") {
                if (p.lastPrice != null && p.status != GONE) {
                    Alerts.send(c, nid + 2, "🔔 ${p.name}: الصفحة اتشالت", "الموقع رجّع $msg", p.url, p.imageUrl)
                }
                Store.update(c, p.id) { it.copy(error = msg, lastChecked = now, status = GONE) }
            } else {
                Store.update(c, p.id) { it.copy(error = msg, lastChecked = now) }
            }
        }
    }
}

private val netConstraints: Constraints =
    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

fun schedulePeriodic(c: Context) {
    val req = PeriodicWorkRequestBuilder<PriceWorker>(CHECK_EVERY_MINUTES, TimeUnit.MINUTES)
        .setConstraints(netConstraints)
        .build()
    WorkManager.getInstance(c)
        .enqueueUniquePeriodicWork("price-check", ExistingPeriodicWorkPolicy.KEEP, req)
}

fun checkNow(c: Context) {
    val req = OneTimeWorkRequestBuilder<PriceWorker>().setConstraints(netConstraints).build()
    WorkManager.getInstance(c).enqueue(req)
}
