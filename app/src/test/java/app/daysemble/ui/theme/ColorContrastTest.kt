package app.daysemble.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/** WCAG 2.x contrast checks for every documented colour pairing in both themes. */
class ColorContrastTest {

    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun check(name: String, fg: Color, bg: Color, min: Double) {
        val r = ratio(fg, bg)
        assertWithMessage("$name contrast ${"%.2f".format(r)} < $min").that(r).isAtLeast(min)
    }

    private fun checkTheme(label: String, c: DsColors) {
        listOf(c.background, c.surface, c.surfaceVariant).forEachIndexed { i, bg ->
            val bgName = listOf("background", "surface", "surfaceVariant")[i]
            check("$label textPrimary/$bgName", c.textPrimary, bg, 4.5)
            check("$label textSecondary/$bgName", c.textSecondary, bg, 4.5)
            check("$label accent/$bgName", c.accent, bg, 4.5)
            check("$label error/$bgName", c.error, bg, 4.5)
            check("$label success/$bgName", c.success, bg, 4.5)
            check("$label warning/$bgName", c.warning, bg, 4.5)
            check("$label violet/$bgName", c.violet, bg, 4.5)
            // Tertiary is used for small meta text (hour labels, end times), so it must meet AA too.
            check("$label textTertiary/$bgName", c.textTertiary, bg, 4.5)
            // Essential non-text UI boundaries (checkbox ring, unchecked switch): WCAG 1.4.11.
            check("$label outlineStrong/$bgName (non-text)", c.outlineStrong, bg, 3.0)
        }
        check("$label onInverse/inverse", c.onInverse, c.inverse, 4.5)
        // onAccent is used for icons and bold labels on the coral fill (3:1 for UI glyphs/large text).
        check("$label onAccent/accentFill", c.onAccent, c.accentFill, 3.0)
        check("$label error/errorContainer", c.error, c.errorContainer, 4.5)
        check("$label textPrimary/accentContainer", c.textPrimary, c.accentContainer, 4.5)
        check("$label accentFill/background (non-text)", c.accentFill, c.background, 3.0)
    }

    @Test fun lightTheme() = checkTheme("light", LightDsColors)

    @Test fun darkTheme() = checkTheme("dark", DarkDsColors)

    @Test
    fun eventSwatchesAreLegibleAsTextOnSurfaces() {
        EventSwatch.entries.forEach { s ->
            check("swatch ${s.name} light", s.light, LightDsColors.surface, 4.5)
            check("swatch ${s.name} dark", s.dark, DarkDsColors.surface, 4.5)
        }
    }
}
