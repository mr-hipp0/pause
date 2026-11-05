package com.pause.app

import android.app.UiAutomation
import android.content.ComponentName
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.StaleObjectException
import com.pause.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Full-window visual fixtures on an isolated emulator, with the real activity/navigation/storage. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VisualScreenshotsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val flags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES

    @Test fun captureRefreshedScreensInBothThemes() {
        assumeTrue("Opt in with pauseRunVisuals=true", InstrumentationRegistry.getArguments().getString("pauseRunVisuals") == "true")
        assumeTrue("Visual fixtures must run only on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = instrumentation.targetContext
        val dependencies = (context.applicationContext as PauseApplication).dependencies
        val savedPrefs = runBlocking { dependencies.preferences.preferences.first() }
        val savedApps = runBlocking { dependencies.protections.apps.first() }
        val savedHistory = runBlocking { dependencies.engine.flushHistory(); dependencies.history.records.first() }
        val installed = runBlocking { dependencies.catalog.listApps() }
        val targets = installed.filter { it.packageName.contains("deskclock") || it.packageName.contains("documentsui") }.take(2)
        assumeTrue("Two ordinary installed apps are needed", targets.size == 2)
        val configurator = Configurator.getInstance()
        val oldFlags = configurator.uiAutomationFlags
        val oldIdle = configurator.waitForIdleTimeout
        configurator.uiAutomationFlags = flags
        configurator.waitForIdleTimeout = 100
        val device = UiDevice.getInstance(instrumentation)
        val profile = InstrumentationRegistry.getArguments().getString("pauseVisualProfile") ?: "standard"
        require(profile.matches(Regex("[a-z-]+")))
        fun capture(name: String, theme: ThemeMode) {
            // Intervention stages have a delayed incoming fade; capture their settled frames.
            SystemClock.sleep(if (name == "selection" || name == "decision") 900 else 250)
            shell("screencap -p /sdcard/Download/pause-$profile-$name-${theme.name.lowercase()}.png")
        }
        try {
            device.wakeUp()
            shell("wm dismiss-keyguard")
            runBlocking {
                dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
                dependencies.engine.flushHistory()
                dependencies.protections.clear()
                targets.forEach { dependencies.protections.upsert(ProtectedApp(it.packageName)) }
                dependencies.history.clear()
                val now = System.currentTimeMillis()
                listOf(60_000L to InterventionOutcome.EXPIRED, 45_000L to InterventionOutcome.LEFT_APP).forEachIndexed { index, (used, outcome) ->
                    val app = targets[index]
                    dependencies.history.upsert(InterventionRecord("visual-$index", app.packageName, app.name,
                        now - 3_600_000L * (index + 1), now - 3_600_000L * (index + 1) + used,
                        outcome, kind = RecordKind.USAGE, allocatedSeconds = 60, usedMillis = used,
                        completedAtMillis = now - 3_600_000L * (index + 1), usageStartedAtMillis = now - 3_600_000L * (index + 1)))
                }
            }
            for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                runBlocking {
                    dependencies.preferences.update(savedPrefs.copy(protectionEnabled = false, onboardingComplete = true,
                        accessibilityDisclosureAccepted = true, theme = theme,
                        defaults = InterventionConfig(breathingSeconds = 10, startUsageSeconds = 60, haptics = false, sound = false)))
                }
                val component = ComponentName(context, MainActivity::class.java).flattenToString()
                shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $component")
                await("main activity") { device.currentPackageName == context.packageName }
                await("main navigation") { device.hasObject(By.text("Today")) }
                click(device, "Today", bottom = true)
                repeat(2) { device.swipe(10, device.displayHeight * 30 / 100, 10, device.displayHeight * 80 / 100, 40) }
                await("Today selected") { hasUpperHeading(device, "Today") }
                capture("today", theme)
                click(device, "Manage")
                await("app selector") { hasUpperHeading(device, "Choose apps") }
                capture("apps", theme)
                device.pressBack()
                click(device, "Insights", bottom = true)
                await("insights") { hasUpperHeading(device, "Insights") }
                capture("insights", theme)
                repeat(5) {
                    if (hasUpperHeading(device, "Interventions", 0.4f)) return@repeat
                    device.swipe(10, device.displayHeight * 75 / 100, 10, device.displayHeight * 40 / 100, 40)
                }
                capture("insights-chart", theme)
                click(device, "Settings", bottom = true)
                await("settings") { device.hasObject(By.text("Make Pause yours")) }
                capture("settings", theme)
                click(device, "5s")
                await("sticky Save is reachable") {
                    device.findObjects(By.text("Save changes")).any { !it.visibleBounds.isEmpty }
                }
                capture("settings-pending", theme)
                click(device, "Save changes")
                await("breathing preference saved") {
                    runBlocking { dependencies.preferences.preferences.first().defaults.breathingSeconds == 5 } &&
                        !device.hasObject(By.text("Your global rhythm has unsaved changes"))
                }
                click(device, "10s")
                click(device, "Save changes")
                await("preview duration saved") {
                    runBlocking { dependencies.preferences.preferences.first().defaults.breathingSeconds == 10 } &&
                        !device.hasObject(By.text("Your global rhythm has unsaved changes"))
                }
                click(device, "Preview")
                await("breathing preview uses saved settings") {
                    dependencies.engine.state.value?.let { it.phase == InterventionPhase.BREATHING && it.config.breathingSeconds == 10 } == true &&
                        device.hasObject(By.text("Take a breath."))
                }
                capture("breathing", theme)
                await("preview decision", 15_000) {
                    dependencies.engine.state.value?.phase == InterventionPhase.DECISION &&
                        device.hasObject(By.text("Continue")) && device.hasObject(By.text("Leave"))
                }
                capture("decision", theme)
                click(device, "Continue")
                await("duration preview", 15_000) { device.hasObject(By.desc("Start app session")) }
                capture("selection", theme)
                click(device, "Return to what matters")
                await("preview finished") { dependencies.engine.state.value == null && device.hasObject(By.text("Preview")) }
                repeat(2) { device.swipe(10, device.displayHeight * 35 / 100, 10, device.displayHeight * 80 / 100, 50) }
                runBlocking { dependencies.preferences.update { it.copy(onboardingComplete = false) } }
                await("setup") { device.hasObject(By.text("A little space.\nA clearer choice.")) }
                capture("setup", theme)
                runBlocking { dependencies.preferences.update { it.copy(onboardingComplete = true) } }
                await("settings after setup fixture") { device.hasObject(By.text("Preview")) }
            }
        } catch (failure: Throwable) {
            shell("screencap -p /sdcard/Download/pause-visual-failure.png")
            throw failure
        } finally {
            device.pressHome()
            runBlocking {
                dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
                dependencies.engine.flushHistory()
                dependencies.history.clear()
                savedHistory.forEach { dependencies.history.upsert(it) }
                dependencies.protections.clear()
                savedApps.forEach { dependencies.protections.upsert(it) }
                dependencies.preferences.update(savedPrefs)
            }
            configurator.waitForIdleTimeout = oldIdle
            configurator.uiAutomationFlags = oldFlags
        }
    }

    private fun click(device: UiDevice, text: String, bottom: Boolean = false) {
        repeat(6) { attempt ->
            try {
                val candidates = device.findObjects(By.text(text)).filter {
                    val bounds = it.visibleBounds
                    !bounds.isEmpty && bounds.height() > 12
                }
                val node = if (bottom) candidates.maxByOrNull { it.visibleBounds.bottom } else candidates.firstOrNull()
                if (node != null) {
                    val bounds = node.visibleBounds
                    device.click(bounds.centerX(), bounds.centerY())
                    return
                }
            } catch (_: StaleObjectException) {
                SystemClock.sleep(100)
                return@repeat
            }
            if (!bottom) {
                val down = attempt < 3
                device.swipe(10, device.displayHeight * (if (down) 75 else 40) / 100,
                    10, device.displayHeight * (if (down) 40 else 75) / 100, 50)
            }
            SystemClock.sleep(150)
        }
        error("Could not find visible action $text")
    }

    private fun hasUpperHeading(device: UiDevice, text: String, fraction: Float = 0.5f) = try {
        device.findObjects(By.text(text)).any {
            val bounds = it.visibleBounds
            !bounds.isEmpty && bounds.top < device.displayHeight * fraction && bounds.height() > 12
        }
    } catch (_: StaleObjectException) { false }

    private fun await(description: String, timeout: Long = 8_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            try {
                if (predicate()) return
            } catch (_: StaleObjectException) {
                // Re-query a replaced Compose node on the next poll.
            }
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for $description", try { predicate() } catch (_: StaleObjectException) { false })
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(flags).executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
