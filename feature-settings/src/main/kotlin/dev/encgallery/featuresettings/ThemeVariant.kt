package dev.encgallery.featuresettings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color

enum class ThemeVariant(
    @StringRes val nameRes: Int,
    val isDark: Boolean,
    val supportsAccent: Boolean,
    val previewSurface: Color,
    val previewOnSurface: Color,
    val previewAccent: Color
) {
    LIGHT(
        nameRes = R.string.settings_appearance_variant_light,
        isDark = false,
        supportsAccent = true,
        previewSurface = Color(0xFFFAFAFA),
        previewOnSurface = Color(0xFF212121),
        previewAccent = Color(0xFF1565C0)
    ),
    DARK_DEFAULT(
        nameRes = R.string.settings_appearance_variant_dark_default,
        isDark = true,
        supportsAccent = true,
        previewSurface = Color(0xFF1E1E1E),
        previewOnSurface = Color(0xFFE0E0E0),
        previewAccent = Color(0xFF80B4FF)
    ),
    HACKER(
        nameRes = R.string.settings_appearance_variant_hacker,
        isDark = true,
        supportsAccent = false,
        previewSurface = Color(0xFF000000),
        previewOnSurface = Color(0xFF22FF44),
        previewAccent = Color(0xFF22FF44)
    ),
    BLOODY(
        nameRes = R.string.settings_appearance_variant_bloody,
        isDark = true,
        supportsAccent = false,
        previewSurface = Color(0xFF0A0000),
        previewOnSurface = Color(0xFFFFD0D0),
        previewAccent = Color(0xFFB00020)
    ),
    MEDIEVAL(
        nameRes = R.string.settings_appearance_variant_medieval,
        isDark = true,
        supportsAccent = false,
        previewSurface = Color(0xFF15110B),
        previewOnSurface = Color(0xFFE8C98A),
        previewAccent = Color(0xFFB48238)
    ),
    M3_LIGHT(
        nameRes = R.string.settings_appearance_variant_m3_light,
        isDark = false,
        supportsAccent = true,
        previewSurface = Color(0xFFFEF7FF),
        previewOnSurface = Color(0xFF1D1B20),
        previewAccent = Color(0xFF1565C0)
    ),
    M3_DARK(
        nameRes = R.string.settings_appearance_variant_m3_dark,
        isDark = true,
        supportsAccent = true,
        previewSurface = Color(0xFF141218),
        previewOnSurface = Color(0xFFE6E0E9),
        previewAccent = Color(0xFF80B4FF)
    ),
    M3_BLACK(
        nameRes = R.string.settings_appearance_variant_m3_black,
        isDark = true,
        supportsAccent = true,
        previewSurface = Color(0xFF000000),
        previewOnSurface = Color(0xFFE6E0E9),
        previewAccent = Color(0xFF80B4FF)
    );

    val isMaterial3: Boolean
        get() = this == M3_LIGHT || this == M3_DARK || this == M3_BLACK

    companion object {
        val DEFAULT: ThemeVariant = LIGHT

        fun fromNameOrDefault(name: String?): ThemeVariant {
            if (name == null) return DEFAULT
            val parsed = try {
                valueOf(name)
            } catch (_: IllegalArgumentException) {
                DEFAULT
            }

            return if (parsed == MEDIEVAL) DEFAULT else parsed
        }
    }
}
