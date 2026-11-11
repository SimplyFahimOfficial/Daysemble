package app.daysemble.ui.theme

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import app.daysemble.R

/** Raw brand palette. Feature code must use [DsColors]. */
object Brand {
    /** The logo tile's cream (artwork only; the app canvas is white). */
    val Cream = Color(0xFFFBF8F3)
    val Ink = Color(0xFF0E0E10)
    /**
     * The one Daysemble orange: the date dot, today markers and the add button, identical in both
     * themes so the dot always reads as the same mark. Orange text uses [DsColors.accent] instead,
     * a deeper (light) or lighter (dark) tone of the same hue that meets 4.5:1.
     */
    val Orange = Color(0xFFF2541F)
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
    /** The canvas behind every screen. */
    val background: Color,
    /** Content surfaces on the canvas: the agenda panel, cards. */
    val surface: Color,
    /** Floating layers: dialogs, sheets, the bottom bar. */
    val surfaceRaised: Color,
    /** Quiet fills for controls on a surface: chips, search fields, segmented tracks. */
    val surfaceVariant: Color,
    /** Hairline separators and borders. */
    val outline: Color,
    val outlineStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Orange used for text and icons (deeper/lighter than [accentFill] so it meets 4.5:1). */
    val accent: Color,
    /**
     * The Daysemble orange ([Brand.Orange]) for markers and glyph-only fills: the date dot, today's
     * ring, the add button. [onAccent] on it is 3.46:1, enough for icons (WCAG 1.4.11) but not for
     * text: orange surfaces that carry text use [accentStrong].
     */
    val accentFill: Color,
    /** Deep orange for orange surfaces with text on them (live countdown chip, accent button). */
    val accentStrong: Color,
    /** Text and icons on [accentFill] (glyphs only) and [accentStrong] (text, 4.5:1). */
    val onAccent: Color,
    /** Orange text on the [inverse] surface (snackbar action). */
    val inverseAccent: Color,
    val accentContainer: Color,
    val violet: Color,
    val violetContainer: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val errorContainer: Color,
    /** Inverted high-emphasis surface (selected date circle, primary button, selected tab). */
    val inverse: Color,
    val onInverse: Color,
    /** Dialog scrims, and the colour of the few soft shadows. */
    val scrim: Color,
)

/** Light: a white canvas, near-black type, light neutral fills and hairline separators. */
val LightDsColors = DsColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF4F4F2),
    outline = Color(0xFFEBEBE8),
    outlineStrong = Color(0xFF8A8A86),
    textPrimary = Color(0xFF121214),
    textSecondary = Color(0xFF5E5E64),
    textTertiary = Color(0xFF6B6B71),
    accent = Color(0xFFBF3A12),
    accentFill = Brand.Orange,
    accentStrong = Color(0xFFBF3A12),
    onAccent = Color(0xFFFFFFFF),
    inverseAccent = Color(0xFFFF8A63),
    accentContainer = Color(0xFFFFEDE6),
    violet = Brand.Violet,
    violetContainer = Color(0xFFECE8FF),
    success = Color(0xFF1F7A50),
    warning = Color(0xFF8F5400),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFFCE4E2),
    inverse = Color(0xFF121214),
    onInverse = Color(0xFFFFFFFF),
    scrim = Color(0x66000000),
)

/** Dark: a near-black canvas with stepped surfaces, so the agenda panel and sheets stay distinct. */
val DarkDsColors = DsColors(
    isDark = true,
    background = Color(0xFF050506),
    surface = Color(0xFF131315),
    surfaceRaised = Color(0xFF1A1A1D),
    surfaceVariant = Color(0xFF1F1F22),
    outline = Color(0xFF2A2A2E),
    outlineStrong = Color(0xFF6E6E76),
    textPrimary = Color(0xFFF4F4F3),
    textSecondary = Color(0xFFA3A3A9),
    textTertiary = Color(0xFF909097),
    accent = Color(0xFFFF8A63),
    accentFill = Brand.Orange,
    accentStrong = Color(0xFFBF3A12),
    onAccent = Color(0xFFFFFFFF),
    inverseAccent = Color(0xFFBF3A12),
    accentContainer = Color(0xFF3A1A10),
    violet = Brand.VioletLight,
    violetContainer = Color(0xFF231C47),
    success = Color(0xFF5BD69B),
    warning = Color(0xFFF2B44B),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF3B1715),
    inverse = Color(0xFFF4F4F3),
    onInverse = Color(0xFF050506),
    scrim = Color(0x99000000),
)

/** Event/calendar swatches. The same index renders a tuned tone per theme. */
enum class EventSwatch(val light: Color, val dark: Color, @param:StringRes val label: Int) {
    CORAL(Color(0xFFB8381F), Color(0xFFFF7A5C), R.string.swatch_coral),
    VIOLET(Color(0xFF5B3FE0), Color(0xFF9B87FF), R.string.swatch_violet),
    INDIGO(Color(0xFF2F4FA8), Color(0xFF8AA6FF), R.string.swatch_indigo),
    TEAL(Color(0xFF0F7A72), Color(0xFF5CCFC4), R.string.swatch_teal),
    AMBER(Color(0xFF9A5B00), Color(0xFFF2B44B), R.string.swatch_amber),
    ROSE(Color(0xFFB0306A), Color(0xFFFF8CC0), R.string.swatch_rose),
    SAGE(Color(0xFF4C7A3A), Color(0xFFA3D58D), R.string.swatch_sage),
    GRAPHITE(Color(0xFF55524D), Color(0xFFB9B5AE), R.string.swatch_graphite);

    fun color(dark: Boolean) = if (dark) this.dark else light

    companion object {
        /** Maps an arbitrary ARGB (e.g. a colour from a phone calendar) to the nearest swatch. */
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
