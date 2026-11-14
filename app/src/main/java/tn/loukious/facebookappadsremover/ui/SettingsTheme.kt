package tn.loukious.facebookappadsremover.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tn.loukious.facebookappadsremover.R

/** Spacing and shape tokens shared by every settings screen. */
object Tokens {
    val ScreenMargin = 8.dp
    val RowGap = 2.dp
    val RowPadding = 16.dp
    val RowMinHeight = 72.dp
    val OuterCorner = 24.dp
    val InnerCorner = 4.dp
    val HeaderCardCorner = 32.dp
}

/**
 * Google Sans Flex (SIL OFL 1.1, see OFL-GoogleSansFlex.txt), subset to
 * Latin + Cyrillic. The variable weight axis is driven per FontWeight.
 */
@OptIn(ExperimentalTextApi::class)
private val GoogleSansFlex = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(
            resId = R.font.google_sans_flex,
            weight = weight,
            variationSettings = FontVariation.Settings(
                FontVariation.weight(weight.weight),
                FontVariation.Setting("ROND", 0f),
            ),
        )
    },
)

private val SettingsTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = GoogleSansFlex),
        displayMedium = base.displayMedium.copy(fontFamily = GoogleSansFlex),
        displaySmall = base.displaySmall.copy(fontFamily = GoogleSansFlex),
        headlineLarge = base.headlineLarge.copy(fontFamily = GoogleSansFlex),
        headlineMedium = base.headlineMedium.copy(fontFamily = GoogleSansFlex),
        headlineSmall = base.headlineSmall.copy(fontFamily = GoogleSansFlex),
        titleLarge = base.titleLarge.copy(fontFamily = GoogleSansFlex),
        titleMedium = base.titleMedium.copy(fontFamily = GoogleSansFlex),
        titleSmall = base.titleSmall.copy(fontFamily = GoogleSansFlex),
        bodyLarge = base.bodyLarge.copy(fontFamily = GoogleSansFlex),
        bodyMedium = base.bodyMedium.copy(fontFamily = GoogleSansFlex),
        bodySmall = base.bodySmall.copy(fontFamily = GoogleSansFlex),
        labelLarge = base.labelLarge.copy(fontFamily = GoogleSansFlex),
        labelMedium = base.labelMedium.copy(fontFamily = GoogleSansFlex),
        labelSmall = base.labelSmall.copy(fontFamily = GoogleSansFlex),
    )
}

/** Neutral fallback palette for Android < 12 (no wallpaper colours). */
private val FallbackDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFC6C7C7), onPrimary = Color(0xFF2F3131),
    secondaryContainer = Color(0xFF46464A), onSecondaryContainer = Color(0xFFE3E2E6),
    surface = Color(0xFF131313), onSurface = Color(0xFFE5E2E1),
    surfaceContainerHigh = Color(0xFF2A2A2A), surfaceContainerHighest = Color(0xFF353535),
    onSurfaceVariant = Color(0xFFC7C6CA), outline = Color(0xFF919094),
)
private val FallbackLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF5D5F5F), onPrimary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E2E6), onSecondaryContainer = Color(0xFF46464A),
    surface = Color(0xFFFAF9F9), onSurface = Color(0xFF1B1C1C),
    surfaceContainerHigh = Color(0xFFE9E8E7), surfaceContainerHighest = Color(0xFFE3E2E2),
    onSurfaceVariant = Color(0xFF46474A), outline = Color(0xFF77777A),
)

/** Status card palette: green = working, amber = connecting. */
data class StatusColors(val container: Color, val badge: Color, val onContainer: Color)

fun okColors(dark: Boolean) = if (dark) {
    StatusColors(Color(0xFF173D22), Color(0xFF3FB950), Color(0xFFC8F0D0))
} else {
    StatusColors(Color(0xFFD7F5DD), Color(0xFF1A7F37), Color(0xFF0B3D1A))
}

fun waitColors(dark: Boolean) = if (dark) {
    StatusColors(Color(0xFF3D3214), Color(0xFFD29922), Color(0xFFFBE7B0))
} else {
    StatusColors(Color(0xFFFFF0C2), Color(0xFF9A6700), Color(0xFF4A3300))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = SettingsTypography,
        content = content,
    )
}
