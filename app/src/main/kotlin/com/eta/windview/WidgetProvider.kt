package com.eta.windview

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import kotlinx.coroutines.runBlocking
import java.util.Locale
import kotlin.math.max

/**
 * 桌面小组件：2×2 与 4×2（4×2 带 12 小时折线 + 时间刻度 + 峰值）。
 * 底色与折线取自壁纸主色，和 App 内保持一致；背景自己画圆角，避免四角发黑。
 */

private fun openApp(context: Context): PendingIntent {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    return PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 按 widget 实际占用尺寸生成，避免拉伸变形 */
private fun sizeOf(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
    val o: Bundle = mgr.getAppWidgetOptions(id)
    val d = ctx.resources.displayMetrics.density
    val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
    val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
    val w = (wDp * d).toInt().coerceIn(240, 2000)
    val h = (hDp * d).toInt().coerceIn(120, 1200)
    return w to h
}

private fun roundBg(w: Int, h: Int, top: Int, bottom: Int, radius: Float): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
    }
    c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, paint)
    return bmp
}

/** 12 小时折线：带峰值标注和底部时间刻度 */
private fun sparkline(
    values: List<Float?>,
    labels: List<String>,
    w: Int,
    h: Int,
    lineColor: Int,
    textColor: Int,
    density: Float,
): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val valid = values.filterNotNull()
    if (valid.size < 2) return bmp

    val top = niceMax(valid.maxOrNull() ?: 1f)
    val n = values.size
    val padX = 8f * density
    val padTop = 14f * density
    val padBottom = 13f * density

    fun x(i: Int): Float = padX + (w - 2 * padX) * i / (n - 1f)
    fun y(v: Float): Float = (h - padBottom) - (h - padBottom - padTop) * (v / top).coerceIn(0f, 1f)

    val path = Path()
    var started = false
    values.forEachIndexed { i, v ->
        if (v == null) {
            started = false
        } else {
            if (started) path.lineTo(x(i), y(v)) else path.moveTo(x(i), y(v))
            started = true
        }
    }

    val firstIdx = values.indexOfFirst { it != null }
    val lastIdx = values.indexOfLast { it != null }
    if (lastIdx > firstIdx) {
        val fillPath = Path().apply {
            addPath(path)
            lineTo(x(lastIdx), h - padBottom)
            lineTo(x(firstIdx), h - padBottom)
            close()
        }
        canvas.drawPath(
            fillPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(72, Color.red(lineColor), Color.green(lineColor), Color.blue(lineColor))
                style = Paint.Style.FILL
            },
        )
    }
    canvas.drawPath(
        path,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = lineColor
            style = Paint.Style.STROKE
            strokeWidth = max(2.5f, 3f * density * 0.6f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        },
    )

    // 基线
    val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = 1f * density
    }
    canvas.drawLine(padX, h - padBottom, w - padX, h - padBottom, baselinePaint)

    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 9f * density
    }

    // 底部时间刻度（每 4 个点一个）
    for (i in labels.indices) {
        if (i % 4 != 0 && i != labels.lastIndex) continue
        val t = labels[i].substringBefore(":") + "时"
        val tw = text.measureText(t)
        val tx = (x(i) - tw / 2).coerceIn(0f, w - tw)
        canvas.drawText(t, tx, h - 2f * density, text)
    }

    // 峰值标注
    val peakIdx = values.indexOfFirst { it != null && it == valid.maxOrNull() }
    if (peakIdx >= 0) {
        val pv = values[peakIdx] ?: 0f
        canvas.drawCircle(x(peakIdx), y(pv), 3.2f * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = lineColor })
        val label = "峰值 " + String.format(Locale.US, "%.1f", pv)
        val tw = text.measureText(label)
        text.color = Color.argb(230, 255, 255, 255)
        canvas.drawText(label, w - tw - 2f * density, 10f * density, text)
    }
    return bmp
}

private fun build(
    ctx: Context,
    mgr: AppWidgetManager,
    id: Int,
    small: Boolean,
    place: String,
    d: WindData?,
    use100: Boolean,
): RemoteViews {
    val layout = if (small) R.layout.widget_2x2 else R.layout.widget_4x2
    val v = RemoteViews(ctx.packageName, layout)
    v.setOnClickPendingIntent(R.id.widget_root, openApp(ctx))

    val density = ctx.resources.displayMetrics.density
    val (w, h) = sizeOf(ctx, mgr, id)
    val bar = widgetBarColors(ctx)
    v.setImageViewBitmap(R.id.widget_bg, roundBg(w, h, bar[0], bar[1], 18f * density))

    if (d == null) {
        v.setTextViewText(R.id.tv_place, place.ifBlank { "风速" })
        v.setTextViewText(R.id.tv_speed, "--")
        v.setTextViewText(R.id.tv_meta, "点按打开 App")
        v.setTextViewText(R.id.tv_time, "")
        return v
    }

    val first = d.hours.firstOrNull()
    val mainSpeed = if (use100) first?.speed100 else d.snapshot.speed10
    val dir = if (use100) first?.dir100 else d.snapshot.dir10
    val hours = d.hours.take(12)

    v.setTextViewText(R.id.tv_place, place)
    v.setTextViewText(R.id.tv_speed, fmt1(mainSpeed))
    v.setTextViewText(R.id.tv_meta, "${dirName(dir)}风 ${dir ?: "--"}° · 阵风 ${fmt1(d.snapshot.gust10)}")
    v.setTextViewText(R.id.tv_time, "${fmtTime(d.snapshot.time)} 更新")

    if (!small) {
        val values = hours.map { if (use100) it.speed100 else it.speed10 }
        val labels = hours.map { it.label }
        val line = runCatching { ctx.getColor(android.R.color.system_accent1_100) }.getOrDefault(0xFFFFFFFF.toInt())
        v.setImageViewBitmap(
            R.id.widget_chart,
            sparkline(values, labels, w - (28 * density).toInt(), (h * 0.42f).toInt(), line, Color.WHITE, density),
        )
    }
    return v
}

private fun refresh(
    context: Context,
    mgr: AppWidgetManager,
    ids: IntArray,
    small: Boolean,
) {
    val prefs = context.getSharedPreferences("windview", Context.MODE_PRIVATE)
    val use100 = prefs.getBoolean("use100", true)
    val lat = prefs.getString("lat", null)?.toDoubleOrNull()
    val lon = prefs.getString("lon", null)?.toDoubleOrNull()
    val place = prefs.getString("name", null)?.takeIf { it.isNotBlank() } ?: "未选择位置"

    if (lat == null || lon == null) {
        ids.forEach { id -> mgr.updateAppWidget(id, build(context, mgr, id, small, place, null, use100)) }
        return
    }

    Thread {
        val data = runCatching { runBlocking { fetchWind(lat, lon) } }.getOrNull()
        ids.forEach { id ->
            runCatching { mgr.updateAppWidget(id, build(context, mgr, id, small, place, data, use100)) }
        }
    }.start()
}

/** 桌面 2×2 卡片 */
class WindWidgetSmall : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        refresh(context, appWidgetManager, appWidgetIds, small = true)
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 12000L)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        refresh(context, appWidgetManager, intArrayOf(appWidgetId), small = true)
    }
}

/** 桌面 4×2 卡片 */
class WindWidgetWide : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        refresh(context, appWidgetManager, appWidgetIds, small = false)
        Handler(Looper.getMainLooper()).postDelayed({ pending.finish() }, 12000L)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        refresh(context, appWidgetManager, intArrayOf(appWidgetId), small = false)
    }
}

/** App 刚拿到新数据时顺手推给卡片（数据与配色一起更新） */
internal fun pushWidgets(ctx: Context, data: WindData) {
    val app = ctx.applicationContext
    Thread {
        runCatching {
            val prefs = app.getSharedPreferences("windview", Context.MODE_PRIVATE)
            val use100 = prefs.getBoolean("use100", true)
            val place = prefs.getString("name", null)?.takeIf { it.isNotBlank() } ?: "未选择位置"
            val mgr = AppWidgetManager.getInstance(app)
            mgr.getAppWidgetIds(ComponentName(app, WindWidgetSmall::class.java)).forEach { id ->
                runCatching { mgr.updateAppWidget(id, build(app, mgr, id, true, place, data, use100)) }
            }
            mgr.getAppWidgetIds(ComponentName(app, WindWidgetWide::class.java)).forEach { id ->
                runCatching { mgr.updateAppWidget(id, build(app, mgr, id, false, place, data, use100)) }
            }
        }
    }.start()
}

/** 壁纸变了：让卡片按新壁纸色重画 */
internal fun updateAllWidgets(ctx: Context) {
    val app = ctx.applicationContext
    Thread {
        runCatching {
            val mgr = AppWidgetManager.getInstance(app)
            val smallIds = mgr.getAppWidgetIds(ComponentName(app, WindWidgetSmall::class.java))
            if (smallIds.isNotEmpty()) refresh(app, mgr, smallIds, true)
            val wideIds = mgr.getAppWidgetIds(ComponentName(app, WindWidgetWide::class.java))
            if (wideIds.isNotEmpty()) refresh(app, mgr, wideIds, false)
        }
    }.start()
}
