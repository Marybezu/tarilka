package ua.tarilka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF2F6B4F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE8D9),
    onPrimaryContainer = Color(0xFF0B2E1E),
    secondary = Color(0xFF9A5F1C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF7E0C2),
    onSecondaryContainer = Color(0xFF3A2200),
    background = Color(0xFFFBF8F2),
    onBackground = Color(0xFF1D1C19),
    surface = Color(0xFFFBF8F2),
    onSurface = Color(0xFF1D1C19),
    surfaceVariant = Color(0xFFE9E4D9),
    onSurfaceVariant = Color(0xFF4B4840),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F3EB),
    surfaceContainer = Color(0xFFF2EEE5),
    surfaceContainerHigh = Color(0xFFECE8DE),
    surfaceContainerHighest = Color(0xFFE6E2D8),
    outline = Color(0xFF7C786E),
    outlineVariant = Color(0xFFD2CDC1),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DD3B5),
    onPrimary = Color(0xFF00381F),
    primaryContainer = Color(0xFF175036),
    onPrimaryContainer = Color(0xFFCFE8D9),
    secondary = Color(0xFFF0BD7A),
    onSecondary = Color(0xFF462A00),
    secondaryContainer = Color(0xFF5C3C12),
    onSecondaryContainer = Color(0xFFF7E0C2),
    background = Color(0xFF141815),
    onBackground = Color(0xFFE3E3DE),
    surface = Color(0xFF141815),
    onSurface = Color(0xFFE3E3DE),
    surfaceVariant = Color(0xFF3F4540),
    onSurfaceVariant = Color(0xFFBFC6BF),
    surfaceContainerLowest = Color(0xFF0F1310),
    surfaceContainerLow = Color(0xFF1A1F1B),
    surfaceContainer = Color(0xFF1E231F),
    surfaceContainerHigh = Color(0xFF282E29),
    surfaceContainerHighest = Color(0xFF333934),
    outline = Color(0xFF89908A),
    outlineVariant = Color(0xFF3F4540),
    error = Color(0xFFF2B8B5),
)

/** Fixed colors for the four nutrients so they read the same everywhere. */
@Immutable
data class MacroColors(val protein: Color, val fat: Color, val carbs: Color, val fiber: Color, val kcal: Color)

private val LightMacros = MacroColors(
    protein = Color(0xFFB8483D), fat = Color(0xFFC98A16), carbs = Color(0xFF3F6FA3),
    fiber = Color(0xFF5B8A3A), kcal = Color(0xFF2F6B4F),
)
private val DarkMacros = MacroColors(
    protein = Color(0xFFF0A196), fat = Color(0xFFF2C46B), carbs = Color(0xFF9CC0EA),
    fiber = Color(0xFFADD48C), kcal = Color(0xFF9DD3B5),
)

val LocalMacroColors = staticCompositionLocalOf { LightMacros }

private val Type = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Numbers line up in columns. */
val Numbers = TextStyle(fontFamily = FontFamily.Default, fontFeatureSettings = "tnum")

@Composable
fun TarilkaTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    androidx.compose.runtime.CompositionLocalProvider(LocalMacroColors provides if (dark) DarkMacros else LightMacros) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
    }
}
