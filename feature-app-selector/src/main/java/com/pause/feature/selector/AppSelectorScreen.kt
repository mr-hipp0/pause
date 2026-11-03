package com.pause.feature.selector

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pause.core.*
import com.pause.domain.AppCategory
import com.pause.domain.InstalledApp
import com.pause.domain.ProtectedApp
import kotlinx.coroutines.launch

@Composable
fun AppSelectorScreen(dependencies: AppDependencies, onBack: () -> Unit) {
    val protected by dependencies.protections.apps.collectAsStateWithLifecycle(emptyList())
    val selected = protected.mapTo(mutableSetOf()) { it.packageName }
    val categoryOverrides = protected.associate { it.packageName to it.categoryOverride }
    fun categoryOf(app: InstalledApp): AppCategory = categoryOverrides[app.packageName] ?: app.category
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var search by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<AppCategory?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refresh) {
        loading = true
        runCatching { dependencies.catalog.listApps() }
            .onSuccess { apps = it; error = null }
            .onFailure { error = "Couldn't read installed apps. Tap Refresh to try again." }
        loading = false
    }
    LaunchedEffect(apps, protected, loading) {
        if (!loading && category != null && apps.none { categoryOf(it) == category }) category = null
    }
    val visible = apps.filter { (category == null || categoryOf(it) == category) &&
        (it.name.contains(search, ignoreCase = true) || it.packageName.contains(search, ignoreCase = true)) }
    val allVisibleSelected = visible.isNotEmpty() && visible.all { it.packageName in selected }

    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.padding(start = PauseDimensions.PagePadding, end = PauseDimensions.PagePadding, top = 4.dp)) {
            PausePageHeader(
                title = "Choose apps",
                subtitle = "${selected.size} selected · saved automatically",
                onBack = onBack,
                action = { TextButton(onClick = { refresh++ }) { Text("Refresh") } },
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search installed apps") }, label = { Text("Search apps") },
                singleLine = true, shape = PauseDimensions.ControlShape,
                leadingIcon = { Icon(PauseIcons.Search, null, Modifier.size(20.dp)) },
                trailingIcon = if (search.isNotEmpty()) {
                    { IconButton(onClick = { search = "" }) { Icon(PauseIcons.Close, "Clear search", Modifier.size(18.dp)) } }
                } else null,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text("All apps") })
                AppCategory.entries.filter { entry -> apps.any { categoryOf(it) == entry } }.forEach { entry ->
                    FilterChip(selected = category == entry, onClick = { category = entry }, label = { Text(entry.displayName()) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${visible.size} ${if (visible.size == 1) "app" else "apps"}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                if (visible.isNotEmpty()) {
                    TextButton(onClick = { scope.launch {
                        runCatching {
                            visible.forEach { app ->
                                if (allVisibleSelected) dependencies.protections.remove(app.packageName)
                                else if (app.packageName !in selected) dependencies.protections.upsert(ProtectedApp(app.packageName))
                            }
                        }.onSuccess { error = null }
                            .onFailure { error = "Some selections couldn't be saved. Please try again." }
                    } }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(if (allVisibleSelected) "Deselect visible apps" else "Select visible apps")
                    }
                }
            }
            error?.let {
                Text(it, Modifier.padding(bottom = 12.dp), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (loading && apps.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("Finding installed apps…", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(
                    start = PauseDimensions.PagePadding, end = PauseDimensions.PagePadding,
                    top = 4.dp, bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (visible.isEmpty()) item {
                    Card(shape = PauseDimensions.CardShape,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.fillMaxWidth().padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (apps.isEmpty()) "No apps found" else "No matching apps", style = MaterialTheme.typography.titleMedium)
                            Text(if (apps.isEmpty()) "Refresh after installing a supported launchable app."
                                else "Try a different name or category.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                items(visible, key = { it.packageName }) { app ->
                    val protection = protected.firstOrNull { it.packageName == app.packageName }
                    val checked = app.packageName in selected
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer),
                        border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.packageName, app.name)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(app.name, style = MaterialTheme.typography.titleMedium)
                                Text(when {
                                    protection?.enabled == true -> "Protected · ${categoryOf(app).displayName()}"
                                    protection != null -> "Paused · ${categoryOf(app).displayName()}"
                                    else -> categoryOf(app).displayName()
                                }, style = MaterialTheme.typography.bodySmall,
                                    color = if (protection?.enabled == true) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Checkbox(
                                modifier = Modifier.semantics { contentDescription = "Protect ${app.name}" },
                                checked = checked, onCheckedChange = { selectedNow -> scope.launch {
                                    runCatching {
                                        if (selectedNow) dependencies.protections.upsert(protection ?: ProtectedApp(app.packageName))
                                        else dependencies.protections.remove(app.packageName)
                                    }.onSuccess { error = null }
                                        .onFailure { error = "Couldn't save ${app.name}. Please try again." }
                                } },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun AppCategory.displayName(): String = when (this) {
    AppCategory.SOCIAL -> "Social media"
    AppCategory.VIDEO -> "Video & entertainment"
    AppCategory.GAMES -> "Games"
    AppCategory.COMMUNICATION -> "Communication"
    AppCategory.SHOPPING -> "Shopping"
    AppCategory.PRODUCTIVITY -> "Productivity"
    AppCategory.OTHER -> "Other"
}

