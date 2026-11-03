package com.pause.feature.statistics

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pause.core.*
import com.pause.domain.InterventionOutcome
import com.pause.domain.InterventionRecord
import com.pause.domain.RecordKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun StatisticsScreen(dependencies: AppDependencies, onBack: () -> Unit) {
    val records by dependencies.history.records.collectAsStateWithLifecycle(emptyList())
    val historyError by dependencies.engine.historyError.collectAsStateWithLifecycle()
    var period by rememberSaveable { mutableIntStateOf(7) }
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val start = today.minusDays(period - 1L)
    val rangeLabel = if (period == 1) today.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        else "${start.format(DateTimeFormatter.ofPattern("MMM d"))} – ${today.format(DateTimeFormatter.ofPattern("MMM d"))}"
    val selected = records.filter { it.date(zone) in start..today }
    val usage = selected.filter { it.kind == RecordKind.USAGE }
    val legacy = selected.filter { it.kind == RecordKind.LEGACY_WAIT }
    val started = usage.count { it.usageStartedAtMillis != null }
    val usedMillis = usage.sumOf { it.usedMillis }
    val expired = usage.count { it.outcome == InterventionOutcome.EXPIRED }
    val abandoned = usage.count { it.outcome == InterventionOutcome.CANCELLED || it.outcome == InterventionOutcome.LEFT_APP }
    val interrupted = usage.count { it.outcome == InterventionOutcome.INTERRUPTED }
    val failures = usage.count { it.outcome == InterventionOutcome.LAUNCH_FAILED }
    val mostUsed = usage.groupBy { it.packageName }.values.sortedByDescending { it.size }.take(5)
    val recent = usage.sortedByDescending { it.startedAtMillis }.take(8)
    val bins = chartBins(period, start, today, usage, zone)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PauseDimensions.PagePadding, end = PauseDimensions.PagePadding,
            top = 20.dp, bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(PauseDimensions.SectionGap),
    ) {
        item {
            PauseBrand(compact = true)
            Spacer(Modifier.height(20.dp))
            PausePageHeader("Insights", "Small pauses, seen over time.")
            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Today", 7 to "7 days", 30 to "30 days").forEach { (days, label) ->
                    FilterChip(selected = period == days, onClick = { period = days }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(rangeLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (historyError != null) item {
            Text("Some history couldn't be saved. These statistics may be incomplete. Retry saving from Today.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard("App time", formatUsageTime(usedMillis), Modifier.weight(1f), emphasized = true)
                    SummaryCard("Sessions", started.toString(), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard("Time expired", expired.toString(), Modifier.weight(1f))
                    SummaryCard("Stepped away", abandoned.toString(), Modifier.weight(1f))
                }
                Text("Only observed time inside protected apps. Stepped away includes leaving early or cancelling.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (interrupted > 0 || failures > 0) {
                    Text("$interrupted interrupted · $failures launch ${if (failures == 1) "issue" else "issues"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(shape = PauseDimensions.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PauseSectionLabel("Interventions")
                    Text(when (period) {
                        1 -> "$rangeLabel · 4-hour groups"
                        7 -> "$rangeLabel · each day"
                        else -> "$rangeLabel · 6-day groups"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    UsageChart(bins)
                }
            }
        }
        if (usage.isEmpty()) {
            item {
                Card(shape = PauseDimensions.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("A fresh start", style = MaterialTheme.typography.titleLarge)
                        Text("No app-use interventions in this period. Open a protected app to begin. Preview sessions don't count.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            if (mostUsed.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PauseSectionLabel("Most paused")
                    Card(shape = PauseDimensions.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(horizontal = PauseDimensions.CardPadding, vertical = 6.dp)) {
                            mostUsed.forEachIndexed { index, group ->
                                val latest = group.maxBy { it.startedAtMillis }
                                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    ApplicationIcon(latest.packageName, latest.appName)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(latest.appName, style = MaterialTheme.typography.titleMedium)
                                        Text(formatUsageTime(group.sumOf { it.usedMillis }) + " of app time",
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(group.size.toString(), style = MaterialTheme.typography.titleLarge,
                                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                        Text(if (group.size == 1) "pause" else "pauses", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (index < mostUsed.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PauseSectionLabel("Recent sessions")
                    Card(shape = PauseDimensions.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(horizontal = PauseDimensions.CardPadding, vertical = 6.dp)) {
                            recent.forEachIndexed { index, record ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(record.appName, style = MaterialTheme.typography.titleMedium)
                                        Text(Instant.ofEpochMilli(record.startedAtMillis).atZone(zone)
                                            .format(DateTimeFormatter.ofPattern("MMM d · h:mm a")),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(record.outcomeLabel(), style = MaterialTheme.typography.labelMedium,
                                            color = if (record.outcome == InterventionOutcome.EXPIRED) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(formatUsageTime(record.usedMillis), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                if (index < recent.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
        if (legacy.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PauseSectionLabel("Earlier history")
                Card(shape = PauseDimensions.CardShape,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Previous wait mode", style = MaterialTheme.typography.titleMedium)
                        Text("${legacy.size} attempts · ${legacy.count { it.outcome == InterventionOutcome.CONTINUED }} continued · ${legacy.count { it.outcome == InterventionOutcome.CANCELLED }} stepped away",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Preserved from the earlier version. Waiting time is excluded from the app-time figures above.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text("Sessions and interventions are grouped by the date they began. Background time and earlier waiting durations are excluded.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    Card(modifier, shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = if (emphasized) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).padding(PauseDimensions.CardPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium.copy(
                fontSize = if (value.length > 7) 25.sp else 30.sp, fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class ChartBin(val label: String, val description: String, val count: Int)

private fun chartBins(period: Int, start: LocalDate, today: LocalDate, records: List<InterventionRecord>, zone: ZoneId): List<ChartBin> =
    when (period) {
        1 -> (0 until 24 step 4).map { hour ->
            ChartBin("%02d".format(hour), "Today, $hour:00 to ${hour + 4}:00",
                records.count { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).hour in hour until hour + 4 })
        }
        7 -> (0L..6L).map { offset ->
            val date = start.plusDays(offset)
            ChartBin(date.format(DateTimeFormatter.ofPattern("EEE")).take(2),
                date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), records.count { it.date(zone) == date })
        }
        else -> (0L..4L).map { group ->
            val first = start.plusDays(group * 6)
            val last = minOf(first.plusDays(5), today)
            ChartBin(first.format(DateTimeFormatter.ofPattern("M/d")),
                "${first.format(DateTimeFormatter.ofPattern("MMM d"))} to ${last.format(DateTimeFormatter.ofPattern("MMM d"))}",
                records.count { it.date(zone) in first..last })
        }
    }

@Composable
private fun UsageChart(bins: List<ChartBin>) {
    val maximum = bins.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        bins.forEach { bin ->
            Column(Modifier.weight(1f).semantics { contentDescription = "${bin.description}: ${bin.count} interventions" },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(bin.count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.widthIn(max = 26.dp).fillMaxWidth()
                        .height(if (bin.count == 0) 2.dp else (100f * bin.count / maximum).dp)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (bin.count == 0) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary))
                }
                Text(bin.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ApplicationIcon(packageName: String, appName: String) {
    AndroidView(
        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            view.contentDescription = appName
            view.setImageDrawable(runCatching { view.context.packageManager.getApplicationIcon(packageName) }.getOrNull())
        },
    )
}

private fun InterventionRecord.date(zone: ZoneId): LocalDate = Instant.ofEpochMilli(startedAtMillis).atZone(zone).toLocalDate()

private fun InterventionRecord.outcomeLabel(): String = when (outcome) {
    InterventionOutcome.CONTINUED -> "Continued"
    InterventionOutcome.CANCELLED -> "Cancelled"
    InterventionOutcome.INTERRUPTED -> "Interrupted"
    InterventionOutcome.LAUNCH_FAILED -> "Launch issue"
    InterventionOutcome.EXPIRED -> "Time expired"
    InterventionOutcome.LEFT_APP -> "Left app"
    null -> if (usageStartedAtMillis != null) "In app" else "Choosing time"
}

private fun formatUsageTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    }
}

