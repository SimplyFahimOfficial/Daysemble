package app.daysemble.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import app.daysemble.core.time.Relative
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.max
import kotlin.math.min

/**
 * The live countdown chip ("ends in 10 min") is small text on an orange fill, so it must reach
 * 4.5:1 as rendered. Measured on the drawn pixels: the chip's fill is its most common colour, the
 * text is the pixel furthest from it in contrast (glyph cores are solid at this density).
 */
abstract class CountdownChipContrastBase(private val theme: ThemeMode) {
    @get:Rule val compose = createComposeRule()

    private fun ratio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble(); val lb = b.luminance().toDouble()
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test fun liveCountdownTextMeetsAA() {
        compose.setContent { DaysembleTheme(themeMode = theme) { CountdownChip(Relative.EndsIn(10), Modifier.testTag("chip")) } }
        compose.mainClock.advanceTimeBy(1_000)
        val px = compose.onNodeWithTag("chip").captureToImage().toPixelMap()
        val colors = buildList { for (y in 0 until px.height) for (x in 0 until px.width) add(px[x, y]) }
        val fill = colors.groupingBy { it }.eachCount().maxBy { it.value }.key
        val r = ratio(colors.maxBy { ratio(it, fill) }, fill)
        assertWithMessage("live countdown contrast ($theme) ${"%.2f".format(r)}").that(r).isAtLeast(4.5)
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h200dp-xxhdpi")
class CountdownChipContrastLightTest : CountdownChipContrastBase(ThemeMode.LIGHT)

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h200dp-xxhdpi")
class CountdownChipContrastDarkTest : CountdownChipContrastBase(ThemeMode.DARK)
