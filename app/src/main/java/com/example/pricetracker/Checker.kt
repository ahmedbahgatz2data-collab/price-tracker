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

class PriceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Checker.checkAll(applicationContext)
        Result.success()
    }
}

object Checker {
    fun checkAll(c: Context) {
        for (p in Store.load(c)) check(c, p)
    }

    private fun fmt(d: Double): String =
        if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(d)

    fun check(c: Context, p: Product) {
        val now = System.currentTimeMillis()
        try {
            val html = PriceFetcher.fetchHtml(p.url)
            val price = PriceFetcher.extract(html, p.selector)
            val title = if (p.nameAuto) PriceFetcher.title(html) else null

            if (price == null) {
                Store.update(c, p.id) {
                    it.copy(
                        error = "ما قدرتش أستخرج السعر من الصفحة",
                        lastChecked = now,
                        name = title ?: it.name,
                        nameAuto = if (title != null) false else it.nameAuto
                    )
                }
                return
            }

            val label = title ?: p.name
            val old = p.lastPrice
            val nid = p.id.hashCode()
            if (old != null && price != old) {
                val arrow = if (price < old) "⬇️ نزل" else "⬆️ زاد"
                Notifier.send(c, nid, "$arrow سعر $label", "من ${fmt(old)} إلى ${fmt(price)}", p.url)
            }
            val t = p.target
            if (t != null && price <= t && (old == null || old > t)) {
                Notifier.send(c, nid + 1, "🎯 $label وصل لسعرك المستهدف", "السعر الآن ${fmt(price)}", p.url)
            }

            Store.update(c, p.id) { cur ->
                val h = if (cur.history.lastOrNull() != price) (cur.history + price).takeLast(40) else cur.history
                cur.copy(
                    lastPrice = price,
                    lastChecked = now,
                    error = null,
                    history = h,
                    name = title ?: cur.name,
                    nameAuto = if (title != null) false else cur.nameAuto
                )
            }
        } catch (e: Exception) {
            Store.update(c, p.id) { it.copy(error = e.message ?: "خطأ في الاتصال", lastChecked = now) }
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
