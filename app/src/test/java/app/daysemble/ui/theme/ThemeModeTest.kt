package app.daysemble.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import app.daysemble.data.prefs.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which palette each theme choice produces, with the device in light and in dark mode: System
 * follows the device, Light and Dark ignore it, and the date-dot orange is the same in every case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ThemeModeTest {
    @get:Rule val compose = createComposeRule()

    private fun palette(mode: ThemeMode): DsColors {
        var colors: DsColors? = null
        compose.setContent { DaysembleTheme(themeMode = mode) { colors = DsTheme.colors } }
        compose.waitForIdle()
        return colors!!
    }

    private fun check(mode: ThemeMode, dark: Boolean) {
        val c = palette(mode)
        assertThat(c.isDark).isEqualTo(dark)
        assertThat(c.background).isEqualTo(if (dark) DarkDsColors.background else LightDsColors.background)
        assertThat(c.accentFill).isEqualTo(Brand.Orange)
    }

    @Config(qualifiers = "notnight") @Test fun system_followsALightDevice() = check(ThemeMode.SYSTEM, dark = false)
    @Config(qualifiers = "night") @Test fun system_followsADarkDevice() = check(ThemeMode.SYSTEM, dark = true)
    @Config(qualifiers = "night") @Test fun light_staysWhite_onADarkDevice() = check(ThemeMode.LIGHT, dark = false)
    @Config(qualifiers = "notnight") @Test fun dark_staysBlack_onALightDevice() = check(ThemeMode.DARK, dark = true)

    /**
     * A theme change recomposes the app once or twice, not on every frame of the transition. (The
     * palette used to animate for 300 ms through a static composition local, recomposing every
     * reader of the theme, i.e. the whole app, about 18 times.)
     */
    @Test fun switchingTheme_recomposesOnce_notEveryFrame() {
        compose.mainClock.autoAdvance = false
        var mode by androidx.compose.runtime.mutableStateOf(ThemeMode.LIGHT)
        var compositions = 0
        var last: DsColors? = null
        compose.setContent {
            DaysembleTheme(themeMode = mode) {
                val c = DsTheme.colors
                androidx.compose.runtime.SideEffect { compositions++; last = c }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(c.background))
            }
        }
        compose.mainClock.advanceTimeBy(100)
        val before = compositions
        // Writes outside composition are announced explicitly: in a long Robolectric run the JVM-wide
        // snapshot notifier doesn't always pick them up (see FirstUseAndNavigationTest.back).
        compose.runOnUiThread { mode = ThemeMode.DARK; androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        repeat(30) { // ~500 ms, longer than any theme transition
            compose.mainClock.advanceTimeByFrame()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.runOnUiThread { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        }
        assertThat(last!!.isDark).isTrue()
        assertThat(last!!.background).isEqualTo(DarkDsColors.background) // the final palette, not a blend
        assertThat(compositions - before).isAtMost(2)
    }

    @Test fun palettes_areWhiteAndNearBlack() {
        assertThat(LightDsColors.background).isEqualTo(androidx.compose.ui.graphics.Color.White)
        assertThat(DarkDsColors.background).isEqualTo(androidx.compose.ui.graphics.Color(0xFF050506))
    }
}
