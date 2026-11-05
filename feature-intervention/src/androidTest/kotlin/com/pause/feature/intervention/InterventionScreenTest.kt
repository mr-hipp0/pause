package com.pause.feature.intervention

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pause.core.PauseTheme
import com.pause.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterventionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedBreathWaitsForContinueBeforeShowingTimeSelection() {
        val state = mutableStateOf(session(InterventionPhase.BREATHING, remaining = 50L))
        var started = false
        compose.setContent {
            PauseTheme {
                InterventionScreen(state.value, {}, { started = true }, {},
                    onContinue = { state.value = state.value.copy(phase = InterventionPhase.SELECTION) }, quote = testQuote)
            }
        }
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(phase = InterventionPhase.DECISION, remainingMillis = 0L) }
        compose.onNodeWithText("Do you still want to open YouTube?").assertIsDisplayed()
        compose.onNodeWithText("Breathing complete.").assertIsDisplayed()
        compose.onNodeWithTag("breathing-circle").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
        val leave = compose.onNodeWithText("Leave").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("intervention-page").fetchSemanticsNode().boundsInRoot
        assertTrue("Both choices should fit the standard viewport", leave.bottom <= page.bottom)
        compose.onNodeWithContentDescription("App usage duration in seconds").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start app session").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(60_000L)
        compose.runOnIdle { assertFalse(started); assertEquals(InterventionPhase.DECISION, state.value.phase) }
        compose.onNodeWithText("Continue").performScrollTo().performClick()
        compose.onNodeWithContentDescription("App usage duration in seconds").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertDoesNotExist()
        compose.runOnIdle { assertFalse(started) }
    }

    @Test fun leaveEndsDecisionWithoutContinuingOrStartingApp() {
        var cancelled = false
        var continued = false
        var started = false
        compose.setContent {
            PauseTheme {
                InterventionScreen(session(InterventionPhase.DECISION), {}, { started = true }, { cancelled = true },
                    onContinue = { continued = true })
            }
        }
        compose.onNodeWithText("Leave").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cancelled); assertFalse(continued); assertFalse(started) }
    }

    @Test fun usageSliderCapsAtFiveMinutesAndStartsAppOnlyOnExplicitAction() {
        val state = mutableStateOf(session(InterventionPhase.SELECTION, selected = 295))
        var started = false
        compose.setContent {
            PauseTheme {
                InterventionScreen(state.value, onDurationChange = { state.value = state.value.copy(selectedSeconds = it) },
                    onStart = { started = true }, onCancel = {}, onContinue = {})
            }
        }
        compose.onNodeWithContentDescription("App usage duration in seconds")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(900f) }
        compose.runOnIdle {
            assertEquals(300, state.value.selectedSeconds)
            assertFalse(started)
        }
        compose.onNodeWithText("5:00").assertIsDisplayed()
        compose.onNodeWithText("+ 5 sec").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Start app session").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(started) }
        compose.onNodeWithText("Start my pause").assertDoesNotExist()
        compose.onNodeWithText("Do you still need\nYouTube?").assertDoesNotExist()
    }

    @Test fun dragReachesConfiguredEndpointsIncludingNonMultiplesOfFive() {
        val state = mutableStateOf(session(InterventionPhase.SELECTION, selected = 15,
            config = InterventionConfig(minUsageSeconds = 7, startUsageSeconds = 15, maxUsageSeconds = 299, haptics = false)))
        compose.setContent {
            PauseTheme {
                InterventionScreen(state.value, onDurationChange = { state.value = state.value.copy(selectedSeconds = it) },
                    onStart = {}, onCancel = {}, onContinue = {})
            }
        }
        val dial = compose.onNodeWithContentDescription("App usage duration in seconds")
        dial.performTouchInput {
            swipe(start = Offset(width * 0.9f, height * 0.5f), end = Offset(width * 0.8f, height * 0.8f), durationMillis = 300)
        }
        compose.runOnIdle { assertEquals(299, state.value.selectedSeconds) }
        compose.onNodeWithText("+ 5 sec").assertIsNotEnabled()
        dial.performTouchInput {
            swipe(start = Offset(width * 0.1f, height * 0.5f), end = Offset(width * 0.2f, height * 0.8f), durationMillis = 300)
        }
        compose.runOnIdle { assertEquals(7, state.value.selectedSeconds) }
        compose.onNodeWithText("− 5 sec").assertIsNotEnabled()
    }

    @Test fun breathingQuoteIsOptionalAndStableAcrossTimerUpdates() {
        val state = mutableStateOf(session(InterventionPhase.BREATHING, remaining = 5_000L))
        compose.setContent {
            PauseTheme { InterventionScreen(state.value, {}, {}, {}, {}, quote = testQuote) }
        }
        compose.onNodeWithContentDescription("Breathing reflection").assertIsDisplayed()
        compose.onNodeWithText(testQuote.text).assertIsDisplayed()
        compose.onNodeWithText("— ${testQuote.author}").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(remainingMillis = 3_000L) }
        compose.onNodeWithText(testQuote.text).assertIsDisplayed()
        compose.onNodeWithText("— ${testQuote.author}").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(config = state.value.config.copy(showQuotes = false)) }
        compose.onNodeWithContentDescription("Breathing reflection").assertDoesNotExist()
        compose.onNodeWithText(testQuote.text).assertDoesNotExist()
        compose.onNodeWithText("— ${testQuote.author}").assertDoesNotExist()
        compose.onNodeWithText("Take a breath.").assertIsDisplayed()
    }

    @Test fun quoteSourceIsExplicitAndMissingQuoteDoesNotBlockBreathing() {
        val quote = mutableStateOf<BreathingQuote?>(null)
        var openedSource = false
        compose.setContent {
            PauseTheme {
                InterventionScreen(session(InterventionPhase.BREATHING, remaining = 5_000L), {}, {}, {}, {},
                    quote = quote.value, onQuoteSource = { openedSource = true })
            }
        }
        compose.onNodeWithContentDescription("Breathing reflection").assertDoesNotExist()
        compose.onNodeWithText("Take a breath.").assertIsDisplayed()
        compose.runOnIdle { quote.value = testQuote }
        compose.onNodeWithContentDescription("Inspirational quotes provided by ZenQuotes API. Open website")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(openedSource) }
    }

    @Test fun quoteRemainsThroughDecisionAndCancellationRemainsReachable() {
        val state = mutableStateOf(session(InterventionPhase.BREATHING, remaining = 5_000L))
        var cancelled = false
        var openedSource = false
        compose.setContent {
            PauseTheme {
                InterventionScreen(state.value, {}, {}, { cancelled = true }, {}, quote = testQuote,
                    onQuoteSource = { openedSource = true })
            }
        }
        compose.onNodeWithText("Return to what matters").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(cancelled); state.value = state.value.copy(phase = InterventionPhase.DECISION, remainingMillis = 0L) }
        compose.onNodeWithText(testQuote.text).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Inspirational quotes provided by ZenQuotes API. Open website")
            .performScrollTo().performClick()
        compose.runOnIdle { assertTrue(openedSource) }
        compose.onNodeWithText("Leave").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(phase = InterventionPhase.SELECTION) }
        compose.onNodeWithContentDescription("Breathing reflection").assertDoesNotExist()
        compose.onNodeWithContentDescription("Start app session").performScrollTo().assertIsDisplayed()
    }

    @Test fun previewEndsWithoutOfferingAnAppLaunch() {
        var finished = false
        val state = mutableStateOf(session(InterventionPhase.DECISION).copy(isPreview = true))
        compose.setContent {
            PauseTheme {
                InterventionScreen(state.value, {}, { finished = true }, {},
                    onContinue = { state.value = state.value.copy(phase = InterventionPhase.SELECTION) })
            }
        }
        compose.onNodeWithText("Continue").assertIsDisplayed().performClick()
        compose.onNodeWithText("Finish preview").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(finished) }
        compose.onNodeWithText("Continue to YouTube").assertDoesNotExist()
    }

    @Test fun breathingUsesViewportAndQuotesOffExpandsTheCircle() {
        val state = mutableStateOf(session(InterventionPhase.BREATHING, remaining = 5_000L))
        compose.setContent { PauseTheme { InterventionScreen(state.value, {}, {}, {}, {}, quote = testQuote) } }
        val withQuotes = compose.onNodeWithTag("breathing-circle").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state.value = state.value.copy(config = state.value.config.copy(showQuotes = false)) }
        val withoutQuotes = compose.onNodeWithTag("breathing-circle").fetchSemanticsNode().boundsInRoot
        val page = compose.onNodeWithTag("intervention-page").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithText("Return to what matters").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Removing the quote must not shrink the breathing circle", withoutQuotes.width >= withQuotes.width)
        assertTrue("Freed space should expand or recenter the breathing circle",
            withoutQuotes.width > withQuotes.width * 1.10f || withoutQuotes.center.y > withQuotes.center.y + 16f)
        assertTrue("The footer should use the lower part of the viewport", cancel.bottom > page.top + page.height * 0.85f)
    }

    @Test fun largeFontsScrollAndTheNextStageStartsAtTheTop() {
        val state = mutableStateOf(session(InterventionPhase.BREATHING, remaining = 5_000L))
        val longQuote = testQuote.copy(
            text = "A deliberately longer interface fixture makes sure the complete quote remains readable when accessibility text is enlarged on a compact display.",
            author = "An Interface Test Author With A Longer Name",
        )
        var cancelled = false
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Box(Modifier.fillMaxWidth().height(480.dp)) {
                    PauseTheme {
                        InterventionScreen(state.value, {}, {}, { cancelled = true },
                            onContinue = { state.value = state.value.copy(phase = InterventionPhase.SELECTION) }, quote = longQuote)
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Breathing reflection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("— ${longQuote.author}").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Inspirational quotes provided by ZenQuotes API. Open website")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Return to what matters").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cancelled); cancelled = false; state.value = state.value.copy(phase = InterventionPhase.DECISION, remainingMillis = 0L) }
        compose.onNodeWithText("Do you still want to open YouTube?").assertIsDisplayed()
        compose.onNodeWithText("Leave").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(cancelled) }
        compose.onNodeWithText("Continue").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("How long would you\nlike to use YouTube?").assertIsDisplayed()
        compose.onNodeWithContentDescription("Start app session").performScrollTo().assertIsDisplayed()
    }

    private val testQuote = BreathingQuote("interface-test", "A short quote used only by the interface test.", "Interface Test Author")

    private fun session(phase: InterventionPhase, selected: Int = 15, remaining: Long = 0,
        config: InterventionConfig = InterventionConfig(haptics = false)) = InterventionSession(
        id = "test-attempt", target = InstalledApp("com.google.android.youtube", "YouTube", AppCategory.VIDEO),
        config = config.normalized(), phase = phase, selectedSeconds = selected,
        remainingMillis = remaining, startedAtMillis = 1_700_000_000_000L,
    )
}
