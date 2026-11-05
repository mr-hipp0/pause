package com.pause.feature.intervention

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pause.core.PauseBrand
import com.pause.core.PauseDimensions
import com.pause.domain.BreathingAnimation
import com.pause.domain.BreathingQuote
import com.pause.domain.InterventionPhase
import com.pause.domain.InterventionSession
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Composable
fun InterventionScreen(
    session: InterventionSession,
    onDurationChange: (Int) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onContinue: () -> Unit,
    quote: BreathingQuote? = null,
    onQuoteSource: () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val elapsed = ((session.config.breathingSeconds * 1000L - session.remainingMillis).coerceAtLeast(0)) / 1000f
    val cycle = (session.config.inhaleSeconds + session.config.holdSeconds + session.config.exhaleSeconds).toFloat()
    val phaseScale = (session.config.breathingSeconds / cycle).coerceAtMost(1f)
    val inhale = session.config.inhaleSeconds * phaseScale
    val hold = session.config.holdSeconds * phaseScale
    val exhale = session.config.exhaleSeconds * phaseScale
    val position = elapsed % (inhale + hold + exhale)
    val breathLabel = when {
        position < inhale -> "Breathe in…"
        position < inhale + hold -> "Hold gently…"
        else -> "Breathe out…"
    }
    val breathProgress = when {
        position < inhale -> position / inhale
        position < inhale + hold -> 1f
        else -> 1f - (position - inhale - hold) / exhale
    }.coerceIn(0f, 1f)
    val tone = remember(session.id, session.config.sound) {
        if (session.config.sound) try { ToneGenerator(AudioManager.STREAM_MUSIC, 28) } catch (_: RuntimeException) { null } else null
    }
    DisposableEffect(tone) { onDispose { tone?.release() } }
    LaunchedEffect(session.id, session.phase, breathLabel) {
        if (session.phase == InterventionPhase.BREATHING) {
            if (session.config.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        AnimatedContent(session.phase, Modifier.fillMaxSize().safeDrawingPadding(), label = "intervention-stage") { phase ->
            // Each stage owns its scroll position; a large-font breathing screen cannot offset selection.
            key(phase) {
                when (phase) {
                    InterventionPhase.BREATHING, InterventionPhase.DECISION -> {
                        val deciding = phase == InterventionPhase.DECISION
                        InterventionPage(
                            minimumHeroDiameter = if (deciding) 112.dp else 200.dp,
                            header = {
                                if (deciding) {
                                    InterventionHeader(session.isPreview, "Do you still want to open ${session.target.name}?",
                                        "Take your time. The next step is your choice.")
                                } else {
                                    InterventionHeader(session.isPreview, "Take a breath.", "A little space before ${session.target.name}.")
                                }
                            },
                            cue = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (deciding) {
                                        Text("Breathing complete.", Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                            style = MaterialTheme.typography.titleLarge)
                                    } else {
                                        Text(breathLabel, style = MaterialTheme.typography.titleLarge)
                                        Text("${ceil(session.remainingMillis / 1000.0).toInt()} seconds remaining", Modifier.padding(top = 6.dp),
                                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            },
                            footer = {
                                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (session.config.showQuotes && quote != null) {
                                        ReflectionCard(quote, onQuoteSource)
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    if (deciding) {
                                        Button(onClick = onContinue,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = PauseDimensions.ControlHeight)
                                                .semantics { contentDescription = "Continue to choose app time" },
                                            shape = PauseDimensions.ControlShape) {
                                            Text("Continue", style = MaterialTheme.typography.labelLarge)
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(onClick = onCancel,
                                            modifier = Modifier.fillMaxWidth().heightIn(min = PauseDimensions.ControlHeight),
                                            shape = PauseDimensions.ControlShape) {
                                            Text("Leave", style = MaterialTheme.typography.labelLarge)
                                        }
                                    } else {
                                        CancelAction(onCancel)
                                    }
                                }
                            },
                            hero = { diameter ->
                                BreathingCircle(if (deciding) 0f else breathProgress, session.config.animation == BreathingAnimation.RIPPLE,
                                    if (deciding) "Breathing complete" else breathLabel, diameter)
                            },
                        )
                    }
                    InterventionPhase.SELECTION -> InterventionPage(
                        minimumHeroDiameter = 188.dp,
                        header = {
                            InterventionHeader(session.isPreview, "How long would you\nlike to use ${session.target.name}?",
                                "Choose a little app time. When it ends, we'll pause together again.", "03 / CHOOSE YOUR TIME")
                        },
                        cue = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    OutlinedButton(onClick = { onDurationChange((session.selectedSeconds - 5).coerceAtLeast(session.config.minUsageSeconds)) },
                                        enabled = session.selectedSeconds > session.config.minUsageSeconds) { Text("− 5 sec") }
                                    OutlinedButton(onClick = { onDurationChange((session.selectedSeconds + 5).coerceAtMost(session.config.maxUsageSeconds.coerceAtMost(300))) },
                                        enabled = session.selectedSeconds < session.config.maxUsageSeconds.coerceAtMost(300)) { Text("+ 5 sec") }
                                }
                                Text("${durationLabel(session.config.minUsageSeconds)} – ${durationLabel(session.config.maxUsageSeconds.coerceAtMost(300))}",
                                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        footer = {
                            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Button(onClick = onStart,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = PauseDimensions.ControlHeight).semantics { contentDescription = "Start app session" },
                                    shape = PauseDimensions.ControlShape) {
                                    Text(if (session.isPreview) "Finish preview" else "Use ${session.target.name} for ${durationLabel(session.selectedSeconds)}",
                                        textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
                                }
                                Text("Leaving ends this app-time session.", Modifier.padding(top = 8.dp), textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                CancelAction(onCancel)
                            }
                        },
                        hero = { diameter ->
                            DurationDial(session.selectedSeconds, session.config.minUsageSeconds, session.config.maxUsageSeconds.coerceAtMost(300), diameter) { value ->
                                if (value != session.selectedSeconds && session.config.haptics) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onDurationChange(value)
                            }
                        },
                    )
                    InterventionPhase.LAUNCHING, InterventionPhase.USING -> {
                        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(24.dp))
                            Text(if (session.isPreview) "Preview complete" else "Opening ${session.target.name}…", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                            Text("Your app time begins when the app is on screen.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

private enum class FrameSlot { HEADER, CUE, FOOTER, HERO }

/**
 * Header, cues, and footer are measured at their real font/quote heights. The hero gets the
 * remaining bounded viewport space. If text plus a useful minimum hero cannot fit, only then
 * does the natural content extend beyond the viewport and the surrounding scroll view takes over.
 * No weighted child is measured inside an unbounded vertical scroll container.
 */
@Composable
private fun InterventionPage(
    minimumHeroDiameter: Dp,
    header: @Composable () -> Unit,
    cue: @Composable () -> Unit,
    footer: @Composable () -> Unit,
    hero: @Composable (Dp) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = PauseDimensions.PagePadding).testTag("intervention-page")) {
        val viewportHeight = maxHeight
        val scrollState = rememberScrollState()
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().fillMaxHeight().align(Alignment.TopCenter).verticalScroll(scrollState)) {
            SubcomposeLayout(Modifier.fillMaxWidth()) { constraints ->
                val width = constraints.maxWidth
                val viewport = viewportHeight.roundToPx()
                val textConstraints = Constraints(minWidth = width, maxWidth = width)
                val headerPlaceable = subcompose(FrameSlot.HEADER) { Box(Modifier.fillMaxWidth()) { header() } }.single().measure(textConstraints)
                val cuePlaceable = subcompose(FrameSlot.CUE) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { cue() } }.single().measure(textConstraints)
                val footerPlaceable = subcompose(FrameSlot.FOOTER) { Box(Modifier.fillMaxWidth()) { footer() } }.single().measure(textConstraints)
                val sectionGap = 16.dp.roundToPx()
                val cueGap = 8.dp.roundToPx()
                val maximumHero = minOf(width, 360.dp.roundToPx())
                val minimumHero = minimumHeroDiameter.roundToPx().coerceAtMost(maximumHero)
                val availableHero = viewport - headerPlaceable.height - footerPlaceable.height - cuePlaceable.height - sectionGap * 2 - cueGap
                val diameter = availableHero.coerceIn(minimumHero, maximumHero)
                val diameterDp = diameter.toDp()
                val heroPlaceable = subcompose(FrameSlot.HERO) { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { hero(diameterDp) } }.single()
                    .measure(textConstraints)
                val centerHeight = heroPlaceable.height + cueGap + cuePlaceable.height
                val naturalHeight = headerPlaceable.height + footerPlaceable.height + centerHeight + sectionGap * 2
                val height = maxOf(viewport, naturalHeight)
                layout(width, height) {
                    headerPlaceable.placeRelative(0, 0)
                    val footerTop = height - footerPlaceable.height
                    footerPlaceable.placeRelative(0, footerTop)
                    val centerRoom = footerTop - headerPlaceable.height - sectionGap * 2
                    val heroTop = headerPlaceable.height + sectionGap + ((centerRoom - centerHeight) / 2).coerceAtLeast(0)
                    heroPlaceable.placeRelative(0, heroTop)
                    cuePlaceable.placeRelative(0, heroTop + heroPlaceable.height + cueGap)
                }
            }
        }
    }
}

@Composable
private fun InterventionHeader(preview: Boolean, title: String, subtitle: String, section: String? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PauseBrand(compact = true)
            Spacer(Modifier.weight(1f))
            Text(if (preview) "PREVIEW" else "YOUR MOMENT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
        section?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(10.dp))
        }
        Text(title, Modifier.fillMaxWidth().semantics { heading() }, style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
        Text(subtitle, Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ReflectionCard(quote: BreathingQuote, onSource: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().semantics { contentDescription = "Breathing reflection" },
        shape = PauseDimensions.CardShape, color = colors.primaryContainer,
        border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.22f)), shadowElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A MOMENT TO REMEMBER", style = MaterialTheme.typography.labelSmall, color = colors.primary, letterSpacing = 1.2.sp)
            Text(quote.text, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 25.sp), color = colors.onPrimaryContainer)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
                    Text("— ${quote.author}", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = colors.onPrimaryContainer)
                }
                Box(Modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Open ZenQuotes website", onClick = onSource)
                    .semantics(mergeDescendants = true) { contentDescription = "Inspirational quotes provided by ZenQuotes API. Open website" }
                    .padding(horizontal = 4.dp), contentAlignment = Alignment.CenterEnd) {
                    Text("via ZenQuotes ↗", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                }
            }
        }
    }
}

@Composable private fun CancelAction(onCancel: () -> Unit) {
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
        Text("Return to what matters", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun BreathingCircle(progress: Float, ripple: Boolean, label: String, preferredDiameter: Dp) {
    val animatedProgress by animateFloatAsState(progress, tween(220), label = "breathing-expansion")
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(preferredDiameter).testTag("breathing-circle").semantics { contentDescription = label }) {
        val radius = size.minDimension * (0.30f + 0.10f * animatedProgress)
        drawCircle(accent.copy(alpha = 0.035f), radius * 1.24f)
        drawCircle(accent.copy(alpha = 0.07f), radius * 1.13f)
        drawCircle(accent.copy(alpha = 0.16f), radius)
        drawCircle(accent.copy(alpha = 0.82f), radius, style = Stroke(1.5.dp.toPx()))
        if (ripple) {
            drawCircle(accent.copy(alpha = 0.12f + animatedProgress * 0.1f), radius * 0.74f, style = Stroke(1.dp.toPx()))
            drawCircle(accent.copy(alpha = 0.16f), radius * 0.5f, style = Stroke(1.dp.toPx()))
        }
    }
}

@Composable
private fun DurationDial(seconds: Int, minimum: Int, maximum: Int, preferredDiameter: androidx.compose.ui.unit.Dp, onChange: (Int) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val currentOnChange by rememberUpdatedState(onChange)
    val fraction = if (maximum == minimum) 1f else (seconds - minimum).toFloat() / (maximum - minimum)
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val diameter = minOf(maxWidth, preferredDiameter)
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier.fillMaxSize()
                    .semantics {
                        contentDescription = "App usage duration in seconds"
                        progressBarRangeInfo = ProgressBarRangeInfo(seconds.toFloat(), minimum.toFloat()..maximum.toFloat())
                        setProgress { value -> currentOnChange(value.roundToInt().coerceIn(minimum, maximum)); true }
                    }
                    .pointerInput(minimum, maximum) {
                        fun select(position: Offset) {
                            val x = position.x - size.width / 2f
                            val y = position.y - size.height / 2f
                            val angle = ((atan2(y, x) * 180f / PI.toFloat() - 135f) + 360f) % 360f
                            val fractionAtTouch = when {
                                angle <= 270f -> angle / 270f
                                angle <= 315f -> 1f
                                else -> 0f
                            }
                            val raw = minimum + fractionAtTouch * (maximum - minimum)
                            val snapped = ((raw / 5f).roundToInt() * 5).coerceIn(minimum, maximum)
                            currentOnChange(snapped)
                        }
                        detectDragGestures(onDragStart = ::select) { change, _ -> change.consume(); select(change.position) }
                    }
            ) {
                val stroke = 7.dp.toPx()
                val inset = 22.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                val topLeft = Offset(inset, inset)
                drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction > 0) drawArc(accent, 135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val radians = (135f + 270f * fraction) * PI.toFloat() / 180f
                val radius = (size.minDimension - inset * 2) / 2f
                val thumb = Offset(center.x + cos(radians) * radius, center.y + sin(radians) * radius)
                drawCircle(accent.copy(alpha = 0.15f), 18.dp.toPx(), thumb)
                drawCircle(accent, 10.dp.toPx(), thumb)
                drawCircle(Color(0xFF171619), 3.dp.toPx(), thumb)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(clockLabel(seconds), fontSize = with(LocalDensity.current) { minOf(56.sp.toPx(), diameter.toPx() / 2.8f).toSp() }, fontWeight = FontWeight.Light, letterSpacing = (-2).sp, maxLines = 1)
                Text("APP TIME", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, letterSpacing = 2.sp)
            }
        }
    }
}



private fun clockLabel(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
private fun durationLabel(seconds: Int): String = when {
    seconds < 60 -> "$seconds sec"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60}m ${seconds % 60}s"
}
