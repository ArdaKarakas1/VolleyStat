package com.voleybolveveleybol.volleystat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voleybolveveleybol.volleystat.R

// UI Design V1 tokens (04_DESIGN_TOKENS.json). Screens never write a colour or a size literal
// for these roles; they read them from the theme.
val Purple = Color(0xFF6A3DF0)
val PurpleLight = Color(0xFF8B5CF6)
val Lavender = Color(0xFFF5F3FF)
val Ink = Color(0xFF111827)

/** Spacing and size tokens. The base unit is 8 dp. */
object Dimens {
    val screenPadding = 16.dp
    val cardPadding = 16.dp
    val gap = 8.dp
    val gapLarge = 16.dp
    val sectionGap = 24.dp
    val touchTarget = 48.dp
    val buttonHeight = 52.dp
}

/** Roles the Material scheme has no slot for. Status is always icon plus words, never colour alone. */
@Immutable
data class VsColors(
    val divider: Color,
    val positive: Color,
    val positiveContainer: Color,
    val onPositiveContainer: Color,
    val caution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    /** The brand gradient behind the welcome screen and the "new match" card. */
    val heroStart: Color,
    val heroEnd: Color,
    val onHero: Color,
    /** Recording indicator. Red is kept for recording and errors only. */
    val recording: Color,
) {
    val hero: Brush get() = Brush.linearGradient(listOf(heroStart, heroEnd))
}

val LocalVsColors = staticCompositionLocalOf<VsColors> { error("VolleyStatTheme is missing") }

private val LightScheme = lightColorScheme(
    primary = Purple, onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE7FE), onPrimaryContainer = Color(0xFF3B1FA8),
    secondary = PurpleLight, onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1ECFF), onSecondaryContainer = Color(0xFF3B1FA8),
    tertiary = Ink, onTertiary = Color.White,
    background = Lavender, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Color(0xFFF3F4F6), onSurfaceVariant = Color(0xFF596174),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFAF9FF),
    surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF3F4F6),
    surfaceContainerHighest = Color(0xFFE9E3FD),
    // #8A93A6 keeps a 3:1 contrast on white for borders that carry meaning.
    outline = Color(0xFF8A93A6), outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFDC2626), onError = Color.White,
    errorContainer = Color(0xFFFEE2E2), onErrorContainer = Color(0xFF991B1B),
    scrim = Color(0xFF111827),
)

private val LightExtra = VsColors(
    divider = Color(0xFFE5E7EB),
    positive = Color(0xFF16A34A), positiveContainer = Color(0xFFDCFCE7), onPositiveContainer = Color(0xFF166534),
    // The token warning colour (#D97706) is 3.2:1 on white: fine for an icon, too weak for text.
    caution = Color(0xFFD97706), cautionContainer = Color(0xFFFEF3C7), onCautionContainer = Color(0xFF92400E),
    heroStart = Purple, heroEnd = PurpleLight, onHero = Color.White,
    recording = Color(0xFFDC2626),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB9A6FF), onPrimary = Color(0xFF2A1470),
    primaryContainer = Color(0xFF3F2A9E), onPrimaryContainer = Color(0xFFE9E3FD),
    secondary = Color(0xFFA78BFA), onSecondary = Color(0xFF2A1470),
    secondaryContainer = Color(0xFF2B2550), onSecondaryContainer = Color(0xFFE9E3FD),
    tertiary = Color(0xFFF3F4F6), onTertiary = Ink,
    background = Color(0xFF0F1020), onBackground = Color(0xFFF3F4F6),
    surface = Color(0xFF1A1B2E), onSurface = Color(0xFFF3F4F6),
    surfaceVariant = Color(0xFF25273D), onSurfaceVariant = Color(0xFFA8B0C0),
    surfaceContainerLowest = Color(0xFF0B0C18), surfaceContainerLow = Color(0xFF15162A),
    surfaceContainer = Color(0xFF1A1B2E), surfaceContainerHigh = Color(0xFF25273D),
    surfaceContainerHighest = Color(0xFF30324B),
    outline = Color(0xFF8A93A6), outlineVariant = Color(0xFF2B2D45),
    error = Color(0xFFFCA5A5), onError = Color(0xFF7F1D1D),
    errorContainer = Color(0xFF7F1D1D), onErrorContainer = Color(0xFFFEE2E2),
    scrim = Color.Black,
)

private val DarkExtra = VsColors(
    divider = Color(0xFF2B2D45),
    positive = Color(0xFF4ADE80), positiveContainer = Color(0xFF14432A), onPositiveContainer = Color(0xFFBBF7D0),
    caution = Color(0xFFFBBF24), cautionContainer = Color(0xFF4A3508), onCautionContainer = Color(0xFFFDE68A),
    heroStart = Color(0xFF5B34D6), heroEnd = Color(0xFF7C4DEE), onHero = Color.White,
    recording = Color(0xFFEF4444),
)

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

// Tabular figures keep changing numbers (megabytes, percent, scores, table cells) from jumping sideways.
private const val Figures = "tnum"

private val VsTypography = Typography(
    displayLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, fontFeatureSettings = Figures),
    displayMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, fontFeatureSettings = Figures),
    headlineLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, fontFeatureSettings = Figures),
    bodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = Figures),
    bodySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = Figures),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)

private val VsShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),   // buttons, inputs
    large = RoundedCornerShape(16.dp),    // cards
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * One static brand theme in light and dark. Wallpaper-based dynamic colour is deliberately
 * not used: it would repaint the brand and the status colours.
 */
@Composable
fun VolleyStatTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalVsColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = VsTypography,
            shapes = VsShapes,
            content = content,
        )
    }
}
