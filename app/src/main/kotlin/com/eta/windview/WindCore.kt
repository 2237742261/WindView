package com.eta.windview

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale

internal const val API_FORECAST = "https://api.open-meteo.com/v1/forecast"
internal const val API_GEOCODE = "https://geocoding-api.open-meteo.com/v1/search"

/** 逐小时的点 */
data class HourPoint(
    val label: String,      // "14:00"
    val speed10: Float?,
    val gust10: Float?,
    val speed100: Float?,
    val dir10: Int?,
    val dir100: Int?,
)

/** 一天的风速概况（不含今天，从明天起） */
data class DayPoint(
    val label: String,          // 明天 / 周三
    val date: String,           // 2026-10-26
    val max100: Float?,
    val min100: Float?,
    val atHour: String,
    val dir: Int?,
    val gust10: Float?,
    val hourly: List<Float?>,   // 当天 24 点的 100 m 风速
    val hourLabels: List<String>,
)

data class Snapshot(
    val time: String,
    val temp: Float?,
    val speed10: Float?,
    val gust10: Float?,
    val dir10: Int?,
)

data class WindData(
    val snapshot: Snapshot,
    val hours: List<HourPoint>,
    val days: List<DayPoint>,
    val fetchedAt: Long,
)

data class Place(val lat: Double, val lon: Double, val label: String)

// ---------------- 网络 ----------------

internal fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 12000
    conn.setRequestProperty("User-Agent", "WindView/0.4 (Android)")
    return try {
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private fun JSONObject.f(key: String): Float? =
    if (has(key) && !isNull(key)) getDouble(key).toFloat() else null

private fun arrF(o: JSONObject, key: String, i: Int): Float? {
    val a = o.optJSONArray(key) ?: return null
    if (i >= a.length() || a.isNull(i)) return null
    return a.getDouble(i).toFloat()
}

private fun arrI(o: JSONObject, key: String, i: Int): Int? {
    val a = o.optJSONArray(key) ?: return null
    if (i >= a.length() || a.isNull(i)) return null
    return a.getInt(i)
}

/**
 * 10 m / 100 m 风速都取自数值天气预报模式的垂直层输出（模式原生层），App 不做插值。
 * 8 天工期用来支撑「今天之外再展示 5 天」。
 */
internal suspend fun fetchWind(lat: Double, lon: Double): WindData = withContext(Dispatchers.IO) {
    val url = API_FORECAST +
        "?latitude=" + String.format(Locale.US, "%.4f", lat) +
        "&longitude=" + String.format(Locale.US, "%.4f", lon) +
        "&current=temperature_2m,wind_speed_10m,wind_gusts_10m,wind_direction_10m" +
        "&hourly=wind_speed_10m,wind_gusts_10m,wind_direction_10m,wind_speed_100m,wind_direction_100m" +
        "&wind_speed_unit=ms&timezone=auto&forecast_days=10"

    val root = JSONObject(httpGet(url))
    val cur = root.getJSONObject("current")
    val snapshot = Snapshot(
        time = cur.optString("time"),
        temp = cur.f("temperature_2m"),
        speed10 = cur.f("wind_speed_10m"),
        gust10 = cur.f("wind_gusts_10m"),
        dir10 = if (cur.has("wind_direction_10m") && !cur.isNull("wind_direction_10m")) cur.getInt("wind_direction_10m") else null,
    )

    val hourly = root.getJSONObject("hourly")
    val times = hourly.getJSONArray("time")
    val nowKey = snapshot.time.take(13)
    var start = 0
    for (i in 0 until times.length()) {
        if (times.getString(i).take(13) == nowKey) {
            start = i
            break
        }
    }

    val hours = ArrayList<HourPoint>()
    var i = start
    while (i < times.length() && hours.size < 24) {
        val t = times.getString(i)
        hours.add(
            HourPoint(
                label = if (t.length >= 16) t.substring(11, 16) else t,
                speed10 = arrF(hourly, "wind_speed_10m", i),
                gust10 = arrF(hourly, "wind_gusts_10m", i),
                speed100 = arrF(hourly, "wind_speed_100m", i),
                dir10 = arrI(hourly, "wind_direction_10m", i),
                dir100 = arrI(hourly, "wind_direction_100m", i),
            ),
        )
        i++
    }

    // 按自然日分组，跳过今天，取接下来 5 天
    val today = snapshot.time.take(10)
    val dayMap = LinkedHashMap<String, MutableList<Int>>()
    for (k in start until times.length()) {
        val date = times.getString(k).take(10)
        dayMap.getOrPut(date) { ArrayList() }.add(k)
    }

    val days = ArrayList<DayPoint>()
    var first = true
    for ((date, idxs) in dayMap) {
        if (first) {
            first = false
            if (date == today) continue   // 今天不列
        }
        if (days.size >= 7) break

        var maxV = Float.NEGATIVE_INFINITY
        var minV = Float.POSITIVE_INFINITY
        var bestAt = ""
        var bestDir: Int? = null
        var bestGust = Float.NEGATIVE_INFINITY
        val perHour = ArrayList<Float?>()
        val perLabel = ArrayList<String>()

        for (k in idxs) {
            val v = arrF(hourly, "wind_speed_100m", k)
            if (v != null) {
                if (v > maxV) {
                    maxV = v
                    bestAt = times.getString(k).substring(11, 16)
                    bestDir = arrI(hourly, "wind_direction_100m", k)
                }
                if (v < minV) minV = v
            }
            val g = arrF(hourly, "wind_gusts_10m", k)
            if (g != null && g > bestGust) bestGust = g
            perHour.add(v)
            perLabel.add(times.getString(k).substring(11, 16))
        }

        days.add(
            DayPoint(
                label = if (days.isEmpty()) "明天" else weekLabel(date),
                date = date,
                max100 = if (maxV.isFinite()) maxV else null,
                min100 = if (minV.isFinite()) minV else null,
                atHour = bestAt,
                dir = bestDir,
                gust10 = if (bestGust.isFinite()) bestGust else null,
                hourly = perHour,
                hourLabels = perLabel,
            ),
        )
    }

    WindData(snapshot = snapshot, hours = hours, days = days, fetchedAt = System.currentTimeMillis())
}

internal suspend fun searchPlace(name: String): List<Place> = withContext(Dispatchers.IO) {
    val url = API_GEOCODE + "?name=" + URLEncoder.encode(name, "UTF-8") +
        "&count=8&language=zh&format=json"
    val root = JSONObject(httpGet(url))
    val arr = root.optJSONArray("results") ?: return@withContext emptyList()
    val out = ArrayList<Place>()
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val lat = o.optDouble("latitude", Double.NaN)
        val lon = o.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) continue
        val parts = listOfNotNull(
            o.optString("name").takeIf { it.isNotBlank() },
            o.optString("admin2").takeIf { it.isNotBlank() },
            o.optString("admin1").takeIf { it.isNotBlank() },
        ).distinct()
        out.add(Place(lat, lon, parts.joinToString(" · ")))
    }
    out
}

// ---------------- 本地缓存（秒开用） ----------------

private fun cacheFile(ctx: Context): File = File(ctx.filesDir, "wind_cache.json")

internal fun saveCache(ctx: Context, d: WindData) {
    runCatching { cacheFile(ctx).writeText(toJson(d)) }
}

internal fun loadCache(ctx: Context): WindData? {
    val f = cacheFile(ctx)
    if (!f.exists() || f.length() < 32) return null
    return runCatching { fromJson(f.readText()) }.getOrNull()
}

private fun toJson(d: WindData): String {
    val root = JSONObject()
    root.put("fetchedAt", d.fetchedAt)
    root.put(
        "snapshot",
        JSONObject().apply {
            put("time", d.snapshot.time)
            putF("temp", d.snapshot.temp)
            putF("speed10", d.snapshot.speed10)
            putF("gust10", d.snapshot.gust10)
            putI("dir10", d.snapshot.dir10)
        },
    )
    root.put(
        "hours",
        JSONArray().apply {
            d.hours.forEach { h ->
                put(
                    JSONObject().apply {
                        put("label", h.label)
                        putF("speed10", h.speed10)
                        putF("gust10", h.gust10)
                        putF("speed100", h.speed100)
                        putI("dir10", h.dir10)
                        putI("dir100", h.dir100)
                    },
                )
            }
        },
    )
    root.put(
        "days",
        JSONArray().apply {
            d.days.forEach { day ->
                put(
                    JSONObject().apply {
                        put("label", day.label)
                        put("date", day.date)
                        putF("max100", day.max100)
                        putF("min100", day.min100)
                        put("atHour", day.atHour)
                        putI("dir", day.dir)
                        putF("gust10", day.gust10)
                        put(
                            "hourly",
                            JSONArray().apply { day.hourly.forEach { v -> if (v == null) put(JSONObject.NULL) else put(v) } },
                        )
                        put("hourLabels", JSONArray().apply { day.hourLabels.forEach { put(it) } })
                    },
                )
            }
        },
    )
    return root.toString()
}

private fun JSONObject.putF(k: String, v: Float?) {
    if (v == null) put(k, JSONObject.NULL) else put(k, v.toDouble())
}

private fun JSONObject.putI(k: String, v: Int?) {
    if (v == null) put(k, JSONObject.NULL) else put(k, v)
}

private fun JSONObject.f2(k: String): Float? = if (has(k) && !isNull(k)) getDouble(k).toFloat() else null

private fun JSONObject.i2(k: String): Int? = if (has(k) && !isNull(k)) getInt(k) else null

private fun fromJson(text: String): WindData {
    val root = JSONObject(text)
    val s = root.getJSONObject("snapshot")
    val snapshot = Snapshot(
        time = s.optString("time"),
        temp = s.f2("temp"),
        speed10 = s.f2("speed10"),
        gust10 = s.f2("gust10"),
        dir10 = s.i2("dir10"),
    )
    val hours = ArrayList<HourPoint>()
    val ha = root.optJSONArray("hours") ?: JSONArray()
    for (i in 0 until ha.length()) {
        val o = ha.getJSONObject(i)
        hours.add(
            HourPoint(
                label = o.optString("label"),
                speed10 = o.f2("speed10"),
                gust10 = o.f2("gust10"),
                speed100 = o.f2("speed100"),
                dir10 = o.i2("dir10"),
                dir100 = o.i2("dir100"),
            ),
        )
    }
    val days = ArrayList<DayPoint>()
    val da = root.optJSONArray("days") ?: JSONArray()
    for (i in 0 until da.length()) {
        val o = da.getJSONObject(i)
        val hArr = o.optJSONArray("hourly") ?: JSONArray()
        val lArr = o.optJSONArray("hourLabels") ?: JSONArray()
        val perHour = ArrayList<Float?>()
        for (k in 0 until hArr.length()) perHour.add(if (hArr.isNull(k)) null else hArr.getDouble(k).toFloat())
        val perLabel = ArrayList<String>()
        for (k in 0 until lArr.length()) perLabel.add(lArr.getString(k))
        days.add(
            DayPoint(
                label = o.optString("label"),
                date = o.optString("date"),
                max100 = o.f2("max100"),
                min100 = o.f2("min100"),
                atHour = o.optString("atHour"),
                dir = o.i2("dir"),
                gust10 = o.f2("gust10"),
                hourly = perHour,
                hourLabels = perLabel,
            ),
        )
    }
    return WindData(snapshot = snapshot, hours = hours, days = days, fetchedAt = root.optLong("fetchedAt"))
}

// ---------------- 工具 ----------------

private val WEEK_CN = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

internal fun weekLabel(date: String): String = runCatching {
    WEEK_CN[LocalDate.parse(date).dayOfWeek.value - 1]
}.getOrDefault(date.takeLast(5))

internal fun fmtCoord(lat: Double, lon: Double): String =
    String.format(Locale.US, "%.3f, %.3f", lat, lon)

internal fun fmtTime(iso: String): String = if (iso.length >= 16) iso.substring(11, 16) else iso

internal fun fmt1(v: Float?): String =
    if (v == null) "--" else String.format(Locale.US, "%.1f", v)

internal fun powerDensity(v: Float?): String =
    if (v == null) "--" else String.format(Locale.US, "%.0f", 0.5 * 1.225 * v * v * v)

internal fun joinDistinct(vararg parts: String?): String {
    val sb = StringBuilder()
    for (p in parts) {
        val s = p?.trim().orEmpty()
        if (s.isEmpty() || sb.contains(s)) continue
        sb.append(s)
    }
    return sb.toString()
}

private val DIR_NAMES = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")

internal fun dirName(deg: Int?): String {
    if (deg == null) return "--"
    val d = ((deg % 360) + 360) % 360
    return DIR_NAMES[((d + 22) / 45) % 8]
}

/** 阶梯式坐标上限：5 / 8 / 12 / 16 …，让折线占满画面 */
internal fun niceMax(raw: Float): Float {
    val steps = floatArrayOf(5f, 8f, 12f, 16f, 20f, 25f, 30f, 35f, 40f, 50f)
    for (s in steps) if (raw <= s) return s
    return kotlin.math.ceil(raw / 5f) * 5f
}
