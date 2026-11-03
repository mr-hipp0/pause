package com.pause.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pause.core.AppDependencies
import com.pause.core.PausePageHeader
import com.pause.core.PauseSectionLabel
import com.pause.core.PauseDimensions
import com.pause.core.PauseBrand
import com.pause.core.PauseIcons
import com.pause.domain.AppCategory
import com.pause.domain.BreathingAnimation
import com.pause.domain.InterventionConfig
import com.pause.domain.InterventionOutcome
import com.pause.domain.ProtectedApp
import com.pause.domain.ThemeMode
import com.pause.domain.UserPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    dependencies: AppDependencies,
    packageName: String? = null,
    onBack: () -> Unit,
    onSetup: () -> Unit,
    onPreview: () -> Unit = {},
) {
    val preferences by dependencies.preferences.preferences.collectAsStateWithLifecycle(UserPreferences())
    val apps by dependencies.protections.apps.collectAsStateWithLifecycle(emptyList())
    val protectedApp = apps.firstOrNull { it.packageName == packageName }
    val effective = (if (packageName == null) preferences.defaults else protectedApp?.config ?: preferences.defaults).normalized()
    var draft by rememberSaveable(packageName, stateSaver = ConfigSaver) { mutableStateOf(effective) }
    var baseline by rememberSaveable(packageName, stateSaver = ConfigSaver) { mutableStateOf(effective) }
    var appName by remember(packageName) { mutableStateOf(packageName ?: "Settings") }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var resetDialog by remember { mutableStateOf<String?>(null) }
    var advancedBreathing by rememberSaveable(packageName) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        if (message == text) message = null
    }
    LaunchedEffect(effective, packageName) {
        // External preference changes may arrive while an unsaved draft is being edited.
        if (draft == baseline) draft = effective
        baseline = effective
    }
    LaunchedEffect(packageName) {
        if (packageName != null) {
            runCatching { dependencies.catalog.listApps().firstOrNull { it.packageName == packageName }?.name }
                .onSuccess { appName = it ?: packageName }
        }
    }
    val dirty = draft.normalized() != effective
    fun save(afterSave: () -> Unit = {}) {
        val configToSave = draft.normalized()
        saving = true
        scope.launch {
            runCatching {
                if (packageName == null) dependencies.preferences.update { it.copy(defaults = configToSave) }
                else {
                    val latest = dependencies.protections.apps.first().firstOrNull { it.packageName == packageName }
                        ?: error("The app is no longer protected")
                    dependencies.protections.upsert(latest.copy(config = configToSave))
                }
            }.onSuccess { message = "Settings saved."; afterSave() }
                .onFailure { message = "Couldn't save settings. Please try again." }
            saving = false
        }
    }
    fun updateApp(transform: (ProtectedApp) -> ProtectedApp, afterSave: () -> Unit = {}) {
        if (saving || packageName == null) return
        saving = true
        scope.launch {
            runCatching {
                val latest = dependencies.protections.apps.first().firstOrNull { it.packageName == packageName }
                    ?: error("The app is no longer protected")
                dependencies.protections.upsert(transform(latest))
            }.onSuccess { afterSave() }
                .onFailure { message = "Couldn't update this app. Please try again." }
            saving = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = PauseDimensions.PagePadding,
                end = PauseDimensions.PagePadding,
                top = 20.dp,
                bottom = if (dirty) 160.dp else 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(PauseDimensions.SectionGap),
        ) {
            stickyHeader {
                Surface(color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().heightIn(min = PauseDimensions.ControlHeight), verticalAlignment = Alignment.CenterVertically) {
                    if (packageName != null) {
                        IconButton(onClick = onBack) { Icon(PauseIcons.Back, "Back") }
                    }
                    PauseBrand(compact = true)
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { if (dirty) save(onPreview) else onPreview() },
                        enabled = !saving && (packageName == null || protectedApp != null),
                        modifier = Modifier.semantics {
                            contentDescription = "Preview intervention"
                            stateDescription = if (dirty) "Saves changes before previewing" else "Uses saved settings"
                        },
                    ) { Text("Preview") }
                }
                }
            }
            item {
                PausePageHeader(
                    title = if (packageName == null) "Make Pause yours" else appName,
                    subtitle = if (packageName == null) "A rhythm that feels like you."
                        else if (protectedApp?.config == null) "Following your global rhythm. Customize it below."
                        else "A little space, set just for this app.",
                )
            }
            if (packageName != null) {
                item {
                    PauseSectionLabel("This app")
                    Spacer(Modifier.height(12.dp))
                    SettingsCard {
                        if (protectedApp == null) {
                            Text("This app is no longer selected for protection.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            ToggleRow("Protect this app", "Pause before opening $appName.", protectedApp.enabled, enabled = !saving) { enabled ->
                                updateApp({ it.copy(enabled = enabled) })
                            }
                            Text(
                                if (protectedApp.config == null) "Using global defaults" else "Individual settings",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            if (protectedApp.config != null) {
                                TextButton(onClick = {
                                    updateApp({ it.copy(config = null) }) {
                                        draft = preferences.defaults.normalized()
                                        message = "Using global defaults."
                                    }
                                }, enabled = !saving, contentPadding = PaddingValues(0.dp)) { Text("Use global defaults") }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Text("Category", style = MaterialTheme.typography.titleMedium)
                            Text("Choose where this app belongs. This saves automatically.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = protectedApp.categoryOverride == null, enabled = !saving,
                                    onClick = { updateApp({ it.copy(categoryOverride = null) }) { message = "Using Android's app category." } },
                                    label = { Text("Automatic") })
                                AppCategory.entries.forEach { category ->
                                    FilterChip(selected = protectedApp.categoryOverride == category, enabled = !saving,
                                        onClick = { updateApp({ it.copy(categoryOverride = category) }) { message = "Category saved." } },
                                        label = { Text(category.displayName()) })
                                }
                            }
                        }
                    }
                }
            }
            item {
                PauseSectionLabel("Breathing")
                Spacer(Modifier.height(12.dp))
                SettingsCard {
                    Text("First, find your breath", style = MaterialTheme.typography.titleLarge)
                    Text("A small pause before every visit.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondsControl("Breathing duration", draft.breathingSeconds, 3..120) {
                        draft = draft.copy(breathingSeconds = it)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(3, 5, 10, 15, 30).forEach { seconds ->
                            FilterChip(selected = draft.breathingSeconds == seconds, enabled = !saving,
                                onClick = { draft = draft.copy(breathingSeconds = seconds) }, label = { Text("${seconds}s") })
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow("Show quotes while breathing", "A thoughtful line to help you return to the moment.", draft.showQuotes, enabled = !saving) {
                        draft = draft.copy(showQuotes = it)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = PauseDimensions.ControlHeight)
                            .semantics { stateDescription = if (advancedBreathing) "Expanded" else "Collapsed" }
                            .clickable(role = Role.Button) { advancedBreathing = !advancedBreathing },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Breathing rhythm", style = MaterialTheme.typography.titleMedium)
                            Text("Inhale, hold, and exhale timing", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (advancedBreathing) "Hide" else "Adjust", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Icon(PauseIcons.ChevronDown, null,
                            Modifier.padding(start = 8.dp).size(18.dp).rotate(if (advancedBreathing) 180f else 0f),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    AnimatedVisibility(visible = advancedBreathing) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SecondsControl("Breathe in", draft.inhaleSeconds, 1..30) { draft = draft.copy(inhaleSeconds = it) }
                            SecondsControl("Hold", draft.holdSeconds, 0..30) { draft = draft.copy(holdSeconds = it) }
                            SecondsControl("Breathe out", draft.exhaleSeconds, 1..30) { draft = draft.copy(exhaleSeconds = it) }
                            Text("Short exercises gently scale these timings to fit one complete breath.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                PauseSectionLabel("App time")
                Spacer(Modifier.height(12.dp))
                SettingsCard {
                    Text("Make room for intention", style = MaterialTheme.typography.titleLarge)
                    Text("Choose your allowance after each breath. Up to 5 minutes, every time.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SecondsControl("Starting position", draft.startUsageSeconds, draft.minUsageSeconds..draft.maxUsageSeconds) {
                        draft = draft.copy(startUsageSeconds = it).normalized()
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SecondsControl("Minimum app time", draft.minUsageSeconds, 1..draft.maxUsageSeconds) {
                        draft = draft.copy(minUsageSeconds = it).normalized()
                    }
                    SecondsControl("Maximum app time", draft.maxUsageSeconds, 1..300) {
                        draft = draft.copy(maxUsageSeconds = it).normalized()
                    }
                    Text("Only time in the app counts. Leaving ends your allowance; returning starts a fresh breath.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                PauseSectionLabel("Appearance")
                Spacer(Modifier.height(12.dp))
                SettingsCard {
                    Text("Keep it calm", style = MaterialTheme.typography.titleLarge)
                    Text("Breathing animation", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BreathingAnimation.entries.forEach { animation ->
                            FilterChip(selected = draft.animation == animation, enabled = !saving,
                                onClick = { draft = draft.copy(animation = animation) },
                                label = { Text(if (animation == BreathingAnimation.CIRCLE) "Soft circle" else "Ripples") })
                        }
                    }
                    if (packageName == null) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemeMode.entries.forEach { theme ->
                                FilterChip(selected = preferences.theme == theme, enabled = !saving, onClick = { scope.launch {
                                    runCatching { dependencies.preferences.update { it.copy(theme = theme) } }
                                        .onFailure { message = "Couldn't change the theme." }
                                } }, label = { Text(when (theme) { ThemeMode.SYSTEM -> "System"; ThemeMode.DARK -> "Dark"; ThemeMode.LIGHT -> "Light" }) })
                            }
                        }
                        Text("Theme changes save automatically.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                PauseSectionLabel("Feedback")
                Spacer(Modifier.height(12.dp))
                SettingsCard {
                    ToggleRow("Haptic feedback", "A gentle cue as your breathing changes.", draft.haptics, enabled = !saving) {
                        draft = draft.copy(haptics = it)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ToggleRow("Sound", "A soft tone for each breathing phase.", draft.sound, enabled = !saving) {
                        draft = draft.copy(sound = it)
                    }
                }
            }
            if (packageName == null) {
                item {
                    PauseSectionLabel("Access & privacy")
                    Spacer(Modifier.height(12.dp))
                    SettingsCard {
                        Text("Your attention is yours", style = MaterialTheme.typography.titleLarge)
                        Text("Protected apps, preferences, and history stay on this device. No account. No usage-data uploads.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Accessibility identifies the foreground app and presents the interventions you choose. Android controls permission status.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onSetup, enabled = !saving,
                            modifier = Modifier.fillMaxWidth().height(PauseDimensions.ControlHeight),
                            shape = RoundedCornerShape(18.dp)) { Text("Review permissions") }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        TextButton(onClick = { resetDialog = "history" }, enabled = !saving,
                            contentPadding = PaddingValues(0.dp)) { Text("Delete intervention history") }
                        TextButton(onClick = { resetDialog = "all" }, enabled = !saving,
                            contentPadding = PaddingValues(0.dp)) {
                            Text("Reset all app data", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        AnimatedVisibility(visible = dirty, modifier = Modifier.align(Alignment.BottomCenter)) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp, shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = PauseDimensions.PagePadding, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (packageName == null) "Your global rhythm has unsaved changes" else "This app has unsaved changes",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { save() }, enabled = !saving && (packageName == null || protectedApp != null),
                        modifier = Modifier.fillMaxWidth().height(PauseDimensions.ControlHeight), shape = RoundedCornerShape(18.dp)) {
                        Text(if (saving) "Saving…" else "Save changes")
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(
            start = PauseDimensions.PagePadding, end = PauseDimensions.PagePadding,
            bottom = if (dirty) 128.dp else 16.dp,
        ))
    }
    resetDialog?.let { reset ->
        AlertDialog(
            onDismissRequest = { resetDialog = null },
            title = { Text(if (reset == "all") "Reset Pause?" else "Delete your history?") },
            text = { Text(if (reset == "all") "This removes protected apps, preferences, and intervention history from this device. Android accessibility permission is managed separately in system settings."
                else "All recorded interventions and statistics will be removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    resetDialog = null
                    saving = true
                    dependencies.scope.launch {
                        runCatching {
                            dependencies.engine.finish(InterventionOutcome.CANCELLED)
                            dependencies.engine.flushHistory()
                            dependencies.history.clear()
                            if (reset == "all") {
                                dependencies.protections.clear()
                                dependencies.preferences.clear()
                                dependencies.quotes.clear()
                            }
                        }.onSuccess { message = if (reset == "all") "App data reset." else "History deleted." }
                            .onFailure { message = "Couldn't complete the reset. Please try again." }
                        saving = false
                    }
                }) { Text(if (reset == "all") "Reset data" else "Delete history", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { resetDialog = null }) { Text("Keep my data") } },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        shape = PauseDimensions.CardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
@Composable
private fun ToggleRow(title: String, description: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title })
    }
}

@Composable
private fun SecondsControl(title: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val safe = value.coerceIn(range.first, range.last)
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(durationLabel(safe), Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (range.first < range.last) {
            Slider(value = safe.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(range.first, range.last)) },
                modifier = Modifier.semantics { contentDescription = title; stateDescription = "$safe seconds" },
                valueRange = range.first.toFloat()..range.last.toFloat())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { onChange((safe - 1).coerceAtLeast(range.first)) }, enabled = safe > range.first,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.semantics { contentDescription = "Decrease $title by 1 second" }) { Text("−1 sec") }
                TextButton(onClick = { onChange((safe + 1).coerceAtMost(range.last)) }, enabled = safe < range.last,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.semantics { contentDescription = "Increase $title by 1 second" }) { Text("+1 sec") }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            Text("Fixed at ${durationLabel(safe)} by your app-time range.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun durationLabel(seconds: Int): String = when {
    seconds < 60 -> "$seconds sec"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60}m ${seconds % 60}s"
}

private val ConfigSaver = listSaver<InterventionConfig, Any>(
    save = { config -> listOf(config.breathingSeconds, config.inhaleSeconds, config.holdSeconds,
        config.exhaleSeconds, config.haptics, config.sound, config.animation.name,
        config.minUsageSeconds, config.startUsageSeconds, config.maxUsageSeconds, config.showQuotes) },
    restore = { values -> InterventionConfig(
        breathingSeconds = values[0] as Int,
        inhaleSeconds = values[1] as Int,
        holdSeconds = values[2] as Int,
        exhaleSeconds = values[3] as Int,
        haptics = values[4] as Boolean,
        sound = values[5] as Boolean,
        animation = BreathingAnimation.valueOf(values[6] as String),
        minUsageSeconds = values[7] as Int,
        startUsageSeconds = values[8] as Int,
        maxUsageSeconds = values[9] as Int,
        showQuotes = values.getOrNull(10) as? Boolean ?: true,
    ).normalized() },
)

private fun AppCategory.displayName(): String = when (this) {
    AppCategory.SOCIAL -> "Social media"
    AppCategory.VIDEO -> "Video & entertainment"
    AppCategory.GAMES -> "Games"
    AppCategory.COMMUNICATION -> "Communication"
    AppCategory.SHOPPING -> "Shopping"
    AppCategory.PRODUCTIVITY -> "Productivity"
    AppCategory.OTHER -> "Other"
}

