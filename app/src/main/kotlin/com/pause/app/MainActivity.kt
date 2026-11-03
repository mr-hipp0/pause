package com.pause.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.pause.core.*
import com.pause.domain.*
import com.pause.feature.dashboard.DashboardScreen
import com.pause.feature.selector.AppSelectorScreen
import com.pause.feature.settings.SettingsScreen
import com.pause.feature.statistics.StatisticsScreen
import com.pause.feature.intervention.InterventionActivity
import com.pause.service.ProtectionServiceStatus
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(private val dependencies: AppDependencies) : ViewModel() {
    val preferences = dependencies.preferences.preferences
        .map<UserPreferences, UserPreferences?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val permissionEnabled = MutableStateFlow(false)
    val notificationsEnabled = MutableStateFlow(false)
}

class MainActivity : ComponentActivity() {
    private val dependencies get() = (application as DependencyOwner).dependencies
    private val model by lazy {
        ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(dependencies) as T
        })[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val preferences by model.preferences.collectAsStateWithLifecycle()
            val enabled by model.permissionEnabled.collectAsStateWithLifecycle()
            val notifications by model.notificationsEnabled.collectAsStateWithLifecycle()
            val connected by ProtectionServiceStatus.connected.collectAsStateWithLifecycle()
            var route by rememberSaveable { mutableStateOf("dashboard") }
            var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
            val scope = rememberCoroutineScope()
            val notificationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                refreshPermissionState()
            }
            val showSetup = preferences?.onboardingComplete == false || route == "setup"
            val protectionReady = enabled && connected && preferences?.accessibilityDisclosureAccepted == true
            BackHandler(enabled = route == "setup" && preferences?.onboardingComplete == true) { route = "settings" }
            PauseTheme(preferences?.theme ?: ThemeMode.DARK) {
                Surface(Modifier.fillMaxSize()) {
                    if (preferences == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() }
                    } else if (showSetup) {
                        SetupScreen(
                            accessibilityEnabled = enabled,
                            serviceConnected = connected,
                            disclosureAccepted = preferences!!.accessibilityDisclosureAccepted,
                            notificationsEnabled = notifications,
                            onAgree = {
                                scope.launch {
                                    runCatching {
                                        dependencies.preferences.update { it.copy(accessibilityDisclosureAccepted = true) }
                                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    }.onFailure { showError("Couldn't open accessibility settings.") }
                                }
                            },
                            onNotifications = {
                                if (Build.VERSION.SDK_INT >= 33 && !notifications) {
                                    notificationRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                                        .onFailure { showError("Couldn't open notification settings.") }
                                }
                            },
                            onDone = {
                                val destination = if (protectionReady && preferences?.onboardingComplete == false) "apps" else "dashboard"
                                scope.launch {
                                    runCatching {
                                        dependencies.preferences.update { it.copy(onboardingComplete = true) }
                                        route = destination
                                    }.onFailure { showError("Couldn't save setup. Please try again.") }
                                }
                            },
                        )
                    } else {
                        BackHandler(enabled = route != "dashboard") { route = "dashboard"; selectedPackage = null }
                        Scaffold(
                            bottomBar = {
                                if (route in listOf("dashboard", "statistics", "settings")) {
                                    Column {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                                        listOf("dashboard" to "Today", "statistics" to "Insights", "settings" to "Settings").forEach { (destination, label) ->
                                            NavigationBarItem(
                                                selected = route == destination,
                                                onClick = { selectedPackage = null; route = destination },
                                                icon = { Icon(when (destination) {
                                                    "dashboard" -> PauseIcons.Today
                                                    "statistics" -> PauseIcons.Insights
                                                    else -> PauseIcons.Settings
                                                }, contentDescription = null) },
                                                label = { Text(label) },
                                                colors = NavigationBarItemDefaults.colors(
                                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            )
                                        }
                                    }
                                    }
                                }
                            },
                        ) { insets ->
                            Box(Modifier.padding(insets)) {
                                when (route) {
                                    "dashboard" -> DashboardScreen(dependencies, protectionReady,
                                        onManageApps = { route = "apps" },
                                        onConfigureApp = { selectedPackage = it; route = "app-settings" },
                                        onSetup = { route = "setup" })
                                    "apps" -> AppSelectorScreen(dependencies, onBack = { route = "dashboard" })
                                    "statistics" -> StatisticsScreen(dependencies, onBack = { route = "dashboard" })
                                    else -> SettingsScreen(dependencies,
                                        packageName = if (route == "app-settings") selectedPackage else null,
                                        onBack = { route = "dashboard" },
                                        onSetup = { route = "setup" },
                                        onPreview = {
                                            scope.launch {
                                                runCatching {
                                                    val current = dependencies.preferences.preferences.first()
                                                    val app = dependencies.catalog.listApps().firstOrNull { it.packageName == selectedPackage }
                                                        ?: InstalledApp(packageName, "your app", AppCategory.OTHER)
                                                    val override = dependencies.protections.apps.first().firstOrNull { it.packageName == selectedPackage }?.config
                                                    dependencies.engine.begin(app, (override ?: current.defaults).normalized(), isPreview = true)
                                                    startActivity(InterventionActivity.intent(this@MainActivity))
                                                }.onFailure {
                                                    dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
                                                    showError("Couldn't start the preview.")
                                                }
                                            }
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        dependencies.engine.onForeground(packageName)
        refreshPermissionState()
    }

    private fun refreshPermissionState() {
        model.permissionEnabled.value = ProtectionServiceStatus.isEnabled(this)
        model.notificationsEnabled.value = NotificationManagerCompat.from(this).areNotificationsEnabled()
    }
    private fun showError(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
