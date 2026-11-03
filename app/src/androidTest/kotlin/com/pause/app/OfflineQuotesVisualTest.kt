package com.pause.app

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import com.pause.domain.AppCategory
import com.pause.domain.BreathingQuote
import com.pause.domain.InstalledApp
import com.pause.domain.InterventionConfig
import com.pause.domain.InterventionOutcome
import com.pause.domain.InterventionPhase
import com.pause.domain.ThemeMode
import com.pause.feature.intervention.InterventionActivity
import com.pause.feature.intervention.InterventionPresentation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, real-Activity verification and full-window evidence for the bundled offline collection. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class OfflineQuotesVisualTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val flags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    private val sourceDescription = "Inspirational quotes provided by ZenQuotes API. Open website"

    @Test fun packagedQuoteAndAuthorSurviveRecreationAndRenderInBothThemes() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with pauseRunQuoteVisuals=true", arguments.getString("pauseRunQuoteVisuals") == "true")
        assumeTrue("This visual fixture is for an emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val profile = arguments.getString("pauseQuoteVisualProfile") ?: "standard"
        require(profile.matches(Regex("[a-z-]+")))
        val context = instrumentation.targetContext
        val dependencies = (context.applicationContext as PauseApplication).dependencies
        val savedPreferences = runBlocking { dependencies.preferences.preferences.first() }
        val configurator = Configurator.getInstance()
        val oldFlags = configurator.uiAutomationFlags
        val oldIdle = configurator.waitForIdleTimeout
        val device = UiDevice.getInstance(instrumentation)
        val target = InstalledApp("com.pause.quote.preview", "your app", AppCategory.OTHER)
        val config = InterventionConfig(breathingSeconds = 30, haptics = false, sound = false, showQuotes = true)
        // This selects an actual packaged record; no production selection hook or generated quote
        // is substituted. A long entry exercises wrapping in the delivered card.
        val rows = context.assets.open("quotes/zenquotes-v1.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("quotes") }
        val longestText = (0 until rows.length()).map { rows.getJSONObject(it).getString("text") }.maxByOrNull { it.length }
        assertNotNull("The delivered APK must contain a nonempty quote library", longestText)

        fun beginPreview(showQuotes: Boolean): Pair<String, BreathingQuote?> {
            instrumentation.runOnMainSync {
                dependencies.engine.finish(InterventionOutcome.INTERRUPTED)
                dependencies.engine.begin(target, config.copy(showQuotes = showQuotes), isPreview = true)
            }
            val session = requireNotNull(dependencies.engine.state.value)
            return session.id to runBlocking { dependencies.quotes.forSession(session.id, isPreview = true) }
        }

        fun capture(name: String, theme: ThemeMode) {
            SystemClock.sleep(250)
            shell("screencap -p /sdcard/Download/pause-quotes-$profile-$name-${theme.name.lowercase()}.png")
        }

        try {
            configurator.uiAutomationFlags = flags
            configurator.waitForIdleTimeout = 100
            device.wakeUp()
            shell("wm dismiss-keyguard")
            for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
                runBlocking {
                    dependencies.preferences.update(savedPreferences.copy(protectionEnabled = false,
                        onboardingComplete = true, accessibilityDisclosureAccepted = true, theme = theme))
                }
                var chosen = beginPreview(showQuotes = true)
                // Preview IDs are random. Prefer the longest record within a bounded search, then
                // use the final valid record if that particular entry was not drawn.
                for (attempt in 0 until 128) {
                    if (chosen.second?.text == longestText) break
                    chosen = beginPreview(showQuotes = true)
                }
                val (sessionId, nullableQuote) = chosen
                val quote = requireNotNull(nullableQuote) { "The offline quote repository returned no quote" }
                ActivityScenario.launch<InterventionActivity>(InterventionActivity.intent(context)).use { scenario ->
                    await("real breathing Activity and packaged quote") {
                        InterventionPresentation.visibleSessionId.value == sessionId &&
                            dependencies.engine.state.value?.phase == InterventionPhase.BREATHING &&
                            device.hasObject(By.text("Take a breath."))
                    }
                    ensureVisible(device, By.text(quote.text))
                    ensureVisible(device, By.text("— ${quote.author}"))
                    ensureVisible(device, By.desc(sourceDescription))
                    assertEquals(quote, runBlocking { dependencies.quotes.forSession(sessionId, isPreview = true) })
                    if (theme == ThemeMode.DARK) {
                        scenario.recreate()
                        await("same breathing session after Activity recreation") {
                            dependencies.engine.state.value?.id == sessionId &&
                                InterventionPresentation.visibleSessionId.value == sessionId
                        }
                        ensureVisible(device, By.text(quote.text))
                        ensureVisible(device, By.text("— ${quote.author}"))
                        ensureVisible(device, By.desc(sourceDescription))
                    }
                    capture("breathing", theme)
                    ensureVisible(device, By.text("Return to what matters"))
                    if (theme == ThemeMode.DARK) {
                        // Intercept ACTION_VIEW so attribution dispatch is tested without opening
                        // a real website, making network access, or leaving a browser behind.
                        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW).apply {
                            addDataScheme("https")
                            addCategory(Intent.CATEGORY_BROWSABLE)
                        }, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
                        try {
                            clickVisible(device, By.desc(sourceDescription))
                            await("explicit attribution link dispatch") { monitor.hits == 1 }
                            assertEquals(sessionId, dependencies.engine.state.value?.id)
                            assertEquals(InterventionPhase.BREATHING, dependencies.engine.state.value?.phase)
                        } finally {
                            instrumentation.removeMonitor(monitor)
                        }
                    }
                }

                val (withoutQuoteId, _) = beginPreview(showQuotes = false)
                ActivityScenario.launch<InterventionActivity>(InterventionActivity.intent(context)).use {
                    await("quotes-disabled breathing") {
                        InterventionPresentation.visibleSessionId.value == withoutQuoteId && device.hasObject(By.text("Take a breath."))
                    }
                    assertTrue("Quotes disabled must hide the card and attribution link", !device.hasObject(By.desc(sourceDescription)))
                    ensureVisible(device, By.text("Return to what matters"))
                    capture("quotes-off", theme)
                }
            }
        } catch (failure: Throwable) {
            shell("screencap -p /sdcard/Download/pause-quotes-failure.png")
            throw failure
        } finally {
            device.pressHome()
            instrumentation.runOnMainSync { dependencies.engine.finish(InterventionOutcome.INTERRUPTED) }
            runBlocking { dependencies.preferences.update(savedPreferences) }
            configurator.waitForIdleTimeout = oldIdle
            configurator.uiAutomationFlags = oldFlags
        }
    }

    private fun ensureVisible(device: UiDevice, selector: BySelector) {
        repeat(8) { attempt ->
            try {
                val bounds = device.findObject(selector)?.visibleBounds
                if (bounds != null && bounds.height() > 12 && bounds.top >= 0 && bounds.bottom <= device.displayHeight) return
            } catch (_: StaleObjectException) { /* Re-query after Compose replaces a node. */ }
            val down = attempt < 5
            // The intervention scroll container is inset by page padding. Swipe inside it.
            device.swipe(device.displayWidth / 2, device.displayHeight * (if (down) 75 else 35) / 100,
                device.displayWidth / 2, device.displayHeight * (if (down) 35 else 75) / 100, 30)
            SystemClock.sleep(100)
        }
        error("Quote content did not become visible: $selector")
    }

    private fun clickVisible(device: UiDevice, selector: BySelector) {
        ensureVisible(device, selector)
        val bounds = requireNotNull(device.findObject(selector)).visibleBounds
        device.click(bounds.centerX(), bounds.centerY())
    }

    private fun await(description: String, timeout: Long = 6_000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (predicate()) return } catch (_: StaleObjectException) { /* Try the new node. */ }
            SystemClock.sleep(100)
        }
        assertTrue("Timed out waiting for $description", try { predicate() } catch (_: StaleObjectException) { false })
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(flags).executeShellCommand(command)
    ).bufferedReader().use { it.readText() }
}
