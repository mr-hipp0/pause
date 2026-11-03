package com.pause.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import android.app.Activity
import android.content.ContextWrapper
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.pause.domain.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFFBDB7FF), onPrimary = Color(0xFF26213F),
    primaryContainer = Color(0xFF363147), onPrimaryContainer = Color(0xFFEAE6FF),
    secondary = Color(0xFFA78BFA), onSecondary = Color(0xFF251E39),
    background = Color(0xFF171619), onBackground = Color(0xFFF5F3FA),
    surface = Color(0xFF171619), onSurface = Color(0xFFF5F3FA),
    surfaceVariant = Color(0xFF2B2931), onSurfaceVariant = Color(0xFFB5B0BF),
    surfaceContainerLowest = Color(0xFF121114), surfaceContainerLow = Color(0xFF1D1C21),
    surfaceContainer = Color(0xFF222127), surfaceContainerHigh = Color(0xFF28262E),
    surfaceContainerHighest = Color(0xFF2F2C36), outline = Color(0xFF777180),
    outlineVariant = Color(0xFF3D3A43), error = Color(0xFFFFB4B4),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF625495), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E3FF), onPrimaryContainer = Color(0xFF29213F),
    secondary = Color(0xFF7960B5), background = Color(0xFFF9F7FC),
    surface = Color(0xFFF9F7FC), surfaceVariant = Color(0xFFEDE9F2),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F1F9),
    surfaceContainer = Color(0xFFF0ECF5), surfaceContainerHigh = Color(0xFFEDE9F2),
    surfaceContainerHighest = Color(0xFFE7E2EE),
    onBackground = Color(0xFF222027), onSurface = Color(0xFF222027),
    onSurfaceVariant = Color(0xFF696371), outlineVariant = Color(0xFFD5CEDF),
)

@Composable
fun PauseTheme(mode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && isSystemInDarkTheme()
    val view = LocalView.current
    SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(
            displayLarge = pauseText(64, 72, FontWeight.Light, -2f),
            displayMedium = pauseText(48, 56, FontWeight.Light, -1.5f),
            displaySmall = pauseText(40, 48, FontWeight.Normal, -1f),
            headlineLarge = pauseText(32, 39, FontWeight.Medium, -0.8f),
            headlineMedium = pauseText(28, 35, FontWeight.Medium, -0.5f),
            headlineSmall = pauseText(24, 31, FontWeight.Medium, -0.3f),
            titleLarge = pauseText(22, 29, FontWeight.Medium, -0.3f),
            titleMedium = pauseText(16, 23, FontWeight.Medium),
            titleSmall = pauseText(14, 21, FontWeight.Medium),
            bodyLarge = pauseText(16, 24),
            bodyMedium = pauseText(14, 21),
            bodySmall = pauseText(12, 18),
            labelLarge = pauseText(14, 20, FontWeight.Medium),
            labelMedium = pauseText(12, 18, FontWeight.Medium),
            labelSmall = pauseText(11, 16, FontWeight.Medium, 0.2f),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = PauseDimensions.ControlShape, large = PauseDimensions.CardShape,
            extraLarge = RoundedCornerShape(28.dp)),
        content = content,
    )
}

private fun pauseText(size: Int, height: Int, weight: FontWeight = FontWeight.Normal, spacing: Float = 0f) =
    TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
        lineHeight = height.sp, letterSpacing = spacing.sp)
