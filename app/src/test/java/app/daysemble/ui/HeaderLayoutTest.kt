package app.daysemble.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import app.daysemble.DaysembleApp
import app.daysemble.data.prefs.AppSettings
import app.daysemble.data.prefs.ClockFormat
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.rememberTimePrefs
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.today.TodayRoute
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * The name beside the logo on Today, at the narrowest supported width with 200% text: a long name
 * must end with "…" before the Search and Settings buttons, never overlap or push them away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi", fontScale = 2.0f)
class HeaderLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val c get() = ApplicationProvider.getApplicationContext<DaysembleApp>().container

    private fun show(name: String?) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            DaysembleTheme {
                val prefs = rememberTimePrefs(ClockFormat.H24, null, ZoneId.systemDefault())
                CompositionLocalProvider(
                    LocalTimePrefs provides prefs, LocalAppClock provides c.clock,
                    LocalSnackbar provides remember { SnackbarHostState() },
                    LocalAppSettings provides AppSettings(onboardingDone = true, displayName = name),
                ) { TodayRoute(PaddingValues(), { _, _ -> }, {}, {}, {}) }
            }
        }
        repeat(10) { compose.mainClock.advanceTimeBy(100); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() }
    }

    private fun right(text: String): Dp = compose.onNodeWithText(text, substring = true).getUnclippedBoundsInRoot().right

    @Test fun longName_truncatesBeforeTheActions() {
        val long = "Maximiliana Alexandrina Wolfeschlegel"
        show(long)
        val search = compose.onNodeWithContentDescription("Search").assertIsDisplayed().getUnclippedBoundsInRoot()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
        compose.onNodeWithText(long).assertIsDisplayed()
        assertThat(right(long)).isAtMost(search.left)
    }

    @Test fun noName_showsTheAppName() {
        show(null)
        compose.onNodeWithText("Daysemble").assertIsDisplayed()
        assertThat(right("Daysemble")).isAtMost(compose.onNodeWithContentDescription("Search").getUnclippedBoundsInRoot().left)
    }
}
