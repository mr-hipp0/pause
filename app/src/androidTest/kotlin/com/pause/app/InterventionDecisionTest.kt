package com.pause.app

import android.app.UiAutomation
import android.os.Build
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import com.pause.domain.AppCategory
import com.pause.domain.InstalledApp
import com.pause.domain.InterventionConfig
import com.pause.domain.InterventionOutcome
import com.pause.domain.InterventionPhase
import com.pause.feature.intervention.InterventionActivity
import com.pause.feature.intervention.InterventionPresentation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Activity coverage without changing preferences, protection, or Android settings. */
@RunWith(AndroidJUnit4::class)
class InterventionDecisionTest {
    @Test fun previewDecisionSurvivesRecreationAndRequiresContinueOrLeave() {
        assumeTrue("This Activity fixture runs only on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val dependencies = (context.applicationContext as PauseApplication).dependencies
        assumeTrue("This fixture requires an idle intervention engine", dependencies.engine.state.value == null)
        val savedHistory = runBlocking {
            dependencies.engine.flushHistory()
            dependencies.history.records.first()
        }
        val target = InstalledApp("com.pause.decision.preview", "your app", AppCategory.OTHER)
        val config = InterventionConfig(breathingSeconds = 3, haptics = false, sound = false, showQuotes = false)
        val configurator = Configurator.getInstance()
        val oldFlags = configurator.uiAutomationFlags
        val oldIdleTimeout = configurator.waitForIdleTimeout
        configurator.uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        configurator.waitForIdleTimeout = 100
        val device = UiDevice.getInstance(instrumentation)

        fun beginPreview(): String {
            instrumentation.runOnMainSync { dependencies.engine.begin(target, config, isPreview = true) }
            return requireNotNull(dependencies.engine.state.value).id
        }

        fun awaitDecision(sessionId: String) {
            await("continue-or-leave decision", 8_000) {
                dependencies.engine.state.value?.let { it.id == sessionId && it.phase == InterventionPhase.DECISION } == true &&
                    InterventionPresentation.visibleSessionId.value == sessionId &&
                    device.hasObject(By.text("Continue")) && device.hasObject(By.text("Leave"))
            }
        }

        try {
            val sessionId = beginPreview()
            ActivityScenario.launch<InterventionActivity>(InterventionActivity.intent(context)).use { scenario ->
                await("breathing presentation") {
                    dependencies.engine.state.value?.phase == InterventionPhase.BREATHING &&
                        InterventionPresentation.visibleSessionId.value == sessionId
                }
                assertFullscreen(scenario, "breathing")
                awaitDecision(sessionId)
                assertFullscreen(scenario, "decision")
                scenario.recreate()
                awaitDecision(sessionId)
                assertFullscreen(scenario, "decision after recreation")
                SystemClock.sleep(700)
                assertEquals(sessionId, dependencies.engine.state.value?.id)
                assertEquals(InterventionPhase.DECISION, dependencies.engine.state.value?.phase)
                assertEquals(0L, dependencies.engine.state.value?.usedMillis)
                assertFalse("The time selector must wait for Continue", device.hasObject(By.desc("Start app session")))
                // Android can show its one-time immersive-mode tutorial over the first capture.
                device.findObject(By.text("Got it"))?.click()
                SystemClock.sleep(300)
                assertTrue("The complete fullscreen decision must be captured",
                    device.takeScreenshot(File(context.getExternalFilesDir(null) ?: context.filesDir, "pause-fullscreen-decision.png")))

                click(device, "Continue")
                await("duration selection") {
                    dependencies.engine.state.value?.phase == InterventionPhase.SELECTION &&
                        device.hasObject(By.text("Finish preview"))
                }
                assertEquals(sessionId, dependencies.engine.state.value?.id)
                assertFullscreen(scenario, "duration selection")
                click(device, "Finish preview")
                await("completed preview closes") { scenario.state == Lifecycle.State.DESTROYED }
                assertNull(dependencies.engine.state.value)
            }

            val leaveSessionId = beginPreview()
            ActivityScenario.launch<InterventionActivity>(InterventionActivity.intent(context)).use { scenario ->
                awaitDecision(leaveSessionId)
                assertFullscreen(scenario, "Leave decision")
                click(device, "Leave")
                await("Leave closes preview") { scenario.state == Lifecycle.State.DESTROYED }
                assertNull(dependencies.engine.state.value)
            }
            assertEquals("Preview choices must not add usage history", savedHistory, runBlocking {
                dependencies.engine.flushHistory()
                dependencies.history.records.first()
            })
        } finally {
            try {
                instrumentation.runOnMainSync { dependencies.engine.finish(InterventionOutcome.INTERRUPTED) }
            } finally {
                configurator.waitForIdleTimeout = oldIdleTimeout
                configurator.uiAutomationFlags = oldFlags
            }
        }
    }

    private fun assertFullscreen(scenario: ActivityScenario<InterventionActivity>, stage: String) {
        await("hidden system bars during $stage") {
            var barsHidden = false
            scenario.onActivity { activity ->
                val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                barsHidden = insets != null && !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            }
            barsHidden
        }
        scenario.onActivity { activity ->
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
            assertFalse("Status bar must be hidden during $stage", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
            assertFalse("Navigation bar must be hidden during $stage", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
        }
    }

    private fun click(device: UiDevice, text: String) {
        repeat(8) { attempt ->
            try {
                val bounds = device.findObject(By.text(text))?.visibleBounds
                if (bounds != null && !bounds.isEmpty && bounds.height() > 12) {
                    device.click(bounds.centerX(), bounds.centerY())
                    return
                }
            } catch (_: StaleObjectException) {
                SystemClock.sleep(100)
                return@repeat
            }
            val down = attempt < 5
            device.swipe(device.displayWidth / 2, device.displayHeight * (if (down) 75 else 35) / 100,
                device.displayWidth / 2, device.displayHeight * (if (down) 35 else 75) / 100, 30)
            SystemClock.sleep(100)
        }
        error("Could not find visible intervention action: $text")
    }

    private fun await(description: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (condition()) return } catch (_: StaleObjectException) { /* Query the new node. */ }
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for $description", try { condition() } catch (_: StaleObjectException) { false })
    }
}
