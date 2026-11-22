package io.github.phillhf.iptv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.phillhf.iptv.data.AppSettings

/** Ein Design: Hintergrund, Flächen und zwei Akzentfarben. */
enum class Palette(val label: String, val bg: Long, val surface: Long, val high: Long, val accent: Long, val accent2: Long) {
    NIGHT("Nachtblau", 0xFF070B14, 0xFF0F172A, 0xFF1A2438, 0xFF38BDF8, 0xFF818CF8),
    OLED("Reines Schwarz", 0xFF000000, 0xFF0C0C0E, 0xFF1C1C20, 0xFF38BDF8, 0xFF22D3EE),
    VIOLET("Violett", 0xFF0D0818, 0xFF1A1030, 0xFF2A1C48, 0xFFC084FC, 0xFFF472B6),
    EMERALD("Smaragd", 0xFF04110D, 0xFF0A1F18, 0xFF123227, 0xFF34D399, 0xFFA3E635),
    SUNSET("Abendrot", 0xFF140A08, 0xFF241210, 0xFF3A1E19, 0xFFFB923C, 0xFFF43F5E),
    GRAPHITE("Graphit", 0xFF121315, 0xFF1C1E21, 0xFF2A2D31, 0xFFE2E8F0, 0xFF94A3B8),
}

/** Farben des gewählten Designs. Liest den Zustand, die Oberfläche folgt einem Wechsel also sofort. */
object Klar {
    val palette: Palette get() = runCatching { Palette.valueOf(AppSettings.theme) }.getOrDefault(Palette.NIGHT)
    val Bg: Color get() = Color(palette.bg)
    val Surface: Color get() = Color(palette.surface)
    val SurfaceHigh: Color get() = Color(palette.high)
    val Accent: Color get() = Color(palette.accent)
    val Accent2: Color get() = Color(palette.accent2)

    /** Schrift auf Flächen in der Akzentfarbe. */
    val OnAccent = Color(0xFF02131D)
    val Text = Color(0xFFF1F5F9)
    val TextDim = Color(0xFF94A3B8)
    val Live = Color(0xFFF43F5E)
    val Gold = Color(0xFFFBBF24)
    val AccentBrush: Brush get() = Brush.linearGradient(listOf(Accent, Accent2))
}

private fun scheme() = darkColorScheme(
    primary = Klar.Accent,
    onPrimary = Klar.OnAccent,
    primaryContainer = Klar.SurfaceHigh,
    onPrimaryContainer = Klar.Text,
    secondary = Klar.Accent2,
    onSecondary = Klar.OnAccent,
    secondaryContainer = Klar.SurfaceHigh,
    onSecondaryContainer = Klar.Text,
    background = Klar.Bg,
    onBackground = Klar.Text,
    surface = Klar.Bg,
    onSurface = Klar.Text,
    surfaceVariant = Klar.SurfaceHigh,
    onSurfaceVariant = Klar.TextDim,
    surfaceContainerLow = Klar.Surface,
    surfaceContainer = Klar.Surface,
    surfaceContainerHigh = Klar.SurfaceHigh,
    surfaceContainerHighest = Klar.SurfaceHigh,
    outline = Color(0xFF475569),
    outlineVariant = Color(0xFF334155),
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
    MaterialTheme(colorScheme = scheme(), typography = Type, content = content)
}
