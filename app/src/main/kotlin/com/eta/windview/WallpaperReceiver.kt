package com.eta.windview

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 换壁纸后立刻让桌面卡片换成新配色（否则要等 30 分钟的定时刷新） */
class WallpaperChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_WALLPAPER_CHANGED) {
            updateAllWidgets(context)
        }
    }
}
