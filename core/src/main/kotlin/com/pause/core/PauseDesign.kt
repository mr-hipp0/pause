package com.pause.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object PauseDimensions {
    val PagePadding = 24.dp
    val SectionGap = 24.dp
    val CardPadding = 20.dp
    val ControlHeight = 56.dp
    val RowHeight = 72.dp
    val CardShape = RoundedCornerShape(24.dp)
    val ControlShape = RoundedCornerShape(18.dp)
}

@Composable
fun PauseBrand(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(2) {
                Box(Modifier.width(4.dp).height(if (compact) 17.dp else 21.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            }
        }
        Text("pause", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PausePageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null || action != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.offset(x = (-12).dp)) {
                    Icon(PauseIcons.Back, "Back")
                }
                Spacer(Modifier.weight(1f))
                action?.invoke()
            }
        }
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PauseSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** Small, consistent vector family. No font-dependent glyphs or network assets. */
object PauseIcons {
    private fun outline(name: String, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
        }.build()

    val Today by lazy { outline("Today") {
        moveTo(3f, 10f); lineTo(12f, 3f); lineTo(21f, 10f)
        moveTo(5f, 9f); lineTo(5f, 20f); lineTo(10f, 20f); lineTo(10f, 14f)
        lineTo(14f, 14f); lineTo(14f, 20f); lineTo(19f, 20f); lineTo(19f, 9f)
    } }
    val Insights by lazy { outline("Insights") {
        moveTo(4f, 4f); lineTo(4f, 20f); lineTo(21f, 20f)
        moveTo(9f, 15f); lineTo(9f, 11f); moveTo(14f, 15f); lineTo(14f, 7f)
        moveTo(19f, 15f); lineTo(19f, 4f)
    } }
    val Settings by lazy { outline("Settings") {
        moveTo(4f, 7f); lineTo(8f, 7f); moveTo(12f, 7f); lineTo(20f, 7f)
        moveTo(4f, 17f); lineTo(12f, 17f); moveTo(16f, 17f); lineTo(20f, 17f)
        moveTo(8f, 4f); lineTo(12f, 4f); lineTo(12f, 10f); lineTo(8f, 10f); close()
        moveTo(12f, 14f); lineTo(16f, 14f); lineTo(16f, 20f); lineTo(12f, 20f); close()
    } }
    val Back by lazy { outline("Back") {
        moveTo(14f, 5f); lineTo(7f, 12f); lineTo(14f, 19f)
        moveTo(7f, 12f); lineTo(21f, 12f)
    } }
    val ChevronRight by lazy { outline("ChevronRight") {
        moveTo(9f, 6f); lineTo(15f, 12f); lineTo(9f, 18f)
    } }
    val ChevronDown by lazy { outline("ChevronDown") {
        moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f)
    } }
    val Search by lazy { outline("Search") {
        moveTo(16f, 10f)
        curveTo(16f, 13.3f, 13.3f, 16f, 10f, 16f)
        curveTo(6.7f, 16f, 4f, 13.3f, 4f, 10f)
        curveTo(4f, 6.7f, 6.7f, 4f, 10f, 4f)
        curveTo(13.3f, 4f, 16f, 6.7f, 16f, 10f)
        moveTo(14.5f, 14.5f); lineTo(20f, 20f)
    } }
    val Close by lazy { outline("Close") {
        moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f)
    } }
    val Check by lazy { outline("Check") {
        moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 7f)
    } }
    val Shield by lazy { outline("Shield") {
        moveTo(12f, 3f); lineTo(20f, 6f); lineTo(20f, 12f)
        curveTo(20f, 16f, 16f, 20f, 12f, 22f)
        curveTo(8f, 20f, 4f, 16f, 4f, 12f)
        lineTo(4f, 6f); close()
        moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 10f)
    } }
}

