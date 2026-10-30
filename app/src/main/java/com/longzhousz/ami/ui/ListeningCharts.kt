package com.longzhousz.ami.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.longzhousz.ami.core.*
import java.time.YearMonth
import kotlin.math.atan2

internal data class ListeningBar(val label: String, val millis: Long, val recorded: Boolean = true)

@Composable internal fun ListeningBars(bars: List<ListeningBar>, progress: () -> Float) {
    var selected by remember(bars) { mutableIntStateOf(bars.indexOfLast { it.millis > 0 }.coerceAtLeast(0)) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    val max = bars.maxOfOrNull { it.millis }?.coerceAtLeast(1) ?: 1
    Canvas(Modifier.fillMaxWidth().height(136.dp).semantics {
        contentDescription = "听歌时长趋势"
        customActions = bars.mapIndexed { i, bar -> CustomAccessibilityAction("${bar.label}，${listeningTimeLabel(bar.millis)}") { selected = i; true } }
    }.pointerInput(bars) { detectTapGestures { selected = (it.x / size.width * bars.size).toInt().coerceIn(0, bars.lastIndex) } }) {
        repeat(3) { n -> val y = size.height * n / 2f; drawLine(track.copy(alpha = .5f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
        val step = size.width / bars.size
        val width = (step * .58f).coerceAtMost(28.dp.toPx())
        bars.forEachIndexed { i, bar ->
            val height = (size.height * bar.millis.toFloat() / max * progress()).coerceAtLeast(if (bar.recorded) 2.dp.toPx() else 0f)
            if (height > 0) drawRoundRect(if (i == selected) primary else primary.copy(alpha = .48f),
                Offset(step * (i + .5f) - width / 2, size.height - height), Size(width, height), CornerRadius(3.dp.toPx()))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        for (index in listOf(0, bars.lastIndex / 2, bars.lastIndex).distinct()) Text(bars[index].label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ChartSelection("${bars[selected].label} · ${if (bars[selected].recorded) listeningTimeLabel(bars[selected].millis) else "尚未记录"}")
}

@Composable internal fun ListeningCalendar(report: ListeningReport, progress: () -> Float) {
    var selected by remember(report) { mutableStateOf(report.days.lastOrNull { it.millis > 0 } ?: report.days.first()) }
    val maximum = report.days.maxOf { it.millis }.coerceAtLeast(1)
    val months = remember(report) { report.days.groupBy { YearMonth.from(it.date) }.values.toList() }
    if (report.period.scale == ListeningScale.YEAR) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            months.chunked(3).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { days -> Column(Modifier.weight(1f)) {
                    Text("${days.first().date.monthValue} 月", style = MaterialTheme.typography.labelMedium)
                    CalendarMonth(days, maximum, true, progress) { selected = it }
                } }
            } }
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // A week crossing a month is still one continuous row.
        CalendarMonth(report.days, maximum, false, progress, week = report.period.scale == ListeningScale.WEEK) { selected = it }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
        Text("少  ", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val color = MaterialTheme.colorScheme.primary
        for (level in 1..4) Canvas(Modifier.padding(horizontal = 2.dp).size(12.dp)) { drawRoundRect(color.copy(alpha = level / 4f), cornerRadius = CornerRadius(2.dp.toPx())) }
        Text("  多", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ChartSelection("${selected.date.monthValue} 月 ${selected.date.dayOfMonth} 日 · ${if (selected.recorded) listeningTimeLabel(selected.millis) else "尚未记录"}")
}

@Composable private fun CalendarMonth(days: List<ListeningDay>, maximum: Long, mini: Boolean, progress: () -> Float,
    week: Boolean = false, onSelect: (ListeningDay) -> Unit) {
    val offset = if (week) 0 else days.first().date.dayOfWeek.value - 1
    val rows = if (week) 1 else (offset + days.size + 6) / 7
    val primary = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.surfaceContainerHigh
    val outline = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(if (mini) 74.dp else (rows * 35).dp).padding(top = 6.dp).semantics {
        contentDescription = "${days.first().date.monthValue} 月听歌日历"
        customActions = days.map { day -> CustomAccessibilityAction("${day.date}，${if (day.recorded) listeningTimeLabel(day.millis) else "尚未记录"}") { onSelect(day); true } }
    }.pointerInput(days) {
        detectTapGestures { point ->
            val col = (point.x / size.width * 7).toInt().coerceIn(0, 6)
            val row = (point.y / size.height * rows).toInt().coerceIn(0, rows - 1)
            days.getOrNull(row * 7 + col - offset)?.let(onSelect)
        }
    }) {
        val width = size.width / 7; val height = size.height / rows
        days.forEachIndexed { i, day ->
            val index = offset + i
            val position = Offset(index % 7 * width + 2.dp.toPx(), index / 7 * height + 2.dp.toPx())
            val cell = Size((width - 4.dp.toPx()).coerceAtLeast(1f), (height - 4.dp.toPx()).coerceAtLeast(1f))
            val color = if (day.millis > 0) primary.copy(alpha = (.2f + .8f * day.millis.toFloat() / maximum) * progress()) else neutral
            if (day.recorded) drawRoundRect(color, position, cell, CornerRadius(2.dp.toPx()))
            else drawRoundRect(outline.copy(alpha = .6f), position, cell, CornerRadius(2.dp.toPx()), style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable internal fun ListeningHours(values: List<Long>, progress: () -> Float) {
    var selected by remember(values) { mutableIntStateOf(values.indices.maxByOrNull { values[it] } ?: 0) }
    val primary = MaterialTheme.colorScheme.primary
    val neutral = MaterialTheme.colorScheme.surfaceContainerHigh
    val maximum = values.maxOrNull()?.coerceAtLeast(1) ?: 1
    val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    Row(Modifier.fillMaxWidth()) {
        Column(Modifier.width(29.dp).height(126.dp), verticalArrangement = Arrangement.SpaceAround) {
            weekdays.forEach { Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Canvas(Modifier.weight(1f).height(126.dp).semantics {
            contentDescription = "星期几与小时的听歌时长分布"
            customActions = values.mapIndexedNotNull { i, ms -> if (ms > 0) CustomAccessibilityAction("${weekdays[i / 24]} ${i % 24} 点，${listeningTimeLabel(ms)}") { selected = i; true } else null }
        }.pointerInput(values) {
            detectTapGestures { selected = (it.y / size.height * 7).toInt().coerceIn(0, 6) * 24 + (it.x / size.width * 24).toInt().coerceIn(0, 23) }
        }) {
            val width = size.width / 24; val height = size.height / 7
            values.forEachIndexed { i, ms ->
                val color = if (ms == 0L) neutral else primary.copy(alpha = (.18f + .82f * ms.toFloat() / maximum) * progress())
                drawRoundRect(color, Offset(i % 24 * width + 1.dp.toPx(), i / 24 * height + 1.dp.toPx()),
                    Size((width - 2.dp.toPx()).coerceAtLeast(1f), height - 2.dp.toPx()), CornerRadius(1.5.dp.toPx()))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(start = 29.dp, top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("0 时", "6", "12", "18", "24").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    ChartSelection("${weekdays[selected / 24]} ${selected % 24}—${selected % 24 + 1} 点 · ${listeningTimeLabel(values[selected])}")
}

@Composable internal fun ListeningDonut(rows: List<ListeningRank>, selected: Int, colors: List<Color>, progress: () -> Float, onSelect: (Int) -> Unit) {
    val total = rows.sumOf { it.millis }.coerceAtLeast(1)
    Canvas(Modifier.size(190.dp).semantics { contentDescription = "歌手听歌占比环形图；下方图例可选择歌手" }
        .pointerInput(rows) { detectTapGestures { point ->
            val x = point.x - size.width / 2; val y = point.y - size.height / 2
            val radius = kotlin.math.sqrt(x * x + y * y)
            if (radius < size.width * .28f || radius > size.width / 2) return@detectTapGestures
            val angle = ((atan2(y, x) * 180 / Math.PI + 450) % 360).toFloat()
            var end = 0f
            for (i in rows.indices) { end += rows[i].millis.toFloat() / total * 360f; if (angle <= end) { onSelect(i); break } }
        } }) {
        val stroke = 25.dp.toPx(); val inset = stroke / 2 + 3.dp.toPx()
        var start = -90f
        rows.forEachIndexed { index, row ->
            val sweep = row.millis.toFloat() / total * 360f
            val visible = (360f * progress() - (start + 90)).coerceIn(0f, sweep)
            if (visible > 1f) drawArc(colors[index].copy(alpha = if (index == selected) 1f else .6f), start + 1f,
                (visible - 2f).coerceAtLeast(.1f), false, Offset(inset, inset), Size(size.width - inset * 2, size.height - inset * 2), style = Stroke(stroke))
            start += sweep
        }
    }
}

@Composable internal fun ListeningShare(newMillis: Long, totalMillis: Long, progress: () -> Float) {
    val primary = MaterialTheme.colorScheme.primary
    val old = Color(0xFF9383CB)
    Canvas(Modifier.fillMaxWidth().height(26.dp).semantics { contentDescription = "初遇与重逢的听歌时长比例" }) {
        val first = size.width * newMillis.toFloat() / totalMillis.coerceAtLeast(1)
        drawRect(old.copy(alpha = .6f * progress()), size = size)
        drawRect(primary.copy(alpha = progress()), size = Size(first, size.height))
    }
}

@Composable internal fun ChartSelection(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(top = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
