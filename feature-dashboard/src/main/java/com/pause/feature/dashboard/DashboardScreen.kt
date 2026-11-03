package com.pause.feature.dashboard

import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.pause.domain.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    dependencies: AppDependencies,
    protectionReady: Boolean,
    onManageApps: () -> Unit,
    onConfigureApp: (String) -> Unit,
    onSetup: () -> Unit,
) {
    val protected by dependencies.protections.apps.collectAsStateWithLifecycle(emptyList())
    val preferences by dependencies.preferences.preferences.collectAsStateWithLifecycle(UserPreferences())
    val records by dependencies.history.records.collectAsStateWithLifecycle(emptyList())
    val historyError by dependencies.engine.historyError.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(dependencies) {
        runCatching { dependencies.catalog.listApps() }.onSuccess { installed = it }
    }
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val todayRecords = records.filter {
        it.kind == RecordKind.USAGE && Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate() == today
    }
    val activeCount = protected.count { it.enabled }
    val active = protectionReady && preferences.protectionEnabled && activeCount > 0
    val started = todayRecords.count { it.usageStartedAtMillis != null }
    val usedMillis = todayRecords.sumOf { it.usedMillis }
    val expired = todayRecords.count { it.outcome == InterventionOutcome.EXPIRED }
    val steppedAway = todayRecords.count { it.outcome == InterventionOutcome.CANCELLED || it.outcome == InterventionOutcome.LEFT_APP }
    val statusTitle = when {
        !protectionReady -> "Finish setting up"
        !preferences.protectionEnabled -> "Protection is paused"
        activeCount == 0 -> "Choose your apps"
        else -> "Protection is on"
    }
    val statusDescription = when {
        !protectionReady -> "Accessibility access is needed to protect your apps."
        !preferences.protectionEnabled -> "Your app choices and settings are saved."
        activeCount == 0 -> "Add the apps you'd like to use more intentionally."
        else -> "$activeCount ${if (activeCount == 1) "app" else "apps"} protected · up to 5 minutes per session"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PauseDimensions.PagePadding, end = PauseDimensions.PagePadding,
            top = 20.dp, bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            PauseBrand(compact = true)
            Spacer(Modifier.height(20.dp))
            PausePageHeader("Today", today.format(DateTimeFormatter.ofPattern("EEEE, MMM d")))
        }
        item {
            Card(
                shape = PauseDimensions.CardShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                ),
            ) {
                Column(Modifier.padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                            Icon(PauseIcons.Shield, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Box(Modifier.size(6.dp).background(
                                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ))
                                Text(statusTitle, style = MaterialTheme.typography.titleMedium)
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(statusDescription, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = preferences.protectionEnabled,
                            modifier = Modifier.semantics { contentDescription = "Enable protection" },
                            onCheckedChange = { enabled -> scope.launch {
                                runCatching { dependencies.preferences.update { it.copy(protectionEnabled = enabled) } }
                                    .onFailure { error = "Couldn't update protection. Please try again." }
                            } },
                        )
                    }
                    if (!protectionReady || activeCount == 0) {
                        Button(
                            onClick = if (!protectionReady) onSetup else onManageApps,
                            modifier = Modifier.fillMaxWidth().height(PauseDimensions.ControlHeight),
                            shape = PauseDimensions.ControlShape,
                        ) { Text(if (!protectionReady) "Finish setup" else "Choose apps") }
                    }
                }
            }
        }
        if (historyError != null) item {
            Card(shape = PauseDimensions.CardShape) {
                Column(Modifier.padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Some history couldn't be saved", style = MaterialTheme.typography.titleMedium)
                    Text("Your daily overview may be incomplete until local storage is available.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { scope.launch {
                        runCatching { dependencies.engine.flushHistory() }
                            .onFailure { error = "History still couldn't be saved. Check available device storage." }
                    } }, contentPadding = PaddingValues(0.dp)) { Text("Retry saving history") }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PauseSectionLabel("Today so far")
                Card(shape = PauseDimensions.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            DailyMetric("App time", formatUsageTime(usedMillis), Modifier.weight(1.3f))
                            DailyMetric("Sessions", started.toString(), Modifier.weight(1f))
                            DailyMetric("Expired", expired.toString(), Modifier.weight(1f))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Text(if (todayRecords.isEmpty()) "Your first intentional moment starts with a protected app."
                            else "$steppedAway ${if (steppedAway == 1) "time" else "times"} you stepped away.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PauseSectionLabel("Protected apps")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onManageApps, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Manage") }
            }
        }
        if (protected.isEmpty()) item {
            Card(shape = PauseDimensions.CardShape, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.fillMaxWidth().padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("A little space, where you need it.", style = MaterialTheme.typography.titleMedium)
                    Text("Select as many apps as you like. Give each its own breathing rhythm and app-time settings.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onManageApps, modifier = Modifier.fillMaxWidth().height(PauseDimensions.ControlHeight),
                        shape = PauseDimensions.ControlShape) { Text("Add your first app") }
                }
            }
        }
        items(protected, key = { it.packageName }) { app ->
            val name = installed.firstOrNull { it.packageName == app.packageName }?.name ?: app.packageName
            val config = (app.config ?: preferences.defaults).normalized()
            Card(
                onClick = { onConfigureApp(app.packageName) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ApplicationIcon(app.packageName, name)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(if (app.enabled) "${config.breathingSeconds}s breath · up to ${durationLabel(config.maxUsageSeconds)}" else "Protection paused",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = app.enabled, modifier = Modifier.semantics { contentDescription = "Protect $name" },
                        onCheckedChange = { enabled -> scope.launch {
                            runCatching { dependencies.protections.upsert(app.copy(enabled = enabled)) }
                                .onFailure { error = "Couldn't update $name. Please try again." }
                        } })
                    Icon(PauseIcons.ChevronRight, "Customize $name", Modifier.padding(start = 6.dp).size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        error?.let { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        } }
    }
}

@Composable
private fun DailyMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(value, style = MaterialTheme.typography.headlineMedium.copy(
            fontSize = if (value.length > 6) 22.sp else 28.sp, fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ApplicationIcon(packageName: String, appName: String) {
    AndroidView(
        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)),
        factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
        update = { view ->
            view.contentDescription = appName
            view.setImageDrawable(runCatching { view.context.packageManager.getApplicationIcon(packageName) }.getOrNull())
        },
    )
}

private fun formatUsageTime(millis: Long): String {
    val seconds = millis.coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3_600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3_600}h ${seconds % 3_600 / 60}m"
    }
}
private fun durationLabel(seconds: Int): String = if (seconds % 60 == 0) "${seconds / 60}m" else "${seconds}s"

