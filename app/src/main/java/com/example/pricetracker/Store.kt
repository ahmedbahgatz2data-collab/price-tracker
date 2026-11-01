package com.example.pricetracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Product(
    val id: String,
    val name: String,
    val url: String,
    val selector: String?,
    val target: Double?,
    val lastPrice: Double?,
    val lastChecked: Long?,
    val error: String?,
    val history: List<Double> = emptyList(),
    val nameAuto: Boolean = false
)

object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("tracker", Context.MODE_PRIVATE)

    @Synchronized
    fun load(c: Context): List<Product> {
        val arr = JSONArray(prefs(c).getString("products", "[]") ?: "[]")
        val out = ArrayList<Product>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val hist = ArrayList<Double>()
            val ha = o.optJSONArray("history")
            if (ha != null) {
                for (j in 0 until ha.length()) hist.add(ha.getDouble(j))
            }
            out.add(
                Product(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    url = o.getString("url"),
                    selector = o.optString("selector", "").ifBlank { null },
                    target = if (o.isNull("target")) null else o.getDouble("target"),
                    lastPrice = if (o.isNull("lastPrice")) null else o.getDouble("lastPrice"),
                    lastChecked = if (o.isNull("lastChecked")) null else o.getLong("lastChecked"),
                    error = if (o.isNull("error")) null else o.getString("error"),
                    history = hist,
                    nameAuto = o.optBoolean("nameAuto", false)
                )
            )
        }
        return out
    }

    @Synchronized
    private fun save(c: Context, list: List<Product>) {
        val arr = JSONArray()
        for (p in list) {
            val o = JSONObject()
            o.put("id", p.id)
            o.put("name", p.name)
            o.put("url", p.url)
            o.put("selector", p.selector ?: "")
            o.put("target", p.target ?: JSONObject.NULL)
            o.put("lastPrice", p.lastPrice ?: JSONObject.NULL)
            o.put("lastChecked", p.lastChecked ?: JSONObject.NULL)
            o.put("error", p.error ?: JSONObject.NULL)
            val ha = JSONArray()
            for (h in p.history) ha.put(h)
            o.put("history", ha)
            o.put("nameAuto", p.nameAuto)
            arr.put(o)
        }
        prefs(c).edit().putString("products", arr.toString()).apply()
    }

    @Synchronized
    fun mutate(c: Context, f: (List<Product>) -> List<Product>) {
        save(c, f(load(c)))
    }

    fun add(c: Context, p: Product) = mutate(c) { it + p }

    fun remove(c: Context, id: String) = mutate(c) { list -> list.filter { it.id != id } }

    fun update(c: Context, id: String, f: (Product) -> Product) =
        mutate(c) { list -> list.map { if (it.id == id) f(it) else it } }
}
