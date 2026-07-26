package dev.encgallery.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.encgallery.featuresettings.AccentColor
import dev.encgallery.featuresettings.ProvideThemeVariant
import dev.encgallery.featuresettings.ThemeVariant

val LocalTabRowColor = staticCompositionLocalOf { Color.Unspecified }
val LocalInactiveTabColor = staticCompositionLocalOf { Color.Unspecified }

@Composable
fun EncGalleryTheme(
    variant: ThemeVariant = ThemeVariant.DEFAULT,
    lightAccent: AccentColor = AccentColor.DEFAULT_LIGHT,
    darkAccent: AccentColor = AccentColor.DEFAULT_DARK,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val scheme = colorSchemeFor(variant, lightAccent, darkAccent, systemDark)
    val typography = typographyFor(variant)
    val accent = if (variant.isDark) darkAccent.primaryFor(true) else lightAccent.primaryFor(false)
    val tabRowColor = tabRowColorFor(variant, scheme, accent)
    val inactiveTabColor = inactiveTabColorFor(variant, scheme, tabRowColor)
    MaterialTheme(
        colorScheme = scheme,
        typography = typography
    ) {

        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Ltr,
            LocalTabRowColor provides tabRowColor,
            LocalInactiveTabColor provides inactiveTabColor
        ) {

            ProvideThemeVariant(variant, content)
        }
    }
}

private fun tabRowColorFor(
    variant: ThemeVariant,
    scheme: ColorScheme,
    accent: Color
): Color = when (variant) {
    ThemeVariant.M3_LIGHT -> tint(M3_LIGHT_TAB_ROW, hueOf(accent), M3_LIGHT_TINT * 1.2f, 1f)
    ThemeVariant.M3_DARK -> scheme.surfaceContainer
    ThemeVariant.M3_BLACK -> M3_BLACK_TAB_ROW
    else -> scheme.surface
}

private fun inactiveTabColorFor(
    variant: ThemeVariant,
    scheme: ColorScheme,
    tabRow: Color
): Color {
    if (!variant.isMaterial3) return scheme.surfaceVariant
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(tabRow.toArgb(), hsv)
    val value: Float
    val saturation: Float
    if (variant.isDark) {
        value = minOf(1f, hsv[2] * 1.7f + 0.045f)
        saturation = hsv[1] * 0.75f
    } else {
        value = hsv[2] * 0.82f
        saturation = hsv[1] * 0.9f
    }
    return Color(AndroidColor.HSVToColor(floatArrayOf(hsv[0], saturation, value)))
}

private fun colorSchemeFor(
    variant: ThemeVariant,
    lightAccent: AccentColor,
    darkAccent: AccentColor,
    systemDark: Boolean
): ColorScheme = when (variant) {
    ThemeVariant.LIGHT -> LightBaseScheme.copy(
        primary = lightAccent.primaryFor(false),
        onPrimary = lightAccent.onPrimaryFor(false)
    )
    ThemeVariant.DARK_DEFAULT -> DarkBaseScheme.copy(
        primary = darkAccent.primaryFor(true),
        onPrimary = darkAccent.onPrimaryFor(true)
    )
    ThemeVariant.HACKER -> HackerScheme
    ThemeVariant.BLOODY -> BloodyScheme
    ThemeVariant.MEDIEVAL -> MedievalScheme
    ThemeVariant.M3_LIGHT -> M3LightScheme
        .copy(
            primary = lightAccent.primaryFor(false),
            onPrimary = lightAccent.onPrimaryFor(false)
        )
        .tintedSurfaces(lightAccent.primaryFor(false), M3_LIGHT_TINT, M3_LIGHT_VALUE_BOOST)
    ThemeVariant.M3_DARK -> M3DarkScheme
        .copy(
            primary = darkAccent.primaryFor(true),
            onPrimary = darkAccent.onPrimaryFor(true)
        )
        .tintedSurfaces(darkAccent.primaryFor(true), M3_DARK_TINT, M3_DARK_VALUE_BOOST)
    ThemeVariant.M3_BLACK -> {
        val accent = darkAccent.primaryFor(true)
        val hue = hueOf(accent)
        M3BlackScheme.copy(
            primary = vivid(accent),
            onPrimary = darkAccent.onPrimaryFor(true),
            outline = tint(M3_BLACK_OUTLINE, hue, 0.40f, 1f),
            outlineVariant = tint(M3_BLACK_OUTLINE_VARIANT, hue, 0.45f, 1f)
        )
    }
}

private const val M3_DARK_TINT = 0.42f
private const val M3_LIGHT_TINT = 0.10f
private const val M3_DARK_VALUE_BOOST = 1.25f
private const val M3_LIGHT_VALUE_BOOST = 1.0f
private const val M3_OUTLINE_TINT_FACTOR = 0.75f
private val M3_BLACK_OUTLINE = Color(0xFFB5B0BC)
private val M3_BLACK_OUTLINE_VARIANT = Color(0xFF3A3840)
private val M3_BLACK_TAB_ROW = Color(0xFF141414)
private val M3_LIGHT_TAB_ROW = Color(0xFFEBE6EE)

private fun hueOf(color: Color): Float {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgb(), hsv)
    return hsv[0]
}

private fun tint(base: Color, hue: Float, saturation: Float, valueBoost: Float): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(base.toArgb(), hsv)
    val v = minOf(1f, hsv[2] * valueBoost)
    return Color(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, v)))
}

private fun vivid(accent: Color): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(accent.toArgb(), hsv)
    hsv[1] = hsv[1] + (1f - hsv[1]) * 0.45f
    hsv[2] = minOf(1f, hsv[2] * 1.06f)
    return Color(AndroidColor.HSVToColor(hsv))
}

private fun ColorScheme.tintedSurfaces(
    accent: Color,
    strength: Float,
    valueBoost: Float
): ColorScheme {
    val h = hueOf(accent)
    return copy(
        background = tint(background, h, strength * 0.85f, valueBoost),
        surface = tint(surface, h, strength * 0.85f, valueBoost),
        surfaceDim = tint(surfaceDim, h, strength * 0.85f, valueBoost),
        surfaceBright = tint(surfaceBright, h, strength * 0.9f, valueBoost),
        surfaceContainerLowest = tint(surfaceContainerLowest, h, strength * 0.85f, valueBoost),
        surfaceContainerLow = tint(surfaceContainerLow, h, strength * 0.95f, valueBoost),
        surfaceContainer = tint(surfaceContainer, h, strength, valueBoost),
        surfaceContainerHigh = tint(surfaceContainerHigh, h, strength * 1.05f, valueBoost),
        surfaceContainerHighest = tint(surfaceContainerHighest, h, strength * 1.1f, valueBoost),
        surfaceVariant = tint(surfaceVariant, h, strength * 1.15f, valueBoost),
        outlineVariant = tint(outlineVariant, h, strength * 1.1f, valueBoost),
        outline = tint(outline, h, strength * M3_OUTLINE_TINT_FACTOR, 1f),
        surfaceTint = tint(surfaceContainer, h, strength, valueBoost)
    )
}

private fun typographyFor(variant: ThemeVariant): Typography {
    if (variant != ThemeVariant.MEDIEVAL) return Typography()
    val base = Typography()
    val gothicFamily = FontFamily.Serif
    fun heavy(style: TextStyle): TextStyle = style.copy(
        fontFamily = gothicFamily,
        fontWeight = FontWeight.Black
    )
    return base.copy(
        displayLarge = heavy(base.displayLarge),
        displayMedium = heavy(base.displayMedium),
        displaySmall = heavy(base.displaySmall),
        headlineLarge = heavy(base.headlineLarge),
        headlineMedium = heavy(base.headlineMedium),
        headlineSmall = heavy(base.headlineSmall),
        titleLarge = heavy(base.titleLarge),
        titleMedium = heavy(base.titleMedium),
        titleSmall = heavy(base.titleSmall)
    )
}
