package com.eta.windview

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Color as AColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** 壁纸取出的三主色（壁纸取色原生就是三种），分别给不同卡片用 */
data class SeedColors(val primary: Int, val secondary: Int, val tertiary: Int)

private fun shiftHue(color: Int, delta: Float): Int {
    val a = FloatArray(3)
    AColor.colorToHSV(color, a)
    a[0] = (((a[0] + delta) % 360f) + 360f) % 360f
    return AColor.HSVToColor(a)
}

/**
 * 取壁纸配色：优先 WallpaperManager 的 primary / secondary / tertiary（换壁纸即变）；
 * 拿不到时退回系统主题色，再退回青蓝并自行转色相。
 */
internal fun wallpaperSeeds(ctx: Context): SeedColors {
    runCatching {
        val wm = WallpaperManager.getInstance(ctx)
        val c = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        if (c != null) {
            val p = c.primaryColor.toArgb()
            val s = c.secondaryColor?.toArgb() ?: shiftHue(p, 32f)
            val t = c.tertiaryColor?.toArgb() ?: shiftHue(p, -36f)
            return SeedColors(p, s, t)
        }
    }
    runCatching {
        val p = ctx.getColor(android.R.color.system_accent1_500)
        return SeedColors(p, shiftHue(p, 32f), shiftHue(p, -36f))
    }
    val p = 0xFF3F8FC4.toInt()
    return SeedColors(p, shiftHue(p, 32f), shiftHue(p, -36f))
}

private fun hsvColor(h: Float, s: Float, v: Float): Color = Color(
    AColor.HSVToColor(
        floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)),
    ),
)

private fun hue(color: Int): Float {
    val a = FloatArray(3)
    AColor.colorToHSV(color, a)
    return a[0]
}

private fun sat(color: Int): Float {
    val a = FloatArray(3)
    AColor.colorToHSV(color, a)
    return a[1].coerceIn(0.08f, 0.85f)
}

/**
 * 三种壁纸色 → 三套卡片色。
 * 卡片刻意保持低饱和、高对比（正文用深色 on*，卡片略比背景深一点），既跟壁纸又读得清。
 */
internal fun schemeFromSeeds(seeds: SeedColors, dark: Boolean): ColorScheme {
    val hp = hue(seeds.primary)
    val sp = sat(seeds.primary)
    val hs = hue(seeds.secondary)
    val ss = sat(seeds.secondary)
    val ht = hue(seeds.tertiary)
    val st = sat(seeds.tertiary)

    return if (dark) {
        darkColorScheme(
            primary = hsvColor(hp, sp * 0.70f + 0.20f, 0.84f),
            onPrimary = hsvColor(hp, 0.5f, 0.10f),
            primaryContainer = hsvColor(hp, sp * 0.62f, 0.42f),
            onPrimaryContainer = hsvColor(hp, 0.08f, 0.97f),
            secondary = hsvColor(hs, ss * 0.60f, 0.80f),
            secondaryContainer = hsvColor(hs, ss * 0.58f, 0.40f),
            onSecondaryContainer = hsvColor(hs, 0.06f, 0.97f),
            tertiary = hsvColor(ht, st * 0.60f, 0.80f),
            tertiaryContainer = hsvColor(ht, st * 0.56f, 0.39f),
            onTertiaryContainer = hsvColor(ht, 0.06f, 0.97f),
            background = hsvColor(hp, sp * 0.24f, 0.075f),
            onBackground = hsvColor(hp, 0.06f, 0.98f),
            surface = hsvColor(hp, sp * 0.22f, 0.11f),
            onSurface = hsvColor(hp, 0.06f, 0.98f),
            surfaceVariant = hsvColor(hp, sp * 0.16f, 0.15f),
            onSurfaceVariant = hsvColor(hp, 0.08f, 0.82f),
            error = Color(0xFFFF8A80),
        )
    } else {
        // 浅色：底色压到近白，卡片只留一层很淡的色，正文用近墨色——整屏不再糊成一片同色
        lightColorScheme(
            primary = hsvColor(hp, 0.62f, 0.46f),
            onPrimary = Color.White,
            primaryContainer = hsvColor(hp, 0.10f, 0.972f),
            onPrimaryContainer = hsvColor(hp, 0.35f, 0.16f),
            secondary = hsvColor(hs, 0.58f, 0.44f),
            secondaryContainer = hsvColor(hs, 0.10f, 0.975f),
            onSecondaryContainer = hsvColor(hs, 0.33f, 0.16f),
            tertiary = hsvColor(ht, 0.58f, 0.44f),
            tertiaryContainer = hsvColor(ht, 0.09f, 0.970f),
            onTertiaryContainer = hsvColor(ht, 0.31f, 0.16f),
            background = hsvColor(hp, 0.26f, 0.905f),
            onBackground = hsvColor(hp, 0.36f, 0.13f),
            surface = hsvColor(hp, 0.06f, 0.985f),
            onSurface = hsvColor(hp, 0.36f, 0.13f),
            surfaceVariant = hsvColor(hp, 0.10f, 0.975f),
            onSurfaceVariant = hsvColor(hp, 0.30f, 0.30f),
            error = Color(0xFFB3261E),
        )
    }
}

/** 桌面卡片用的深色渐变（两色），随壁纸色相走，白字始终可读 */
internal fun widgetBarColors(ctx: Context): IntArray {
    val seeds = wallpaperSeeds(ctx)
    val h = hue(seeds.primary)
    val top = AColor.HSVToColor(floatArrayOf(h, 0.34f, 0.54f))
    val bottom = AColor.HSVToColor(floatArrayOf(hue(seeds.tertiary), 0.40f, 0.36f))
    return intArrayOf(top, bottom)
}

/** 顶部渐变起点：不透明的浅彩，莫奈感来源；绝不能带 alpha——半透明会透出桌面壁纸 */
internal fun backgroundTop(seeds: SeedColors, dark: Boolean): Color {
    val hp = hue(seeds.primary)
    return if (dark) hsvColor(hp, 0.34f, 0.17f) else hsvColor(hp, 0.34f, 0.855f)
}

/** 折线图卡片底色：同色相但几乎中性，线才看得清 */
internal fun chartContainer(seeds: SeedColors, dark: Boolean): Color {
    val hp = hue(seeds.primary)
    val sp = sat(seeds.primary)
    return if (dark) {
        hsvColor(hp, sp * 0.45f, 0.30f)
    } else {
        hsvColor(hp, sp * 0.045f, 0.991f)
    }
}

/** 第四色：用壁纸的副色，别硬转色相——+62 度那种凑色最容易脏 */
private fun quatHue(seeds: SeedColors): Float = hue(seeds.secondary)

internal fun quaternaryContainer(seeds: SeedColors, dark: Boolean): Color {
    val h = quatHue(seeds)
    val s = sat(seeds.secondary)
    return if (dark) hsvColor(h, s * 0.55f, 0.40f) else hsvColor(h, s * 0.13f + 0.02f, 0.966f)
}

internal fun onQuaternary(seeds: SeedColors, dark: Boolean): Color {
    val h = quatHue(seeds)
    return if (dark) hsvColor(h, 0.08f, 0.97f) else hsvColor(h, 0.22f, 0.15f)
}
