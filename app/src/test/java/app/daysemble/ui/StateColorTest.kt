package app.daysemble.ui

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.components.animateStateColor
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A state colour (chip, date number, segment label) is created without an animation, follows a
 * change of its own state through a real animation, ends on the latest target however fast the
 * target flips, and follows a theme change at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StateColorTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val a = Color(0xFF112233)
    private val b = Color(0xFFAA5500)

    private var target by mutableStateOf(a)
    private var mode by mutableStateOf(ThemeMode.LIGHT)
    private var shown = Color.Unspecified

    /** Changes state on the UI thread and lets the composition see it at once. */
    private fun set(change: () -> Unit) = compose.runOnUiThread { change(); androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DaysembleTheme(mode, reducedMotionOverride = false) { shown = animateStateColor(target, tween(200)) }
        }
        compose.mainClock.advanceTimeBy(50)
    }

    @Test fun atRest_itIsTheTarget() {
        show()
        assertThat(shown).isEqualTo(a)
    }

    @Test fun aChangeOfTarget_animates_andArrives() {
        show()
        set { target = b }
        compose.mainClock.advanceTimeBy(100)
        assertThat(shown).isNotEqualTo(a)
        assertThat(shown).isNotEqualTo(b)
        compose.mainClock.advanceTimeBy(400)
        assertThat(shown).isEqualTo(b)
    }

    @Test fun flippingTheTargetQuickly_endsOnTheLastOne() {
        show()
        repeat(4) {
            set { target = b }; compose.mainClock.advanceTimeBy(60)
            set { target = a }; compose.mainClock.advanceTimeBy(60)
        }
        set { target = b }; compose.mainClock.advanceTimeBy(30)
        set { target = a }
        compose.mainClock.advanceTimeBy(600)
        assertThat(shown).isEqualTo(a)
    }

    /** A colour taken from the theme (as most are) jumps with the theme: no frame in between. */
    @Test fun aThemeChange_isFollowedAtOnce_notAnimated() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DaysembleTheme(mode, reducedMotionOverride = false) {
                shown = animateStateColor(if (app.daysemble.ui.theme.DsTheme.colors.isDark) a else b, tween(200))
            }
        }
        compose.mainClock.advanceTimeBy(50)
        assertThat(shown).isEqualTo(b)
        set { mode = ThemeMode.DARK }
        val seen = (0 until 30).map { compose.mainClock.advanceTimeBy(16); shown }.toSet()
        assertThat(seen).containsExactly(a, b)
        assertThat(shown).isEqualTo(a)
    }
}
