package com.voleybolveveleybol.volleystat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voleybolveveleybol.volleystat.R

// Brand colours measured from the Voleybol Ve Veleybol logo.
val Cream = Color(0xFFFCF7EB)
val Navy = Color(0xFF162340)
val BurntOrange = Color(0xFF9E491B)

/** Colours the Material scheme has no role for. Status is never shown by colour alone. */
@Immutable
data class VsExtraColors(
    val card: Color,
    val cardLine: Color,
    val positive: Color,
    val positiveContainer: Color,
    val caution: Color,
    val cautionContainer: Color,
    val teamA: Color,
    val onTeamA: Color,
    val teamB: Color,
    /** Background of a band header, when the direction uses one. */
    val band: Color,
    val onBand: Color,
)

/** The few knobs that distinguish one visual direction from another. */
@Immutable
data class VsTokens(
    val titleFont: FontFamily,
    val scoreFont: FontFamily,
    val cardRadius: Dp,
    val controlRadius: Dp,
    /** Draw a brush-stroke rule under screen titles. */
    val titleRule: Boolean,
    /** Put top-level titles and match headers on a dark band. */
    val headerBand: Boolean,
    /** Paint soft colour washes behind screens that hold no data. */
    val washes: Boolean,
)

val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

val LocalVsColors = staticCompositionLocalOf<VsExtraColors> { error("VolleyStatTheme is missing") }
val LocalVsTokens = staticCompositionLocalOf<VsTokens> { error("VolleyStatTheme is missing") }

private val LightScheme = lightColorScheme(
    primary = BurntOrange, onPrimary = Color.White,
    primaryContainer = Color(0xFFF8DEC9), onPrimaryContainer = Color(0xFF4A1F06),
    secondary = Navy, onSecondary = Cream,
    secondaryContainer = Color(0xFFEDE4CF), onSecondaryContainer = Navy,
    tertiary = Navy, onTertiary = Cream,
    background = Cream, onBackground = Navy,
    surface = Cream, onSurface = Navy,
    surfaceVariant = Color(0xFFF1E8D3), onSurfaceVariant = Color(0xFF4A5468),
    surfaceContainerLowest = Color(0xFFFFFDF8), surfaceContainerLow = Color(0xFFFAF4E6),
    surfaceContainer = Color(0xFFF6EEDC), surfaceContainerHigh = Color(0xFFF1E8D3),
    surfaceContainerHighest = Color(0xFFEBE1CA),
    outline = Color(0xFF7A7667), outlineVariant = Color(0xFFDFD4BA),
    error = Color(0xFFA3261C), onError = Color.White,
    errorContainer = Color(0xFFF8DAD5), onErrorContainer = Color(0xFF5C120C),
)

private val LightExtra = VsExtraColors(
    card = Color(0xFFFFFDF8), cardLine = Color(0xFFDFD4BA),
    positive = Color(0xFF2C6238), positiveContainer = Color(0xFFDDEAD9),
    caution = Color(0xFF7A5200), cautionContainer = Color(0xFFF6E5BE),
    teamA = Navy, onTeamA = Cream, teamB = BurntOrange,
    band = Navy, onBand = Cream,
)

// The brand orange is too dark on navy (2.5:1), so dark surfaces use a lighter tone of it.
private val DarkScheme = darkColorScheme(
    primary = Color(0xFFF0A97A), onPrimary = Color(0xFF4A1F06),
    primaryContainer = Color(0xFF6E3212), onPrimaryContainer = Color(0xFFFFDCC5),
    secondary = Color(0xFFDCD3BD), onSecondary = Navy,
    secondaryContainer = Color(0xFF2A3654), onSecondaryContainer = Color(0xFFF3EAD6),
    tertiary = Color(0xFFDCD3BD), onTertiary = Navy,
    background = Color(0xFF0F1A30), onBackground = Color(0xFFF3EAD6),
    surface = Color(0xFF0F1A30), onSurface = Color(0xFFF3EAD6),
    surfaceVariant = Color(0xFF233050), onSurfaceVariant = Color(0xFFBEC4D2),
    surfaceContainerLowest = Color(0xFF0B1425), surfaceContainerLow = Color(0xFF142039),
    surfaceContainer = Color(0xFF182541), surfaceContainerHigh = Color(0xFF1F2D4C),
    surfaceContainerHighest = Color(0xFF283758),
    outline = Color(0xFF8B92A3), outlineVariant = Color(0xFF34425F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A1D15), onErrorContainer = Color(0xFFFFDAD6),
)

private val DarkExtra = VsExtraColors(
    card = Color(0xFF17233D), cardLine = Color(0xFF34425F),
    positive = Color(0xFF9CD3A6), positiveContainer = Color(0xFF1E3D27),
    caution = Color(0xFFEBC26A), cautionContainer = Color(0xFF4A3708),
    teamA = Color(0xFFF3EAD6), onTeamA = Navy, teamB = Color(0xFFF0A97A),
    band = Color(0xFF0B1425), onBand = Color(0xFFF3EAD6),
)

val DefaultTokens = VsTokens(
    titleFont = BarlowCondensed, scoreFont = BarlowCondensed,
    cardRadius = 16.dp, controlRadius = 14.dp,
    titleRule = true, headerBand = false, washes = false,
)

private fun typography(tokens: VsTokens): Typography {
    val base = Typography()
    // Tabular figures keep changing numbers (megabytes, percent, scores) from jumping sideways.
    val figures = "tnum"
    return base.copy(
        displayMedium = TextStyle(fontFamily = tokens.scoreFont, fontWeight = FontWeight.Bold, fontSize = 48.sp, lineHeight = 52.sp, fontFeatureSettings = figures),
        headlineMedium = TextStyle(fontFamily = tokens.titleFont, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
        headlineSmall = TextStyle(fontFamily = tokens.titleFont, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontFamily = tokens.titleFont, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp, fontFeatureSettings = figures),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp, fontFeatureSettings = figures),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp, fontFeatureSettings = figures),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
    )
}

/**
 * One static brand theme in light and dark. Wallpaper-based dynamic colour is deliberately
 * not used: it would repaint the team and status colours.
 */
@Composable
fun VolleyStatTheme(
    dark: Boolean = isSystemInDarkTheme(),
    tokens: VsTokens = DefaultTokens,
    colors: ColorScheme? = null,
    extra: VsExtraColors? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalVsTokens provides tokens,
        LocalVsColors provides (extra ?: if (dark) DarkExtra else LightExtra),
    ) {
        MaterialTheme(
            colorScheme = colors ?: if (dark) DarkScheme else LightScheme,
            typography = typography(tokens),
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(tokens.controlRadius),
                large = RoundedCornerShape(tokens.cardRadius),
                extraLarge = RoundedCornerShape(tokens.cardRadius + 8.dp),
            ),
            content = content,
        )
    }
}
