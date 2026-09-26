package app.sunnyside.news.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.sunnyside.news.data.Category
import app.sunnyside.news.data.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFFB35C00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB5),
    onPrimaryContainer = Color(0xFF3A1D00),
    secondary = Color(0xFF2E7D6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9EEE2),
    onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFFC2416B),
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3E0020),
    background = Color(0xFFFFFBF2),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFBF2),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF3E6D6),
    onSurfaceVariant = Color(0xFF51453A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFF6E9),
    surfaceContainer = Color(0xFFFBF0E2),
    surfaceContainerHigh = Color(0xFFF5EADB),
    surfaceContainerHighest = Color(0xFFEFE4D5),
    outline = Color(0xFF847568),
    outlineVariant = Color(0xFFD6C4B3),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB866),
    onPrimary = Color(0xFF5F3000),
    primaryContainer = Color(0xFF874500),
    onPrimaryContainer = Color(0xFFFFDDB5),
    secondary = Color(0xFF8FD5C1),
    onSecondary = Color(0xFF00382D),
    secondaryContainer = Color(0xFF0F5144),
    onSecondaryContainer = Color(0xFFC9EEE2),
    tertiary = Color(0xFFFFB1C6),
    tertiaryContainer = Color(0xFF8A1F48),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF17130F),
    onBackground = Color(0xFFEDE0D4),
    surface = Color(0xFF17130F),
    onSurface = Color(0xFFEDE0D4),
    surfaceVariant = Color(0xFF51453A),
    onSurfaceVariant = Color(0xFFD6C4B3),
    surfaceContainerLowest = Color(0xFF110E0A),
    surfaceContainerLow = Color(0xFF1F1B16),
    surfaceContainer = Color(0xFF241F1A),
    surfaceContainerHigh = Color(0xFF2E2924),
    surfaceContainerHighest = Color(0xFF39342E),
    outline = Color(0xFF9F8E80),
    outlineVariant = Color(0xFF51453A),
)

private val Headline = FontFamily.Serif

private val base = Typography()

val SunnyTypography = Typography(
    displaySmall = base.displaySmall.copy(fontFamily = Headline, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = Headline, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = Headline, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = Headline, fontWeight = FontWeight.Bold, lineHeight = 30.sp),
    titleLarge = base.titleLarge.copy(fontFamily = Headline, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = Headline, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
)

@Composable
fun SunnysideTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = SunnyTypography,
        content = content,
    )
}

/** Accent colour per topic, used for labels and image placeholders. */
fun Category.accent(): Color = when (this) {
    Category.Science -> Color(0xFF5B6CD9)
    Category.Environment -> Color(0xFF2E9D5B)
    Category.Health -> Color(0xFF0F9D8F)
    Category.Animals -> Color(0xFFE07A1F)
    Category.Community -> Color(0xFFD9477A)
    Category.Innovation -> Color(0xFF8A56D6)
    Category.Culture -> Color(0xFFC9533A)
    Category.Sport -> Color(0xFF2C88C9)
}
