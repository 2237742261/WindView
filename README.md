# WindView · 风速

实时风速 App（Android，Compose）。打开就能看到当前位置的风速，不需要登录、没有广告、不申请多余权限。

## 功能

- **实时风速**：默认显示轮毂高度 100 m 风速，可切到地面 10 m；附风向、阵风、气温、机组运行区间提示。
- **未来 24 小时折线**：可左右滑动（或点按）查看任意时刻的风速与阵风，标出 3 m/s 切入、25 m/s 切出参考线。Y 轴按 5 / 8 / 12 / 16 … 阶梯自适应，风小的时候线也不会压成一条直线。
- **七日最大风速**：从明天起 7 天，显示当日最大 / 最小风速、出现时刻和风向；点某一天展开当日 24 小时曲线。
- **桌面卡片**：2×2（风速 + 风向 + 阵风）与 4×2（多一条 12 小时折线，带时间刻度与峰值标注），30 分钟自动刷新，点按打开 App。
- **壁纸取色**：从壁纸的 primary / secondary / tertiary 三种颜色派生整套配色，换壁纸后 App 与桌面卡片一起变色。
- **秒开**：上次数据缓存在本地，冷启动先铺满界面再静默刷新。

## 数据源

[Open-Meteo](https://open-meteo.com/) 免费接口，底层是 ECMWF IFS / ICON 等数值天气预报模式。

10 m 与 100 m 风速都是**模式的原生垂直层输出**，不是用 10 m 风速推算的。需要说明的是：模式格点约 10 km，地形复杂或沿海处局地偏差可能有一两米每秒的误差，适合看趋势和当班前判断；检修窗口、吊装窗口这类决策请以场内测风塔 / 激光雷达 / SCADA 机舱风速为准。

## 构建

环境：JDK 21+、Android SDK（platform 36、build-tools 35）、Gradle 9.6、AGP 9.3.2。

```bash
ANDROID_HOME=/path/to/sdk ./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/WindView-<version>.apk`（用 `release.keystore` 签名，密码见 `app/build.gradle.kts`，就是 Android 默认 debug key）。

## 结构

| 文件 | 作用 |
| --- | --- |
| `WindCore.kt` | 数据模型、Open-Meteo 请求、本地缓存、工具函数 |
| `Palette.kt` | 壁纸三色取色 → Compose 配色方案 |
| `MainActivity.kt` | 单 Activity + Compose 界面（含自绘折线图手势） |
| `WidgetProvider.kt` | 2×2 / 4×2 桌面卡片，折线用 Bitmap 绘制 |
| `WallpaperReceiver.kt` | 换壁纸后立刻重绘卡片配色 |
