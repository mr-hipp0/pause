package com.pause.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pause.core.*

@Composable
fun SetupScreen(
    accessibilityEnabled: Boolean,
    serviceConnected: Boolean,
    disclosureAccepted: Boolean,
    notificationsEnabled: Boolean,
    onAgree: () -> Unit,
    onNotifications: () -> Unit,
    onDone: () -> Unit,
) {
    val ready = accessibilityEnabled && serviceConnected && disclosureAccepted
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = PauseDimensions.PagePadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                PauseBrand()
                Spacer(Modifier.weight(1f))
                Text("YOUR FIRST PAUSE", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("A little space.\nA clearer choice.", Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineLarge)
                Text("Make your next app opening an intentional one.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(shape = PauseDimensions.CardShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("A rhythm that works for you", style = MaterialTheme.typography.titleMedium)
                    FlowStep("1", "Take a breath", "A gentle pause before the apps you choose.")
                    FlowStep("2", "Choose your time", "Use the app for up to 5 minutes. When time ends, breathe and choose again.")
                }
            }
            SetupStep("01", "Enable protection", when {
                accessibilityEnabled && serviceConnected -> "Service connected"
                accessibilityEnabled -> "Waiting for Android to connect"
                else -> "Access needed"
            }) {
                Text("Pause uses Android Accessibility to recognize protected app package names, count their on-screen usage time, and show a breathing intervention when an app opens or its allowance expires.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("It does not read screen text, messages, passwords, or keystrokes. Your selections and history stay on this device and are not uploaded or shared. You can turn access off in Android settings at any time.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("In Android settings, open Downloaded apps or Installed services, select Pause, and enable it. Some sideloaded devices first require Allow restricted settings in app info.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onAgree, modifier = Modifier.fillMaxWidth().heightIn(min = PauseDimensions.ControlHeight),
                    shape = PauseDimensions.ControlShape) {
                    Text(if (disclosureAccepted) "Review accessibility settings" else "Agree & open accessibility settings")
                }
            }
            SetupStep("02", "Enable a fallback", if (notificationsEnabled) "Notifications enabled" else "Optional") {
                Text("If Android blocks the intervention screen, a notification lets you open it. The protected app may briefly appear before Pause responds.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onNotifications,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = PauseDimensions.ControlShape) {
                    Text(if (notificationsEnabled) "Review notifications" else "Enable fallback notifications")
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 12.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = PauseDimensions.ControlHeight),
                    shape = PauseDimensions.ControlShape) {
                    Text(if (ready) "Choose your apps" else "Explore Pause")
                }
                Text("No account. No subscription. Your pace.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FlowStep(number: String, title: String, description: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.13f), CircleShape),
            contentAlignment = Alignment.Center) {
            Text(number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SetupStep(number: String, title: String, status: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        Surface(shape = PauseDimensions.CardShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(PauseDimensions.CardPadding), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                content()
            }
        }
    }
}
