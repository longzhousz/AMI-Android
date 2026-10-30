package com.longzhousz.ami.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.longzhousz.ami.core.*
import com.longzhousz.ami.data.ListeningStatsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ListeningStatsScreen(store: ListeningStatsStore, onBack: () -> Unit,
    initialScale: ListeningScale = ListeningScale.MONTH, onScaleChanged: (ListeningScale) -> Unit = {}) {
    var scaleName by rememberSaveable { mutableStateOf(initialScale.name) }
    var anchor by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    val scale = ListeningScale.valueOf(scaleName)
    val period = remember(scale, anchor) { ListeningPeriod(scale, LocalDate.ofEpochDay(anchor)) }
    val today = LocalDate.now()
    val list = rememberLazyListState()
    var refresh by remember { mutableIntStateOf(0) }
    var report by remember { mutableStateOf<ListeningReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var dates by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val visible = report?.takeIf { it.period == period }
    fun movePeriod(amount: Long) {
        val first = minOf(report?.firstDay ?: today, today)
        anchor = period.move(amount).anchor.coerceIn(first, today).toEpochDay()
    }
    val seen = remember(period) { mutableSetOf<String>() }
    LaunchedEffect(period) { list.requestScrollToItem(0) }
    LaunchedEffect(period, refresh) {
        loading = true; error = null
        try {
            val result = store.report(period)
            report = result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "聆听记录暂时无法读取" }
        finally { loading = false }
    }
    CompositionLocalProvider(SeenCharts provides seen) {
    Column(Modifier.fillMaxSize().testTag("listening-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.Rounded.ArrowBack, "返回设置", onBack)
            Text("我的聆听", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Box {
                ActionIcon(Icons.Rounded.MoreVert, "聆听选项", { menu = true })
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("统计说明") }, { menu = false; dialog = "info" })
                    DropdownMenuItem({ Text("清除聆听记录") }, { menu = false; dialog = "clear" })
                }
            }
        }
        Row(Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ListeningScale.entries.forEach { value ->
                Box(Modifier.weight(1f).padding(4.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (value == scale) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .selectable(value == scale, role = Role.Tab, onClick = {
                        scaleName = value.name; onScaleChanged(value)
                    }).padding(vertical = 10.dp).testTag("listening-${value.name}"), contentAlignment = Alignment.Center) {
                    Text(value.label, color = if (value == scale) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (value == scale) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionIcon(Icons.Rounded.ChevronLeft, "上一${scale.label}", { movePeriod(-1) },
                enabled = report?.firstDay?.let { period.start > ListeningPeriod(scale, it).start } == true)
            Column(Modifier.weight(1f).clickable { dates = true }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(period.label, style = MaterialTheme.typography.titleMedium)
                if (today >= period.start && today < period.end) Text("进行中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionIcon(Icons.Rounded.ChevronRight, "下一${scale.label}", { movePeriod(1) }, enabled = period.end <= today)
        }
        PullToRefreshBox(loading && visible != null, { refresh++ }, Modifier.weight(1f)) {
            LazyColumn(state = list, modifier = Modifier.fillMaxSize().testTag("listening-report"),
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (error != null) item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error!!); TextButton({ refresh++ }) { Text("重试") }
                    }
                }
                if (visible == null) item {
                    Surface(Modifier.fillMaxWidth().height(230.dp), shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer) {}
                } else {
                    item(key = "overview") { ListeningOverview(visible) }
                    if (visible.totalMillis == 0L) item {
                        Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            Text(if (visible.firstDay == null) "从下一首歌，开始积累" else "这段时间，还没有聆听记录", style = MaterialTheme.typography.titleLarge)
                            Text("以后在 AMI 听过的歌，会慢慢变成这里的日历、排行和音乐偏好。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        item(key = "trend") { ReportCard("01", "音乐的起伏", "${if (scale == ListeningScale.YEAR) "每个月" else "每一天"}，留给音乐的时间", visible, "trend") { progress ->
                            val bars = remember(visible) {
                                if (scale == ListeningScale.YEAR) visible.days.groupBy { it.date.monthValue }.map { (month, days) ->
                                    ListeningBar("${month}月", days.sumOf { it.millis }, days.any { it.recorded })
                                } else visible.days.map { ListeningBar(if (scale == ListeningScale.WEEK) listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it.date.dayOfWeek.value - 1] else "${it.date.dayOfMonth}日", it.millis, it.recorded) }
                            }
                            ListeningBars(bars, progress)
                        } }
                        item(key = "calendar") { ReportCard("02", "听过的日子", "颜色越深，音乐停留得越久", visible, "calendar") { ListeningCalendar(visible, it) } }
                        item(key = "songs") { ReportCard("03", "反复相伴的歌", "累计听得最久的十首", visible, "songs") { progress ->
                            visible.topSongs.forEachIndexed { index, row ->
                                ListeningSongRank(row, index, visible.totalMillis, visible.topSongs.first().millis, progress) {
                                    detail = row.title to "${row.subtitle}\n${listeningTimeLabel(row.millis)} · 本期 ${shareLabel(row.millis, visible.totalMillis)}"
                                }
                            }
                        } }
                        item(key = "artists") { ReportCard("04", "耳朵偏爱的声音", "歌手的聆听时长占比", visible, "artists") { progress ->
                            ArtistDistribution(visible, progress)
                        } }
                        item(key = "albums") { ReportCard("05", "常驻的唱片", "陪伴时间最长的六张专辑", visible, "albums") { progress ->
                            visible.albums.chunked(2).forEach { rows ->
                                Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    rows.forEach { row -> Column(Modifier.weight(1f).clickable {
                                        detail = row.title to "${row.subtitle}\n${listeningTimeLabel(row.millis)} · 本期 ${shareLabel(row.millis, visible.totalMillis)}"
                                    }) {
                                        Cover(row.cover, row.title, Modifier.fillMaxWidth().aspectRatio(1f))
                                        Text(row.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(listeningTimeLabel(row.millis), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        RankLine(row.millis, visible.totalMillis, progress)
                                    } }
                                    if (rows.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } }
                        item(key = "hours") { ReportCard("06", "音乐里的作息", "一周七天，二十四小时", visible, "hours") { ListeningHours(visible.hours, it) } }
                        item(key = "discovery") { ReportCard("07", "初遇，也重逢", "新遇到的声音，与熟悉的旋律", visible, "discovery") { progress ->
                            val portions = remember(visible) { listeningPercentages(listOf(visible.newMillis, visible.totalMillis - visible.newMillis)) }
                            ListeningShare(visible.newMillis, visible.totalMillis, progress)
                            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column { Text(percentLabel(portions[0]), style = MaterialTheme.typography.headlineMedium); Text("本期初遇", style = MaterialTheme.typography.bodySmall) }
                                Column(horizontalAlignment = Alignment.End) { Text(percentLabel(portions[1]), style = MaterialTheme.typography.headlineMedium); Text("以前听过", style = MaterialTheme.typography.bodySmall) }
                            }
                            ChartSelection("“初遇”指首次在 AMI 留下收听记录，并非歌曲发行时间。")
                        } }
                        item(key = "insight") { ListeningInsight(visible) }
                    }
                    item(key = "footnote") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (visible.incomplete) Text("部分聆听记录未能保存，图表可能不完整。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            visible.firstDay?.let { Text("从 ${it.year}.${it.monthValue}.${it.dayOfMonth} 开始记录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text("仅保存在此设备", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
    }
    if (dates) {
        val first = report?.firstDay ?: today
        val picker = rememberDatePickerState(initialSelectedDateMillis = period.anchor.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = minOf(first.year, today.year)..today.year, selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().let { it >= first && it <= today }
            })
        DatePickerDialog({ dates = false }, confirmButton = { TextButton({
            picker.selectedDateMillis?.let { anchor = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay() }; dates = false
        }, enabled = picker.selectedDateMillis != null) { Text("查看") } }, dismissButton = { TextButton({ dates = false }) { Text("取消") } }) {
            DatePicker(picker, title = { Text("选择日期", Modifier.padding(24.dp)) }, headline = null, showModeToggle = false)
        }
    }
    if (dialog == "info") AlertDialog({ dialog = null }, title = { Text("关于我的聆听") }, text = { Text(
        "只记录启用新版之后 AMI 内的实际播放时间。暂停、缓冲和拖动进度不计时，倍速播放十分钟仍记十分钟。\n\n在线与缓存播放合并；有可靠标识的本地歌曲也会归到同一首。合作曲优先归首位署名艺人，旧曲目信息不足时保留原署名。\n\n上期对比按已进入的日历天数计算，本期今日仍在累计；记录不足时不作对比。记录仅保存在本机，清缓存不会清除。异常退出可能遗漏最后约十秒。"
    ) }, confirmButton = { TextButton({ dialog = null }) { Text("知道了") } })
    if (dialog == "clear") AlertDialog({ dialog = null }, title = { Text("清除全部聆听记录？") },
        text = { Text("周报、月报和年报将从下一次播放重新积累。歌曲、账号、缓存与译文会保留。") },
        confirmButton = { TextButton({ dialog = null; scope.launch {
            try { store.clear(); anchor = today.toEpochDay(); refresh++ } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "聆听记录未能清除，请重试" }
        } }) { Text("清除记录", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton({ dialog = null }) { Text("取消") } })
    detail?.let { (title, body) -> AlertDialog({ detail = null }, title = { Text(title) }, text = { Text(body) }, confirmButton = { TextButton({ detail = null }) { Text("关闭") } }) }
}

private val SeenCharts = compositionLocalOf<MutableSet<String>> { mutableSetOf() }

@Composable private fun ListeningOverview(report: ListeningReport) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().testTag("listening-overview")) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${when (report.period.scale) { ListeningScale.WEEK -> "这一周"; ListeningScale.MONTH -> "这一月"; ListeningScale.YEAR -> "这一年" }}的音乐时光", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            val hours = report.totalMillis / 3_600_000
            val number = if (hours > 0) hours else if (report.totalMillis >= 60_000) report.totalMillis / 60_000 else report.totalMillis / 1000
            Row(verticalAlignment = Alignment.Bottom) {
                Text(number.toString(), Modifier.testTag("listening-duration"), fontSize = 58.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(if (hours > 0) "小时 ${report.totalMillis / 60_000 % 60} 分" else if (report.totalMillis >= 60_000 || report.totalMillis == 0L) "分钟" else "秒", Modifier.padding(start = 10.dp, bottom = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            report.previousMillis?.let { previous ->
                Text(if (previous == 0L) "上期同天数还未听歌" else "较上期同天数 ${if (report.totalMillis >= previous) "+" else ""}${((report.totalMillis.toDouble() / previous - 1) * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .12f))
            Row(Modifier.fillMaxWidth()) {
                listOf(report.songs to "听过的歌", report.activeDays to "听歌天数", report.longestStreak to "最长连续").forEach { (value, label) ->
                    Column(Modifier.weight(1f)) {
                        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .75f))
                    }
                }
            }
        }
    }
}

@Composable private fun ReportCard(number: String, title: String, caption: String, report: ListeningReport, id: String,
    content: @Composable ColumnScope.(() -> Float) -> Unit) {
    val seen = SeenCharts.current
    val key = "${report.period}:$id"
    val progress = remember(key) { Animatable(if (key in seen) 1f else 0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(key, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (seen.add(key)) progress.animateTo(1f, tween(360)) else progress.snapTo(1f)
        }
    }
    Surface(Modifier.fillMaxWidth().testTag("listening-$id"), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(20.dp)) {
            Text(number, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.titleLarge)
            Text(caption, Modifier.padding(top = 6.dp, bottom = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content { progress.value }
        }
    }
}

@Composable private fun ListeningSongRank(row: ListeningRank, index: Int, total: Long, maximum: Long, progress: () -> Float, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${index + 1}".padStart(2, '0'), Modifier.width(22.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Cover(row.cover, row.title, Modifier.size(44.dp))
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            RankLine(row.millis, maximum, progress)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(listeningTimeLabel(row.millis), style = MaterialTheme.typography.labelSmall)
            Text(shareLabel(row.millis, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun RankLine(value: Long, maximum: Long, progress: () -> Float) {
    val color = MaterialTheme.colorScheme.primary; val neutral = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(Modifier.fillMaxWidth().padding(top = 7.dp).height(3.dp)) {
        drawRect(neutral); drawRect(color, size = size.copy(width = size.width * value.toFloat() / maximum.coerceAtLeast(1) * progress()))
    }
}

@Composable private fun ArtistDistribution(report: ListeningReport, progress: () -> Float) {
    var selected by remember(report) { mutableIntStateOf(0) }
    val colors = listOf(MaterialTheme.colorScheme.primary, Color(0xFF60B6AF), Color(0xFFC99660), Color(0xFF9383CB), Color(0xFFD48593), MaterialTheme.colorScheme.onSurfaceVariant)
    val percentages = remember(report) { listeningPercentages(report.artists.map { it.millis }) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ListeningDonut(report.artists, selected, colors, progress) { selected = it }
        Column(Modifier.width(112.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percentLabel(percentages[selected]), style = MaterialTheme.typography.headlineMedium)
            Text(report.artists[selected].title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Spacer(Modifier.height(16.dp))
    report.artists.forEachIndexed { index, row ->
        Row(Modifier.fillMaxWidth().clickable { selected = index }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(9.dp)) { drawCircle(colors[index]) }
            Text(row.title, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(percentLabel(percentages[index]), style = MaterialTheme.typography.labelLarge)
        }
    }
    ChartSelection("${report.artists[selected].title} · ${listeningTimeLabel(report.artists[selected].millis)}")
}

@Composable private fun ListeningInsight(report: ListeningReport) {
    val night = report.hours.withIndex().filter { it.index % 24 >= 22 || it.index % 24 < 6 }.sumOf { it.value }
    val lines = buildList {
        val top = report.artists.firstOrNull()
        if (top != null && top.millis >= report.totalMillis / 2) add("${shareLabel(top.millis, report.totalMillis)} 的音乐时间，留给了 ${top.title}。")
        if (report.longestStreak >= 3) add("最长连续 ${report.longestStreak} 天，都有音乐相伴。")
        if (night >= report.totalMillis / 4) add("${shareLabel(night, report.totalMillis)} 的聆听，发生在夜晚十点至清晨六点。")
        if (isEmpty()) report.days.maxByOrNull { it.millis }?.let { add("${it.date.monthValue} 月 ${it.date.dayOfMonth} 日，是音乐停留最久的一天。") }
    }.take(2)
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
        lines.forEach { Text(it, style = MaterialTheme.typography.titleMedium) }
    }
}

private fun shareLabel(value: Long, total: Long) = percentLabel((value.toDouble() / total.coerceAtLeast(1) * 1000).roundToInt())
private fun percentLabel(tenths: Int) = if (tenths % 10 == 0) "${tenths / 10}%" else "${tenths / 10}.${tenths % 10}%"
