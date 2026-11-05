package com.pause.feature.intervention

import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pause.core.PauseTheme
import com.pause.domain.AppCategory
import com.pause.domain.BreathingQuote
import com.pause.domain.InstalledApp
import com.pause.domain.InterventionConfig
import com.pause.domain.InterventionPhase
import com.pause.domain.InterventionSession
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Captures device-rendered screens for visual review; it does not assert unreviewed goldens. */
@RunWith(AndroidJUnit4::class)
class InterventionScreenshotsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun captureBreathing() = capture(InterventionPhase.BREATHING, 3_000L)
    @Test fun captureBreathingDecision() = capture(InterventionPhase.DECISION, 0L)
    @Test fun captureDurationSelectionAtMaximum() = capture(InterventionPhase.SELECTION, 0L, selected = 300)
    @Test fun captureBreathingWithoutQuotes() = capture(InterventionPhase.BREATHING, 3_000L, showQuotes = false)

    private fun capture(phase: InterventionPhase, remaining: Long, selected: Int = 15, showQuotes: Boolean = true) {
        compose.setContent {
            PauseTheme {
                InterventionScreen(
                    InterventionSession("screenshot", InstalledApp("com.google.android.youtube", "YouTube", AppCategory.VIDEO),
                        InterventionConfig(haptics = false, showQuotes = showQuotes), phase, selected, remaining, 1_700_000_000_000L),
                    onDurationChange = {}, onStart = {}, onCancel = {}, onContinue = {},
                    quote = BreathingQuote("zenquotes-docs-sample", "The friend is the man who knows all about you, and still likes you.", "Elbert Hubbard"),
                )
            }
        }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "screenshots").apply { mkdirs() }
        val suffix = if (phase == InterventionPhase.BREATHING && !showQuotes) "-no-quotes" else ""
        val output = File(directory, "pause-${phase.name.lowercase()}$suffix.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(output.length() > 0)
        Log.i("PauseScreenshots", "Saved ${output.absolutePath}")
    }
}
