package app.daysemble.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Raw brand palette, sampled from the supplied Daysemble logos. Feature code must use [DsColors]. */
object Brand {
    val Cream = Color(0xFFFBF8F3)
    val CreamDeep = Color(0xFFF2EEE7)
    val Ink = Color(0xFF0E0E10)
    val InkSoft = Color(0xFF16161A)
    val Coral = Color(0xFFFF6B4A)
    val CoralDeep = Color(0xFFD9442A)
    val CoralLight = Color(0xFFFF7A5C)
    val Violet = Color(0xFF5B3FE0)
    val VioletLight = Color(0xFF9B87FF)
}

/**
 * Semantic colour roles. Every text/background pairing listed in design.md §6 is
 * verified for WCAG AA in ColorContrastTest.
 */
@Immutable
data class DsColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val outlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Coral used for text/icons on background. */
    val accent: Color,
    /** Coral fill (FAB, today marker, brand dot). Pair with [onAccent]. */
    val accentFill: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val violet: Color,
    val violetContainer: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val errorContainer: Color,
    /** Inverted high-emphasis surface (e.g. selected date pill, primary button). */
    val inverse: Color,
    val onInverse: Color,
    val scrim: Color,
)

val LightDsColors = DsColors(
    isDark = false,
    background = Brand.Cream,
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceVariant = Brand.CreamDeep,
    outline = Color(0xFFE4DED4),
    outlineStrong = Color(0xFF8A847A),
    textPrimary = Brand.InkSoft,
    textSecondary = Color(0xFF6B6760),
    textTertiary = Color(0xFF706B63),
    accent = Color(0xFFB8381F),
    accentFill = Brand.CoralDeep,
    onAccent = Color(0xFFFFFFFF),
    accentContainer = Color(0xFFFFE6DF),
    violet = Brand.Violet,
    violetContainer = Color(0xFFECE8FF),
    success = Color(0xFF1F7A50),
    warning = Color(0xFF8F5400),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFCE4E2),
    inverse = Brand.InkSoft,
    onInverse = Brand.Cream,
    scrim = Color(0x66000000),
)

val DarkDsColors = DsColors(
    isDark = true,
    background = Brand.Ink,
    surface = Color(0xFF17171A),
    surfaceRaised = Color(0xFF1E1E22),
    surfaceVariant = Color(0xFF222226),
    outline = Color(0xFF2E2E33),
    outlineStrong = Color(0xFF74747C),
    textPrimary = Color(0xFFF5F1EA),
    textSecondary = Color(0xFFA19D96),
    textTertiary = Color(0xFF908C85),
    accent = Brand.CoralLight,
    accentFill = Brand.CoralLight,
    onAccent = Brand.Ink,
    accentContainer = Color(0xFF3A1D16),
    violet = Brand.VioletLight,
    violetContainer = Color(0xFF231C47),
    success = Color(0xFF5BD69B),
    warning = Color(0xFFF2B44B),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF3B1715),
    inverse = Color(0xFFF5F1EA),
    onInverse = Brand.Ink,
    scrim = Color(0x99000000),
)

/** Event/calendar swatches. The same index renders a tuned tone per theme. */
enum class EventSwatch(val light: Color, val dark: Color) {
    CORAL(Color(0xFFB8381F), Color(0xFFFF7A5C)),
    VIOLET(Color(0xFF5B3FE0), Color(0xFF9B87FF)),
    INDIGO(Color(0xFF2F4FA8), Color(0xFF8AA6FF)),
    TEAL(Color(0xFF0F7A72), Color(0xFF5CCFC4)),
    AMBER(Color(0xFF9A5B00), Color(0xFFF2B44B)),
    ROSE(Color(0xFFB0306A), Color(0xFFFF8CC0)),
    SAGE(Color(0xFF4C7A3A), Color(0xFFA3D58D)),
    GRAPHITE(Color(0xFF55524D), Color(0xFFB9B5AE));

    fun color(dark: Boolean) = if (dark) this.dark else light

    companion object {
        /** Maps an arbitrary ARGB (e.g. a Google calendar colour) to the nearest swatch. */
        fun nearest(argb: Int): EventSwatch {
            val c = Color(argb)
            return entries.minBy { s ->
                val l = s.light
                val dr = l.red - c.red; val dg = l.green - c.green; val db = l.blue - c.blue
                dr * dr * 0.3f + dg * dg * 0.59f + db * db * 0.11f
            }
        }
    }
}

val LocalDsColors = staticCompositionLocalOf { LightDsColors }
