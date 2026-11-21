package io.github.phillhf.iptv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** Farben von Klar TV: tiefes Nachtblau, Akzent Himmelblau mit Übergang zu Violett. */
object Klar {
    val Bg = Color(0xFF070B14)
    val Surface = Color(0xFF0F172A)
    val SurfaceHigh = Color(0xFF1A2438)
    val Accent = Color(0xFF38BDF8)
    val Accent2 = Color(0xFF818CF8)
    val Text = Color(0xFFF1F5F9)
    val TextDim = Color(0xFF94A3B8)
    val Live = Color(0xFFF43F5E)
    val Gold = Color(0xFFFBBF24)
    val AccentBrush = Brush.linearGradient(listOf(Accent, Accent2))
}

private val Scheme = darkColorScheme(
    primary = Klar.Accent,
    onPrimary = Color(0xFF02131D),
    primaryContainer = Color(0xFF0C3A57),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondary = Klar.Accent2,
    onSecondary = Color(0xFF0B0D2A),
    secondaryContainer = Color(0xFF1E2A4A),
    onSecondaryContainer = Color(0xFFE0E7FF),
    background = Klar.Bg,
    onBackground = Klar.Text,
    surface = Klar.Bg,
    onSurface = Klar.Text,
    surfaceVariant = Klar.SurfaceHigh,
    onSurfaceVariant = Klar.TextDim,
    surfaceContainer = Klar.Surface,
    surfaceContainerHigh = Klar.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF243049),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1F2A3D),
    error = Color(0xFFFB7185),
)

private val Base = Typography()

private val Type = Base.copy(
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = Base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
)

@Composable
fun KlarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
