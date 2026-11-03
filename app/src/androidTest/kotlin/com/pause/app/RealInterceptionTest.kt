package com.pause.app

import android.app.UiAutomation
import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.StaleObjectException
import com.pause.domain.InstalledApp
import com.pause.domain.InterventionConfig
import com.pause.domain.InterventionOutcome
import com.pause.domain.InterventionPhase
import com.pause.domain.ProtectedApp
import com.pause.domain.RecordKind
import com.pause.feature.intervention.InterventionPresentation
import com.pause.service.PauseAccessibilityService
import com.pause.service.ProtectionServiceStatus

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real cross-app coverage. Explicitly opt in with -e pauseRunInterception true.
 * The hardware guard prevents secure-setting changes on a physical device.
 * Existing protection/preferences/accessibility settings are restored in finally.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RealInterceptionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES

    @Test fun realUsageAllowancesExpireAndRequireFreshInterventions() {
        assumeTrue("Pass -e pauseRunInterception true to run this emulator fixture.",
            InstrumentationRegistry.getArguments().getString("pauseRunInterception") == "true")
        assumeTrue("This test must never modify a physical device.", Build.HARDWARE in setOf("ranchu", "goldfish"))

        val context = instrumentation.targetContext
        val dependencies = (context.applicationContext as PauseApplication).dependencies
        val installed = runBlocking { dependencies.catalog.listApps() }
        val candidates = installed.filter(::isSafeFixture)
            .sortedBy { candidatePriority(it.packageName) }
        assumeTrue("The emulator must have at least two ordinary launchable apps.", candidates.size >= 2)
        val target = candidates[0]
        val secondTarget = candidates[1]
        val savedPreferences = runBlocking { dependencies.preferences.preferences.first() }
        val savedProtections = runBlocking { dependencies.protections.apps.first() }
        val configurator = Configurator.getInstance()
        val oldAutomationFlags = configurator.getUiAutomationFlags()
        val oldIdleTimeout = configurator.getWaitForIdleTimeout()
        // UiAutomator otherwise suppresses the very Accessibility Service this test exercises.
        configurator.setUiAutomationFlags(automationFlags)
        configurator.setWaitForIdleTimeout(100)
        val device = UiDevice.getInstance(instrumentation)
        val savedServices = shell("settings get secure enabled_accessibility_services").trim()
        val savedAccessibilityEnabled = shell("settings get secure accessibility_enabled").trim()
        val serviceComponent = ComponentName(context, PauseAccessibilityService::class.java)
        val service = serviceComponent.flattenToString()
        val otherServices = savedServices.takeUnless { it.isBlank() || it == "null" }
            ?.split(':').orEmpty().filter(String::isNotBlank)
            .filterNot { ComponentName.unflattenFromString(it) == serviceComponent }
        try {
            device.wakeUp()
            shell("wm dismiss-keyguard")
            // Instrumentation force-stops/reinstalls its target. A previously enabled service can
            // retain an old pending binding, so force a real disable→enable transition for ours.
            // Other enabled accessibility services are preserved throughout the fixture.
            if (otherServices.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${argument(otherServices.joinToString(":"))}")
            await("old accessibility binding removed", 8_000, diagnostics = { accessibilityDiagnostics(service) }) {
                val manager = shell("dumpsys accessibility")
                manager.lineSequence().filter {
                    val line = it.trimStart()
                    line.startsWith("Enabled services:") || line.startsWith("Binding services:")
                }.none { it.contains(service) }
            }
            SystemClock.sleep(250)
            // Instrumentation alone need not clear a freshly installed package's stopped state.
            // A real user opens onboarding before granting accessibility, so mirror that path.
            val mainComponent = ComponentName(context, MainActivity::class.java).flattenToString()
            shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${argument(mainComponent)}")
            await("Pause onboarding/main activity opened", 8_000) { device.currentPackageName == context.packageName }
            instrumentation.waitForIdleSync()
            device.pressHome()
            runBlocking {
                dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
                dependencies.protections.clear()
                // Exercise the real Room-backed selection store with ten discovered apps.
                assertTrue("This emulator fixture requires ten discoverable launchable apps", installed.size >= 10)
                run {
                    installed.take(10).forEach { dependencies.protections.upsert(ProtectedApp(it.packageName)) }
                    assertEquals(10, dependencies.protections.apps.first().size)
                    dependencies.protections.clear()
                }
                dependencies.preferences.update(savedPreferences.copy(
                    protectionEnabled = true, onboardingComplete = true, accessibilityDisclosureAccepted = true,
                    defaults = InterventionConfig(breathingSeconds = 3, inhaleSeconds = 1, exhaleSeconds = 2,
                        minUsageSeconds = 1, startUsageSeconds = 4, maxUsageSeconds = 300, haptics = false, sound = false, showQuotes = true),
                ))
                dependencies.protections.upsert(ProtectedApp(target.packageName))
                dependencies.protections.upsert(ProtectedApp(secondTarget.packageName))
            }
            val services = (otherServices + service).distinct().joinToString(":")
            // UiAutomation uses Runtime.exec rather than a shell parser: quotes become literal
            // characters in the setting. These package/component tokens contain no whitespace.
            shell("settings put secure enabled_accessibility_services ${argument(services)}")
            shell("settings put secure accessibility_enabled 1")
            val enabledReadBack = shell("settings get secure enabled_accessibility_services").trim()
            if (!enabledReadBack.split(':').contains(service)) {
                throw AssertionError("Enabled component missing after fixture setup: $enabledReadBack\n${accessibilityDiagnostics(service)}")
            }
            await("Accessibility Service connected", 12_000, diagnostics = { accessibilityDiagnostics(service) }) {
                ProtectionServiceStatus.connected.value
            }
            instrumentation.runOnMainSync { /* Drain already queued preference updates. */ }
            SystemClock.sleep(200)

            fun launch(app: InstalledApp) {
                val component = context.packageManager.getLaunchIntentForPackage(app.packageName)?.component
                    ?: error("No launcher component for ${app.packageName}")
                // This starts the actual installed app. The test never calls engine.begin().
                val output = shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${argument(component.flattenToString())}")
                assertTrue("The real target launch failed: $output", output.contains("Starting: Intent"))
            }

            fun intercepted(app: InstalledApp): String {
                await("visible breathing intervention for ${app.packageName}", 12_000) {
                    val state = dependencies.engine.state.value
                    state != null && !state.isPreview && state.target.packageName == app.packageName &&
                        state.phase == InterventionPhase.BREATHING && InterventionPresentation.visibleSessionId.value == state.id &&
                        device.currentPackageName == context.packageName && device.hasObject(By.text("Take a breath."))
                }
                return dependencies.engine.state.value!!.id
            }

            fun chooseAndStart(app: InstalledApp, id: String, seconds: Int) {
                await("fresh duration choice for ${app.packageName}", 8_000) {
                    val session = dependencies.engine.state.value
                    session?.id == id && session.phase == InterventionPhase.SELECTION &&
                        device.hasObject(By.desc("Start app session"))
                }
                assertEquals(seconds, dependencies.engine.state.value!!.selectedSeconds)
                assertEquals(300, dependencies.engine.state.value!!.config.maxUsageSeconds)
                assertFalse("The old waiting countdown must not appear", device.hasObject(By.text("Start my pause")))
                clickText(device, "Start app session", byDescription = true)
                await("target app usable immediately after choosing its allowance", 8_000) {
                    val session = dependencies.engine.state.value
                    session?.id == id && session.phase == InterventionPhase.USING &&
                        device.currentPackageName == app.packageName && InterventionPresentation.visibleSessionId.value == null
                }
                val running = dependencies.engine.state.value
                if (running == null || running.id != id || running.phase != InterventionPhase.USING) {
                    val stored = runBlocking {
                        dependencies.engine.flushHistory()
                        dependencies.history.records.first().firstOrNull { it.id == id }
                    }
                    throw AssertionError("The confirmed usage allowance ended or changed immediately after launch. " +
                        "Expected target=${app.packageName}, id=$id, phase=USING; " +
                        "observed=${running?.id}/${running?.phase}; " +
                        "currentPackage=${device.currentPackageName}; saved=$stored")
                }
                assertTrue("A confirmed target starts with a bounded positive allowance", running.remainingMillis in 1..seconds * 1_000L)
            }

            fun savedRecord(id: String) = runBlocking {
                dependencies.engine.flushHistory()
                dependencies.history.records.first().single { it.id == id }
            }

            // The countdown runs while the real target is usable. Each expiration creates a new
            // visible breathing exercise and a fresh explicit choice; there is no waiting gate.
            launch(target)
            var cycleId = intercepted(target)
            val expiredIds = mutableListOf<String>()
            repeat(3) { cycle ->
                await("cycle $cycle duration selection", 8_000) {
                    dependencies.engine.state.value?.let { it.id == cycleId && it.phase == InterventionPhase.SELECTION } == true
                }
                val choiceId = cycleId
                if (cycle == 0) {
                    SystemClock.sleep(700)
                    assertEquals("Choosing a duration must never start access automatically", InterventionPhase.SELECTION,
                        dependencies.engine.state.value!!.phase)
                    assertEquals(choiceId, dependencies.engine.state.value!!.id)
                }
                chooseAndStart(target, choiceId, 4)
                expiredIds += choiceId
                cycleId = intercepted(target)
                assertNotEquals("Expiration must create a new intervention cycle", choiceId, cycleId)
                val record = savedRecord(choiceId)
                assertEquals(RecordKind.USAGE, record.kind)
                assertEquals(InterventionOutcome.EXPIRED, record.outcome)
                assertEquals(4, record.allocatedSeconds)
                assertEquals(4_000L, record.usedMillis)
                assertTrue(record.usageStartedAtMillis != null)
                assertEquals("Usage must not be mislabeled as a historical waiting period", 0, record.waitSeconds)
            }
            clickText(device, "Return to what matters")
            await("cancel after the third expiration", 5_000) { dependencies.engine.state.value == null }

            // A longer new allowance leaves enough real foreground time to inspect transient
            // coverage and to leave before it expires. B keeps independent four-second settings.
            runBlocking {
                dependencies.preferences.update { prefs -> prefs.copy(defaults = prefs.defaults.copy(startUsageSeconds = 20)) }
                val prefs = dependencies.preferences.preferences.first()
                dependencies.protections.upsert(ProtectedApp(secondTarget.packageName,
                    config = prefs.defaults.copy(startUsageSeconds = 4)))
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(250)
            launch(target)
            val leaveId = intercepted(target)
            chooseAndStart(target, leaveId, 20)

            device.openNotification()
            await("notification shade covering the app", 5_000) { device.currentPackageName == "com.android.systemui" }
            SystemClock.sleep(250)
            val coveredRemaining = dependencies.engine.state.value!!.remainingMillis
            SystemClock.sleep(650)
            assertEquals("A transient overlay must not renew the session", leaveId, dependencies.engine.state.value!!.id)
            assertTrue("Covered time must not consume an app allowance",
                kotlin.math.abs(coveredRemaining - dependencies.engine.state.value!!.remainingMillis) <= 150L)
            device.pressBack()
            await("target resumed after transient overlay", 5_000) {
                device.currentPackageName == target.packageName && dependencies.engine.state.value?.id == leaveId
            }
            val resumedRemaining = dependencies.engine.state.value!!.remainingMillis
            SystemClock.sleep(650)
            assertEquals("Closing the shade must retain the same allowance", leaveId, dependencies.engine.state.value!!.id)
            assertTrue("Foreground usage must resume charging after the shade closes",
                resumedRemaining - dependencies.engine.state.value!!.remainingMillis >= 300L)
            device.pressHome()
            await("leaving ends the allowance", 5_000) { dependencies.engine.state.value == null }
            val departed = savedRecord(leaveId)
            assertEquals(InterventionOutcome.LEFT_APP, departed.outcome)
            assertTrue(departed.usedMillis in 1..19_999L)
            SystemClock.sleep(500)
            assertNull("A departed app's old timer must not re-open an intervention", dependencies.engine.state.value)

            launch(target)
            val reopenedId = intercepted(target)
            assertNotEquals(leaveId, reopenedId)
            chooseAndStart(target, reopenedId, 20)
            assertTrue("A fresh full allowance must replace the discarded remainder",
                dependencies.engine.state.value!!.remainingMillis > 18_000L)
            launch(secondTarget)
            val secondId = intercepted(secondTarget)
            assertNotEquals(reopenedId, secondId)
            assertEquals(InterventionOutcome.LEFT_APP, savedRecord(reopenedId).outcome)
            chooseAndStart(secondTarget, secondId, 4)
            device.pressHome()
            await("leaving the second protected app", 5_000) { dependencies.engine.state.value == null }
            assertEquals(InterventionOutcome.LEFT_APP, savedRecord(secondId).outcome)

            // Preview does not grant app access or write real usage history. Opening a protected
            // app directly from preview must still produce its own real intervention.
            shell("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n ${argument(mainComponent)}")
            await("Pause before opening preview", 8_000) { device.currentPackageName == context.packageName }
            clickText(device, "Settings")
            await("settings screen rendered", 5_000) { device.hasObject(By.text("Make Pause yours")) }
            clickText(device, "Preview")
            await("visible settings preview", 7_000) {
                val preview = dependencies.engine.state.value
                preview?.isPreview == true && InterventionPresentation.visibleSessionId.value == preview.id
            }
            val previewId = dependencies.engine.state.value!!.id
            launch(target)
            val fromPreviewId = intercepted(target)
            assertNotEquals(previewId, fromPreviewId)
            clickText(device, "Return to what matters")
            await("preview departure still guards the target", 5_000) { dependencies.engine.state.value == null }
            val history = runBlocking { dependencies.engine.flushHistory(); dependencies.history.records.first() }
            assertTrue("Preview must not pollute actual history", history.none { it.id == previewId })
            assertEquals(InterventionOutcome.CANCELLED, history.single { it.id == fromPreviewId }.outcome)
            assertEquals(3, history.count { it.id in expiredIds && it.outcome == InterventionOutcome.EXPIRED })

            launch(target)
            val revokedId = intercepted(target)
            chooseAndStart(target, revokedId, 20)
            if (otherServices.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${argument(otherServices.joinToString(":"))}")
            await("permission revocation ends the active usage allowance", 8_000) {
                !ProtectionServiceStatus.connected.value && dependencies.engine.state.value == null
            }
            assertFalse(ProtectionServiceStatus.isEnabled(context))
            assertEquals(InterventionOutcome.INTERRUPTED, savedRecord(revokedId).outcome)
            launch(target)
            await("disabled service does not claim protection", 5_000) {
                device.currentPackageName == target.packageName && dependencies.engine.state.value == null
            }
        } catch (failure: Throwable) {
            shell("screencap -p /sdcard/Download/pause-integration-failure.png")
            throw failure
        } finally {
            device.pressHome()
            instrumentation.runOnMainSync {
                dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
            }
            try {
                restoreSecureSetting("enabled_accessibility_services", savedServices)
                restoreSecureSetting("accessibility_enabled", savedAccessibilityEnabled)
            } finally {
                runBlocking {
                    dependencies.engine.flushHistory()
                    dependencies.protections.clear()
                    savedProtections.forEach { dependencies.protections.upsert(it) }
                    dependencies.preferences.update(savedPreferences)
                }
                configurator.setWaitForIdleTimeout(oldIdleTimeout)
                configurator.setUiAutomationFlags(oldAutomationFlags)
            }
        }
    }

    private fun isSafeFixture(app: InstalledApp): Boolean = listOf(
        "deskclock", "calculator", "calendar", "gallery", "documentsui", "photos",
    ).any { app.packageName.contains(it, ignoreCase = true) }

    private fun candidatePriority(packageName: String): Int = when {
        packageName.contains("deskclock") -> 0
        packageName.contains("calculator") -> 1
        packageName.contains("documentsui") -> 2
        packageName.contains("calendar") -> 3
        else -> 4
    }

    private fun clickText(device: UiDevice, text: String, maxScrollAttempts: Int = 5, byDescription: Boolean = false) {
        repeat(maxScrollAttempts) { attempt ->
            val node = device.findObject(if (byDescription) By.desc(text) else By.text(text))
            if (node != null) {
                try {
                    val bounds = node.visibleBounds
                    if (!bounds.isEmpty && bounds.height() > 12) {
                        device.click(bounds.centerX(), bounds.centerY())
                        return
                    }
                } catch (_: StaleObjectException) {
                    // AnimatedContent may replace a matching node between lookup and click.
                    // Re-query the current tree; a stale fixture handle is not an app action.
                    SystemClock.sleep(100)
                    return@repeat
                }
            }
            // Scroll in the empty gutter, away from sliders and nested horizontal lists.
            val down = attempt < (maxScrollAttempts + 1) / 2
            val top = device.displayHeight * 45 / 100
            val bottom = device.displayHeight * 75 / 100
            device.swipe(10, if (down) bottom else top, 10, if (down) top else bottom, 100)
            SystemClock.sleep(350)
        }
        error("Could not find intervention action: $text")
    }

    private fun await(description: String, timeoutMillis: Long, diagnostics: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for $description\n${diagnostics()}", condition())
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(automationFlags).executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun restoreSecureSetting(key: String, value: String) {
        // ContentResolver preserves even an originally empty value, which Runtime.exec tokenizing
        // cannot pass as a standalone empty argument. The privilege exists only in this emulator test.
        val automation = instrumentation.getUiAutomation(automationFlags)
        automation.adoptShellPermissionIdentity(Manifest.permission.WRITE_SECURE_SETTINGS)
        try {
            check(Settings.Secure.putString(instrumentation.targetContext.contentResolver, key, value.takeUnless { it == "null" }))
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun accessibilityDiagnostics(component: String): String = buildString {
        appendLine("Expected component: $component")
        appendLine("enabled_accessibility_services=${shell("settings get secure enabled_accessibility_services").trim()}")
        appendLine("accessibility_enabled=${shell("settings get secure accessibility_enabled").trim()}")
        appendLine("Connected in application=${ProtectionServiceStatus.connected.value}")
        appendLine(shell("dumpsys accessibility"))
        appendLine(shell("dumpsys activity services com.pause.app"))
    }

    private fun argument(value: String): String {
        require(value.matches(Regex("""[A-Za-z0-9_./:$-]+"""))) { "Invalid component/list token: $value" }
        return value
    }
}

