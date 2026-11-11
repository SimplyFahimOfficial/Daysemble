package app.daysemble.ui.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Settings home: Help & Support first, and no Google account section. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h1700dp-mdpi")
class SettingsHomeTest {
    @get:Rule val compose = createComposeRule()

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test fun helpAndSupportLeadsThePage_andThereIsNoAccountsSection() {
        val opened = mutableListOf<SettingsPage>()
        compose.setContent { DaysembleTheme { SettingsHomeRoute(onBack = {}, onOpen = { opened += it }) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Help & Support").fetchSemanticsNodes().isNotEmpty() }

        // Above every section header and every row.
        val help = top("Help & Support")
        listOf("GENERAL", "Daily schedule & sleep", "Appearance", "Notifications", "PRIVACY & ABOUT", "About").forEach {
            assertThat(help).isLessThan(top(it))
        }
        compose.onAllNodesWithText("ACCOUNTS").assertCountEqualsZero()
        compose.onAllNodesWithText("Google Calendar & Tasks").assertCountEqualsZero()
        compose.onAllNodesWithText("Connect", substring = true).assertCountEqualsZero()

        compose.onNodeWithText("Help & Support").performClick()
        assertThat(opened).containsExactly(SettingsPage.HELP)
    }

    @Test fun noPageIsLeftForAGoogleAccount() {
        assertThat(SettingsPage.entries.map { it.name }).containsNoneOf("ACCOUNTS", "CONFLICTS")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() =
        assertThat(fetchSemanticsNodes()).isEmpty()
}
