package app.daysemble.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.theme.LightDsColors
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Frame-by-frame renders of the real app root (Robolectric native graphics, the test clock stepped
 * one frame at a time) during a tab switch and while Settings opens and closes. Every frame must
 * still show content: the share of pixels that aren't the plain background never drops far below
 * what the screens show at rest. The previous fade-through motion let both sides go nearly
 * transparent at once, which showed up here as frames with almost no content (a flash).
 *
 * It proves what is drawn in each frame, not how smooth it is on a device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-mdpi")
class TransitionFramesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val c get() = (compose.activity.application as DaysembleApp).container

    @Before fun setUp() {
        compose.mainClock.autoAdvance = false
        runBlocking {
            compose.activity.application.settingsDataStore.edit { it.clear() }
            c.clearLocalData(); SampleData.load(c)
            c.settings.completeOnboarding("Sam")
        }
        compose.setContent {
            val s by c.settings.settings.collectAsState(initial = null)
            val settings = s ?: return@setContent
            DaysembleTheme(settings.themeMode, settings.logo, reducedMotionOverride = false) { DaysembleRoot(c, settings) }
        }
        settle()
    }

    private fun settle(n: Int = 25) = repeat(n) {
        compose.mainClock.advanceTimeBy(100); Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle()
    }

    private fun frame() { compose.mainClock.advanceTimeByFrame(); shadowOf(Looper.getMainLooper()).idle() }

    /** Share of pixels in the band between [top] and [bottom] (fractions of the height) that differ from the background. */
    private fun ink(top: Float = 0.12f, bottom: Float = 0.80f): Double {
        val px = compose.onRoot().captureToImage().toPixelMap()
        val bg = LightDsColors.background
        var n = 0; var total = 0
        val y0 = (px.height * top).toInt(); val y1 = (px.height * bottom).toInt()
        for (y in y0 until y1 step 2) for (x in 0 until px.width step 2) {
            val p = px[x, y]
            total++
            if (abs(p.red - bg.red) + abs(p.green - bg.green) + abs(p.blue - bg.blue) > 0.06f) n++
        }
        return n.toDouble() / total
    }

    /** Runs [action], then checks every frame for [frames] frames against the content at rest before and after. */
    private fun assertNoBlankFrame(what: String, frames: Int = 24, action: () -> Unit) {
        val before = ink()
        action()
        val during = (0 until frames).map { frame(); ink() }
        settle()
        val after = ink()
        val floor = minOf(before, after) * 0.6
        assertWithMessage("$what: content at rest").that(minOf(before, after)).isGreaterThan(0.02)
        during.forEachIndexed { i, v ->
            assertWithMessage("$what: frame $i shows %.3f of content; at rest %.3f → %.3f".format(v, before, after)).that(v).isAtLeast(floor)
        }
    }

    private fun tab(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and hasParent(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)),
    )

    @Test fun tabSwitch_neverShowsABlankFrame() {
        assertNoBlankFrame("Today → Tasks") { tab("Tasks").performClick() }
        assertNoBlankFrame("Tasks → Calendar") { tab("Calendar").performClick() }
        assertNoBlankFrame("Calendar → Today") { tab("Today").performClick() }
    }

    private fun meanLuminance(): Double {
        val px = compose.onRoot().captureToImage().toPixelMap()
        var sum = 0.0; var n = 0
        for (y in 0 until px.height step 4) for (x in 0 until px.width step 4) {
            val p = px[x, y]; sum += 0.2126 * p.red + 0.7152 * p.green + 0.0722 * p.blue; n++
        }
        return sum / n
    }

    /** Light → Dark cross-fades through in-between frames (the old frame fading over the new one), never a flash. */
    @Test fun themeSwitch_crossFades() {
        val light = meanLuminance()
        runBlocking { c.settings.setThemeMode(app.daysemble.data.prefs.ThemeMode.DARK) }
        val during = (0 until 24).map { frame(); meanLuminance() }
        settle()
        val dark = meanLuminance()
        assertThat(dark).isLessThan(light * 0.5)
        // Every frame lies between the two themes (no white or black flash) …
        during.forEach { assertThat(it).isIn(com.google.common.collect.Range.closed(dark - 0.02, light + 0.02)) }
        // … and some are clearly in between: a cross-fade, not a cut.
        assertThat(during.count { it > dark + 0.1 * (light - dark) && it < light - 0.1 * (light - dark) }).isAtLeast(3)
    }

    @Test fun openingAndClosingSettings_neverShowsABlankFrame() {
        assertNoBlankFrame("open Settings") { compose.onNodeWithContentDescription("Settings").performClick() }
        assertNoBlankFrame("open Appearance") { compose.onAllNodesWithText("Appearance").onFirst().performClick() }
        assertNoBlankFrame("back to Settings") {
            compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        }
        assertNoBlankFrame("back to Today") {
            compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        }
        assertThat(compose.onAllNodesWithText("Your name").fetchSemanticsNodes()).isEmpty()
    }
}
