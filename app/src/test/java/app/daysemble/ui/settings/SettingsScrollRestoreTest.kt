package app.daysemble.ui.settings

import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Coming back to a Settings page rebuilds it from its saved state (the back stack keeps the state,
 * not the composition). On a device the rebuilt page is laid out before its rows arrive from the
 * database; a ScrollState is clamped to the content height at layout, so the empty first layout
 * used to reset the position to the top. [SettingsBody] waits for the data. Here the rows arrive
 * only after the rebuilt page has been laid out, as on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h480dp-mdpi")
class SettingsScrollRestoreTest {
    @get:Rule val compose = createComposeRule()

    private fun settle(n: Int = 5) = repeat(n) {
        compose.mainClock.advanceTimeBy(100); shadowOf(Looper.getMainLooper()).idle()
    }

    private val scroller = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
        SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)

    private fun position() = compose.onNode(scroller).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test fun rebuiltPage_keepsItsScrollPosition_whenItsRowsArriveAfterLayout() {
        compose.mainClock.autoAdvance = false
        val rows = MutableStateFlow<List<String>?>((1..40).map { "Row $it" })
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            DaysembleTheme {
                val data by rows.collectAsState()
                Column(Modifier.fillMaxSize()) {
                    SettingsBody(loaded = data != null) {
                        data.orEmpty().forEach { Text(it, Modifier.height(48.dp)) }
                    }
                }
            }
        }
        settle()
        val node = compose.onNode(scroller).fetchSemanticsNode()
        compose.runOnUiThread { node.config[SemanticsActions.ScrollBy].action?.invoke(0f, 900f) }
        settle()
        val scrolled = position()
        assertThat(scrolled).isGreaterThan(500f)

        // Back to the page: rebuilt from saved state, rows not loaded yet, laid out, then the rows come.
        rows.value = null
        restoration.emulateSavedInstanceStateRestore()
        settle()
        rows.value = (1..40).map { "Row $it" }
        settle()
        assertThat(position()).isWithin(1f).of(scrolled)
    }
}
