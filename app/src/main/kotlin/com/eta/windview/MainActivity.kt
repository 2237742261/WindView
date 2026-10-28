package com.eta.windview

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface LocState {
    data object Locating : LocState
    data class Ok(val lat: Double, val lon: Double, val name: String, val manual: Boolean) : LocState
    data class Failed(val message: String) : LocState
}

sealed interface DataState {
    data object Loading : DataState
    data class Ready(val data: WindData) : DataState
    data class Failed(val message: String) : DataState
}

// ---------------- Activity ----------------

class MainActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("windview", Context.MODE_PRIVATE) }
    private val locState = mutableStateOf<LocState>(LocState.Locating)
    private val dataState = mutableStateOf<DataState>(DataState.Loading)
    private val refreshKey = mutableStateOf(0)
    private val alt100 = mutableStateOf(true)
    private val refreshing = mutableStateOf(false)
    private val seeds = mutableStateOf(SeedColors(0xFF3F8FC4.toInt(), 0xFF3F8FC4.toInt(), 0xFF3F8FC4.toInt()))

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it } || hasLocPerm()) locate() else fallbackToSaved()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching {
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        alt100.value = prefs.getBoolean("use100", true)
        seeds.value = wallpaperSeeds(this)
        // 先用本地缓存铺满界面，网络结果回来再静默替换（点卡片进来不再看到空加载）
        loadCache(this)?.let { dataState.value = DataState.Ready(it) }

        setContent {
            val loc by locState
            val data by dataState
            val use100 by alt100
            val busy by refreshing
            val tick by refreshKey
            val themeSeeds by seeds
            WindTheme(seeds = themeSeeds) {
                WindApp(
                    seeds = themeSeeds,
                    loc = loc,
                    data = data,
                    use100 = use100,
                    refreshing = busy,
                    refreshTick = tick,
                    onData = { next ->
                        when (next) {
                            is DataState.Loading -> if (dataState.value !is DataState.Ready) {
                                dataState.value = next
                            }
                            is DataState.Ready -> {
                                dataState.value = next
                                refreshing.value = false
                                saveCache(this, next.data)
                                pushWidgets(this, next.data)
                            }
                            is DataState.Failed -> {
                                if (dataState.value !is DataState.Ready) dataState.value = next
                                refreshing.value = false
                            }
                        }
                    },
                    onToggleHeight = { v ->
                        alt100.value = v
                        prefs.edit().putBoolean("use100", v).apply()
                        dataState.value.let { st ->
                            if (st is DataState.Ready) pushWidgets(this, st.data)
                        }
                    },
                    onRefresh = {
                        refreshing.value = true
                        refreshKey.value = refreshKey.value + 1
                    },
                    onPickPlace = { lat, lon, name -> pickPlace(lat, lon, name) },
                    onUseGps = { useGps() },
                )
            }
        }
        autoStart()
    }

    override fun onResume() {
        super.onResume()
        // 换过壁纸后重新取色（App 内与桌面卡片一起更新）
        seeds.value = wallpaperSeeds(this)
        (dataState.value as? DataState.Ready)?.let { pushWidgets(this, it.data) }
        val cached = (dataState.value as? DataState.Ready)?.data
        val stale = cached == null || System.currentTimeMillis() - cached.fetchedAt > 3 * 60 * 1000L
        if (stale && locState.value is LocState.Ok) refreshKey.value = refreshKey.value + 1
    }

    // ---- 位置 ----

    private fun autoStart() {
        val manual = prefs.getBoolean("manual", false)
        val lat = prefs.getString("lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("lon", null)?.toDoubleOrNull()
        if (lat != null && lon != null) {
            locState.value = LocState.Ok(lat, lon, prefs.getString("name", null) ?: fmtCoord(lat, lon), manual)
            if (!manual) {
                if (hasLocPerm()) locate() else requestPerm()
            }
        } else {
            if (hasLocPerm()) locate() else requestPerm()
        }
    }

    private fun hasLocPerm(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestPerm() {
        if (locState.value !is LocState.Ok) locState.value = LocState.Locating
        permLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun useGps() {
        prefs.edit().putBoolean("manual", false).apply()
        if (locState.value !is LocState.Ok) locState.value = LocState.Locating
        if (hasLocPerm()) locate() else requestPerm()
    }

    private fun pickPlace(lat: Double, lon: Double, name: String) {
        prefs.edit()
            .putString("lat", lat.toString())
            .putString("lon", lon.toString())
            .putString("name", name)
            .putBoolean("manual", true)
            .apply()
        locState.value = LocState.Ok(lat, lon, name, true)
    }

    private fun locate() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            fallbackToSaved()
            return
        }
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused", "passive")) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                val b = best
                if (b == null || l.time > b.time) best = l
            } catch (_: Exception) {
            }
        }
        best?.let { onLocated(it.latitude, it.longitude) }

        var listener: LocationListener? = null
        val cb = LocationListener { l: Location ->
            onLocated(l.latitude, l.longitude)
            listener?.let { runCatching { lm.removeUpdates(it) } }
            listener = null
        }
        listener = cb
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                lm.requestLocationUpdates(p, 1000L, 0f, cb, Looper.getMainLooper())
            } catch (_: Exception) {
            }
        }
        Handler(Looper.getMainLooper()).postDelayed({
            listener?.let { runCatching { lm.removeUpdates(it) } }
            listener = null
        }, 15000L)
    }

    private fun onLocated(lat: Double, lon: Double) {
        val cachedName = prefs.getString("name", null)
        val name = cachedName?.takeIf { it.isNotBlank() } ?: fmtCoord(lat, lon)
        prefs.edit().putString("lat", lat.toString()).putString("lon", lon.toString()).apply()
        locState.value = LocState.Ok(lat, lon, name, false)
        resolveName(lat, lon)
    }

    private fun resolveName(lat: Double, lon: Double) {
        Thread {
            var found: String? = null
            try {
                @Suppress("DEPRECATION")
                val list = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
                val a = list?.firstOrNull()
                if (a != null) {
                    found = joinDistinct(
                        a.adminArea?.toString(),
                        a.locality?.toString(),
                        a.subAdminArea?.toString(),
                        a.subLocality?.toString(),
                    )
                }
            } catch (_: Exception) {
            }
            val finalName = found?.takeIf { it.isNotBlank() } ?: fmtCoord(lat, lon)
            runOnUiThread {
                val cur = locState.value
                if (cur is LocState.Ok && !cur.manual) {
                    prefs.edit().putString("name", finalName).apply()
                    locState.value = cur.copy(name = finalName)
                }
            }
        }.start()
    }

    private fun fallbackToSaved() {
        val lat = prefs.getString("lat", null)?.toDoubleOrNull()
        val lon = prefs.getString("lon", null)?.toDoubleOrNull()
        if (lat != null && lon != null) {
            locState.value = LocState.Ok(lat, lon, prefs.getString("name", null) ?: fmtCoord(lat, lon), true)
        } else if (locState.value !is LocState.Ok) {
            locState.value = LocState.Failed("没有定位权限。点顶部地点名搜索城市，或授予定位权限。")
        }
    }
}

// ---------------- 主题（壁纸取色） ----------------

@Composable
private fun WindTheme(seeds: SeedColors, content: @Composable () -> Unit) {
    // 固定浅色：不跟随系统深色模式，只跟壁纸取色
    val scheme = remember(seeds) { schemeFromSeeds(seeds, false) }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ---------------- 主界面 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WindApp(
    seeds: SeedColors,
    loc: LocState,
    data: DataState,
    use100: Boolean,
    refreshing: Boolean,
    refreshTick: Int,
    onData: (DataState) -> Unit,
    onToggleHeight: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onPickPlace: (Double, Double, String) -> Unit,
    onUseGps: () -> Unit,
) {
    var showSearch by remember { mutableStateOf(false) }
    val locKey = (loc as? LocState.Ok)?.let { it.lat to it.lon }

    LaunchedEffect(locKey, refreshTick) {
        val ll = locKey ?: return@LaunchedEffect
        while (true) {
            onData(DataState.Loading)
            try {
                onData(DataState.Ready(fetchWind(ll.first, ll.second)))
            } catch (e: Exception) {
                onData(DataState.Failed(e.message ?: "获取失败"))
            }
            delay(5 * 60 * 1000L)
        }
    }

    val ready = (data as? DataState.Ready)?.data
    val cs = MaterialTheme.colorScheme
    val dark = false
    val chartBg = chartContainer(seeds, dark)
    val quatBg = quaternaryContainer(seeds, dark)
    val quatFg = onQuaternary(seeds, dark)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        cs.primaryContainer.copy(alpha = if (dark) 0.40f else 0.55f),
                        cs.background,
                        cs.background,
                    ),
                ),
            ),
    ) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 108.dp),
            ) {
                LocationBar(
                    title = when (loc) {
                        is LocState.Ok -> loc.name
                        is LocState.Locating -> "正在定位…"
                        is LocState.Failed -> "未选择位置"
                    },
                    subtitle = when (loc) {
                        is LocState.Ok -> (if (loc.manual) "手动选点" else "本机定位") +
                            " · " + fmtCoord(loc.lat, loc.lon)
                        else -> "Open-Meteo"
                    },
                    onTap = { showSearch = true },
                )

                when {
                    loc is LocState.Failed && ready == null -> HintCard(loc.message) { showSearch = true }
                    ready == null && data is DataState.Failed -> HintCard("获取风速失败：${data.message}") { showSearch = true }
                    ready == null -> HintCard("正在获取风速…") { showSearch = true }
                }

                if (ready != null) {
                    HeroCard(ready, use100)
                    Spacer(Modifier.height(14.dp))
                    HourlyStrip(ready, use100)
                    Spacer(Modifier.height(12.dp))
                    ChartCard(ready, use100, chartBg)
                    Spacer(Modifier.height(12.dp))
                    DailyCard(ready.days)
                    Spacer(Modifier.height(12.dp))
                    DetailsCard(ready, use100, quatBg, quatFg)
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "下拉刷新 · 每 5 分钟自动更新 · 数据源 Open-Meteo（数值天气预报模式输出）",
                        fontSize = 11.sp,
                        color = cs.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        FloatingHeightSwitch(
            use100 = use100,
            onToggleHeight = onToggleHeight,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showSearch) {
        SearchDialog(
            onDismiss = { showSearch = false },
            onPick = { lat, lon, name ->
                onPickPlace(lat, lon, name)
                showSearch = false
            },
            onUseGps = {
                onUseGps()
                showSearch = false
            },
        )
    }
}

@Composable
private fun LocationBar(title: String, subtitle: String, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 24.dp, bottom = 4.dp)
            .clickable { onTap() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(subtitle, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun HintCard(message: String, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 18.dp)
            .background(cs.surfaceVariant.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
            .clickable { onTap() }
            .padding(18.dp),
    ) {
        Text(message, fontSize = 13.sp, color = cs.onSurfaceVariant, lineHeight = 20.sp)
    }
}

private fun machineStatus(v: Float?): String = when {
    v == null -> "--"
    v < 3f -> "机组未并网（低于切入）"
    v < 12f -> "发电中 · 部分负荷"
    v < 25f -> "发电中 · 接近满发"
    else -> "超切出风速 · 停机"
}

private fun workHint(v: Float?): String = when {
    v == null -> ""
    v < 8f -> "高空作业条件较好"
    v < 10f -> "上塔/机舱作业需留意阵风"
    else -> "不建议高空作业（>10 m/s）"
}

@Composable
private fun HeroCard(data: WindData, use100: Boolean) {
    val cs = MaterialTheme.colorScheme
    val first = data.hours.firstOrNull()
    val mainSpeed = if (use100) first?.speed100 else data.snapshot.speed10
    val dir = if (use100) first?.dir100 else data.snapshot.dir10

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(fmt1(mainSpeed), fontSize = 94.sp, fontWeight = FontWeight.Light, color = cs.onBackground)
            Text(
                "m/s",
                fontSize = 19.sp,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 20.dp, start = 4.dp),
            )
        }
        Text("${dirName(dir)}风 ${dir ?: "--"}°", fontSize = 15.sp, color = cs.onBackground)
        Spacer(Modifier.height(2.dp))
        Text(
            "阵风 ${fmt1(data.snapshot.gust10)} m/s · 气温 ${fmt1(data.snapshot.temp)}°C",
            fontSize = 13.sp,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .background(cs.secondaryContainer, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        ) {
            Text(
                machineStatus(mainSpeed),
                fontSize = 12.sp,
                color = cs.onSecondaryContainer,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            workHint(mainSpeed) + " · 更新 " + fmtTime(data.snapshot.time),
            fontSize = 11.sp,
            color = cs.onSurfaceVariant,
        )
    }
}

@Composable
private fun FloatingHeightSwitch(
    use100: Boolean,
    onToggleHeight: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp)
            .shadow(20.dp, shape)
            .clip(shape)
            .background(cs.primaryContainer, shape)
            .padding(4.dp),
    ) {
        Pill("轮毂 100 m", use100) { onToggleHeight(true) }
        Pill("地面 10 m", !use100) { onToggleHeight(false) }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.22f) else Color.Transparent, shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun HourlyStrip(data: WindData, use100: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .background(cs.secondaryContainer, RoundedCornerShape(22.dp))
            .padding(vertical = 12.dp),
    ) {
        Text(
            "逐小时风速",
            fontSize = 12.sp,
            color = cs.onSecondaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(data.hours) { h ->
                val v = if (use100) h.speed100 else h.speed10
                val d = if (use100) h.dir100 else h.dir10
                Column(
                    Modifier
                        .width(60.dp)
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(h.label.substringBefore(":"), fontSize = 12.sp, color = cs.onSecondaryContainer.copy(alpha = 0.7f))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        fmt1(v),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        dirName(d) + "风",
                        fontSize = 10.sp,
                        color = cs.onSecondaryContainer.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartCard(data: WindData, use100: Boolean, chartBg: Color) {
    val cs = MaterialTheme.colorScheme
    val hours = data.hours
    val line = if (use100) hours.map { it.speed100 } else hours.map { it.speed10 }
    val gust = hours.map { it.gust10 }
    val dirs = if (use100) hours.map { it.dir100 } else hours.map { it.dir10 }
    var selIndex by remember(hours) { mutableStateOf<Int?>(null) }
    LaunchedEffect(selIndex) {
        if (selIndex != null) {
            delay(4000)
            selIndex = null
        }
    }
    val peak = (line.filterNotNull() + gust.filterNotNull()).maxOrNull() ?: 0f
    val chartMax = niceMax(peak)
    val focus = selIndex ?: 0
    val gustColor = cs.onSurfaceVariant.copy(alpha = 0.45f)

    Column(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .background(chartBg, RoundedCornerShape(22.dp))
            .padding(vertical = 14.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("未来 24 小时", fontSize = 13.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text("左右滑动查看", fontSize = 11.sp, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
        }
        Spacer(Modifier.height(2.dp))
        // 当前/选中时刻的值做图例：── 东北风 4.6 m/s　── 阵风 5.2 m/s
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendValue(
                color = cs.primary,
                text = dirName(dirs.getOrNull(focus)) + "风 " + fmt1(line.getOrNull(focus)) + " m/s",
                bold = true,
            )
            LegendValue(
                color = gustColor,
                text = "阵风 " + fmt1(gust.getOrNull(focus)) + " m/s",
                bold = false,
            )
        }
        Spacer(Modifier.height(4.dp))
        LineChart(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            labels = hours.map { it.label },
            values = line,
            second = gust,
            dirs = dirs,
            maxVal = chartMax,
            selection = selIndex,
            onSelect = { selIndex = it },
            accent = cs.primary,
            sub = cs.onSurfaceVariant,
            secondColor = gustColor,
        )
        if (chartMax >= 25f) {
            Text(
                "红虚线＝25 m/s 切出风速，超过机组必须停机",
                fontSize = 11.sp,
                color = Color(0xFFE57373),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun LegendValue(color: Color, text: String, bold: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(16.dp)
                .height(3.dp)
                .background(color, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            fontSize = 12.sp,
            color = if (bold) cs.onSurface else cs.onSurfaceVariant,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * 通用风速折线。
 * 手势：只有明显横向拖动才接管（更新选中点）；竖向滑动一律交给页面滚动，避免"卡手"。
 */
@Composable
private fun LineChart(
    modifier: Modifier = Modifier,
    labels: List<String>,
    values: List<Float?>,
    second: List<Float?>?,
    dirs: List<Int?>? = null,
    maxVal: Float,
    selection: Int?,
    onSelect: ((Int?) -> Unit)?,
    accent: Color,
    sub: Color,
    secondColor: Color = Color(0x66FFFFFF),
    height: Dp = 205.dp,
    showTimeAxis: Boolean = true,
) {
    val axisPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    }
    val bubblePaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            isFakeBoldText = true
        }
    }
    val gridColor = sub.copy(alpha = 0.16f)
    // 手势闭包会一直活着（pointerInput 只在 size 变化时重建），
    // 所以必须用 rememberUpdatedState 拿最新回调，否则数据刷新后写到的是旧的选中状态
    val selectCb = rememberUpdatedState(onSelect)

    Canvas(
        modifier = modifier
            .height(height)
            .then(
                if (onSelect == null) {
                    Modifier
                } else {
                    Modifier.pointerInput(labels.size) {
                        val leftPx = 34.dp.toPx()
                        val rightPx = size.width - 10.dp.toPx()
                        val slop = 7.dp.toPx()

                        fun idxOf(x: Float): Int {
                            if (labels.size <= 1) return 0
                            val t = ((x - leftPx) / (rightPx - leftPx)).coerceIn(0f, 1f)
                            return (t * (labels.size - 1)).roundToInt().coerceIn(0, labels.size - 1)
                        }

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var horizontal: Boolean? = null
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y

                                if (horizontal == null && (abs(dx) > slop || abs(dy) > slop)) {
                                    horizontal = abs(dx) > abs(dy)
                                }
                                if (horizontal == true) {
                                    selectCb.value?.invoke(idxOf(change.position.x))
                                    change.consume()
                                }
                                if (!change.pressed) {
                                    // 轻点：没拖动就直接选中；消费掉，避免触发外层「收起当天曲线」
                                    if (horizontal == null) {
                                        selectCb.value?.invoke(idxOf(change.position.x))
                                        change.consume()
                                    }
                                    break
                                }
                                if (horizontal == false) break  // 竖向手势交给外层滚动
                            }
                        }
                    }
                },
            ),
    ) {
        val w = size.width
        val h = size.height
        val left = 34.dp.toPx()
        val right = w - 10.dp.toPx()
        val top = 24.dp.toPx()
        val bottom = h - if (showTimeAxis) 22.dp.toPx() else 6.dp.toPx()
        axisPaint.textSize = 10.sp.toPx()
        axisPaint.color = sub.toArgb()
        bubblePaint.textSize = 11.sp.toPx()

        fun xAt(i: Int): Float =
            if (labels.size <= 1) left else left + (right - left) * i / (labels.size - 1).toFloat()

        fun yAt(v: Float): Float = bottom - (bottom - top) * (v / maxVal).coerceIn(0f, 1f)

        fun axisText(v: Float): String =
            if (v == v.toInt().toFloat()) v.toInt().toString()
            else String.format(Locale.US, "%.1f", v)

        for (k in 0..2) {
            val v = maxVal * k / 2f
            val y = yAt(v)
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1f)
            drawContext.canvas.nativeCanvas.drawText(
                axisText(v), 4.dp.toPx(), y + 4.dp.toPx(), axisPaint,
            )
        }

        if (3f <= maxVal) {
            val y = yAt(3f)
            drawLine(
                Color(0xFF66BB6A).copy(alpha = 0.5f),
                Offset(left, y), Offset(right, y),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
            )
        }
        if (25f <= maxVal) {
            val y = yAt(25f)
            drawLine(
                Color(0xFFE57373).copy(alpha = 0.75f),
                Offset(left, y), Offset(right, y),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
            )
        }

        fun buildPath(vals: List<Float?>): Path {
            val p = Path()
            var started = false
            for (i in vals.indices) {
                val v = vals[i]
                if (v == null) {
                    started = false
                    continue
                }
                val x = xAt(i)
                val y = yAt(v)
                if (started) p.lineTo(x, y) else p.moveTo(x, y)
                started = true
            }
            return p
        }

        second?.let {
            drawPath(
                buildPath(it),
                secondColor,
                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        val mainPath = buildPath(values)
        val firstIdx = values.indexOfFirst { it != null }
        val lastIdx = values.indexOfLast { it != null }
        if (firstIdx >= 0 && lastIdx > firstIdx) {
            val fillPath = Path().apply {
                addPath(mainPath)
                lineTo(xAt(lastIdx), bottom)
                lineTo(xAt(firstIdx), bottom)
                close()
            }
            drawPath(
                fillPath,
                Brush.verticalGradient(
                    listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0f)),
                    startY = top,
                    endY = bottom,
                ),
            )
        }
        drawPath(mainPath, accent, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round))

        selection?.let { idx ->
            if (idx in labels.indices) {
                val x = xAt(idx)
                val v = values.getOrNull(idx)
                drawLine(
                    sub.copy(alpha = 0.6f),
                    Offset(x, top - 6.dp.toPx()),
                    Offset(x, bottom),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                )
                if (v != null) drawCircle(accent, 4.dp.toPx(), Offset(x, yAt(v)))
                second?.getOrNull(idx)?.let { gv ->
                    drawCircle(secondColor, 3.dp.toPx(), Offset(x, yAt(gv)))
                }
                val dirTxt = dirs?.getOrNull(idx)?.let { dirName(it) + "风 " } ?: ""
                val label = labels[idx].substringBefore(":") + "时  " + dirTxt + fmt1(v) + " m/s"
                val tw = bubblePaint.measureText(label)
                val bw = tw + 20.dp.toPx()
                val bh = 22.dp.toPx()
                val bx = (x - bw / 2).coerceIn(left, right - bw)
                drawRoundRect(
                    color = Color(0xCC000000),
                    topLeft = Offset(bx, 0f),
                    size = Size(bw, bh),
                    cornerRadius = CornerRadius(bh / 2),
                )
                drawContext.canvas.nativeCanvas.drawText(
                    label, bx + 10.dp.toPx(), bh / 2 + 4.dp.toPx(), bubblePaint,
                )
            }
        }

        if (showTimeAxis) {
            for (i in labels.indices) {
                if (i % 4 != 0) continue
                drawContext.canvas.nativeCanvas.drawText(
                    labels[i].substringBefore(":") + "时",
                    xAt(i) - 12.dp.toPx(),
                    h - 5.dp.toPx(),
                    axisPaint,
                )
            }
        }
    }
}

// 七日（明天起 7 天），点一行展开当天折线
@Composable
private fun DailyCard(days: List<DayPoint>) {
    val cs = MaterialTheme.colorScheme
    if (days.isEmpty()) return
    var expanded by remember(days) { mutableStateOf<String?>(null) }
    var selIndex by remember(expanded) { mutableStateOf<Int?>(null) }
    val globalMax = niceMax(days.mapNotNull { it.max100 }.maxOrNull() ?: 0f)

    Column(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .background(cs.tertiaryContainer, RoundedCornerShape(22.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("七日最大风速 · 点日期看当天曲线", fontSize = 12.sp, color = cs.onTertiaryContainer.copy(alpha = 0.75f))
        Spacer(Modifier.height(8.dp))
        for ((i, d) in days.withIndex()) {
            val open = expanded == d.date
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = if (open) null else d.date
                        selIndex = null
                    },
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        d.label,
                        fontSize = 14.sp,
                        fontWeight = if (open) FontWeight.SemiBold else FontWeight.Normal,
                        color = cs.onTertiaryContainer,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        dirName(d.dir) + "风",
                        fontSize = 11.sp,
                        color = cs.onTertiaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.width(48.dp),
                    )
                    Box(Modifier.weight(1f)) { SpeedBar(d, globalMax) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${fmt1(d.min100)}/${fmt1(d.max100)}",
                        fontSize = 12.sp,
                        color = cs.onTertiaryContainer,
                        modifier = Modifier.width(72.dp),
                        textAlign = TextAlign.End,
                    )
                }
                if (open && d.hourly.isNotEmpty()) {
                    LineChart(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 6.dp),
                        labels = d.hourLabels,
                        values = d.hourly,
                        second = null,
                        maxVal = niceMax((d.max100 ?: 0f) * 1.15f),
                        selection = selIndex,
                        onSelect = { selIndex = it },
                        accent = cs.primary,
                        sub = cs.onTertiaryContainer.copy(alpha = 0.6f),
                        height = 150.dp,
                    )
                    Text(
                        "当天最大 ${fmt1(d.max100)} m/s · ${d.atHour.ifEmpty { "--" }} · 阵风 ${fmt1(d.gust10)} m/s",
                        fontSize = 11.sp,
                        color = cs.onTertiaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
            if (i != days.lastIndex) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(cs.onTertiaryContainer.copy(alpha = 0.10f)),
                )
            }
        }
    }
}

@Composable
private fun SpeedBar(d: DayPoint, globalMax: Float) {
    val cs = MaterialTheme.colorScheme
    val maxV = d.max100 ?: return
    val minV = d.min100 ?: 0f
    val from = (minV / globalMax).coerceIn(0f, 1f)
    val to = (maxV / globalMax).coerceIn(0f, 1f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val w = size.width
        val r = CornerRadius(size.height / 2)
        drawRoundRect(cs.onTertiaryContainer.copy(alpha = 0.12f), Offset.Zero, Size(w, size.height), r)
        val x1 = w * from
        val x2 = max(x1 + size.height, w * to)
        drawRoundRect(
            Brush.horizontalGradient(listOf(cs.primary, cs.secondary)),
            Offset(x1, 0f),
            Size((x2 - x1).coerceAtMost(w - x1), size.height),
            r,
        )
    }
}

@Composable
private fun DetailsCard(data: WindData, use100: Boolean, bg: Color, fg: Color) {
    val cs = MaterialTheme.colorScheme
    val first = data.hours.firstOrNull()
    val ref = if (use100) first?.speed100 ?: data.snapshot.speed10 else data.snapshot.speed10
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(22.dp))
            .background(bg, RoundedCornerShape(22.dp))
            .padding(16.dp),
    ) {
        Text("详情", fontSize = 12.sp, color = fg.copy(alpha = 0.7f))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            DetailItem("阵风(10m)", fmt1(data.snapshot.gust10), "m/s", Modifier.weight(1f), fg)
            DetailItem("风功率密度", powerDensity(ref), "W/m²", Modifier.weight(1f), fg)
            DetailItem("气温", fmt1(data.snapshot.temp), "°C", Modifier.weight(1f), fg)
        }
    }
}

@Composable
private fun DetailItem(label: String, value: String, unit: String, modifier: Modifier, fg: Color) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Text(label, fontSize = 11.sp, color = fg.copy(alpha = 0.68f), maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 21.sp, fontWeight = FontWeight.Medium, color = fg)
            Text(
                unit,
                fontSize = 10.sp,
                color = fg.copy(alpha = 0.68f),
                modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun SearchDialog(
    onDismiss: () -> Unit,
    onPick: (Double, Double, String) -> Unit,
    onUseGps: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择风电场所在位置", fontSize = 17.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("城市 / 乡镇，例如：如东、玉门、张北") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val q = query.trim()
                        if (q.isNotEmpty()) {
                            loading = true
                            error = null
                            scope.launch {
                                try {
                                    results = searchPlace(q)
                                    if (results.isEmpty()) error = "没找到匹配的地点"
                                } catch (e: Exception) {
                                    error = "搜索失败：${e.message}"
                                }
                                loading = false
                            }
                        }
                    }) { Text("搜索") }
                    TextButton(onClick = onUseGps) { Text("用本机定位") }
                }
                if (loading) Text("搜索中…", fontSize = 12.sp, color = cs.onSurfaceVariant)
                error?.let { Text(it, fontSize = 12.sp, color = cs.error) }
                results.forEach { p ->
                    Text(
                        p.label,
                        fontSize = 15.sp,
                        color = cs.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(p.lat, p.lon, p.label) }
                            .padding(vertical = 10.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
