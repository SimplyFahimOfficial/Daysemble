package app.daysemble.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.theme.DaysembleTheme
import app.daysemble.ui.theme.DsTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Help & Support page: every external row opens its configured URL, Support My Work is the one
 * in-app row, and nothing points at the private repository, the old Ko-fi page or personal accounts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h900dp-xxhdpi")
class HelpScreenTest {
    @get:Rule val compose = createComposeRule()

    private val opened = mutableListOf<String>()
    private var supportOpened = 0

    private fun show(theme: ThemeMode = ThemeMode.LIGHT) {
        compose.setContent {
            DaysembleTheme(themeMode = theme) {
                Column(Modifier.fillMaxWidth().background(DsTheme.colors.background)) {
                    HelpContent(onOpenUrl = { opened += it }, onOpenSupport = { supportOpened++ })
                }
            }
        }
    }

    @Test fun externalRowsOpenTheirConfiguredUrls() {
        show()
        compose.onNodeWithText("Telegram Community").performClick()
        compose.onNodeWithText("Project Website").performClick()
        compose.onNodeWithText("Contact Developer").performClick()

        assertThat(opened).containsExactly(
            "https://t.me/daysemble",
            "https://simplyfahim.pages.dev/daysemble",
            "https://simplyfahim.pages.dev/contactme",
        ).inOrder()
        assertThat(supportOpened).isEqualTo(0)
    }

    @Test fun supportMyWorkIsNativeNavigationNotALink() {
        show()
        compose.onNodeWithText("Support My Work").performClick()

        assertThat(supportOpened).isEqualTo(1)
        assertThat(opened).isEmpty()
    }

    @Test fun showsEverySectionAndNoPrivateOrRetiredLinks() {
        show(ThemeMode.DARK)
        listOf("GET HELP", "PROJECT", "SUPPORT DEVELOPMENT", "DEVELOPER").forEach { compose.onNodeWithText(it).assertExists() }
        listOf("Telegram Community", "Project Website", "Support My Work", "Contact Developer").forEach {
            compose.onNodeWithText(it).assertHasClickAction()
        }
        // The repository is private, so there is no bug-report row; payments are not mixed into help.
        compose.onNodeWithText("Report a Bug").assertDoesNotExist()
        compose.onNodeWithText("Ko-fi", substring = true, ignoreCase = true).assertDoesNotExist()
        compose.onNodeWithText("More Ways to Pay").assertDoesNotExist()
    }
}
