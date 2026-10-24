package app.daysemble.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.edit
import app.daysemble.DaysembleApp
import app.daysemble.data.SampleData
import app.daysemble.data.prefs.settingsDataStore
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The real app root (onboarding, NavHost, tabs, Settings) with the real settings store, Room DB and
 * sample data, as MainActivity composes it. Checks first-use persistence, the display name, tab
 * state across switches, and back behaviour from Settings. Robolectric, not a device: it proves
 * behaviour and state, not how smooth the animations look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class FirstUseAndNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application as DaysembleApp
    private val c get() = app.container

    @Before fun reset() {
        compose.mainClock.autoAdvance = false
        runBlocking {
            app.settingsDataStore.edit { it.clear() } // fresh install (the store is shared across tests)
            c.clearLocalData(); SampleData.load(c)
        }
    }

    /** Same wiring as MainActivity: wait for settings, then theme + root. */
    private fun showApp() = compose.setContent {
        val s by c.settings.settings.collectAsState(initial = null)
        val settings = s ?: return@setContent
        DaysembleTheme(settings.themeMode, settings.logo, settings.reducedMotionOverride) { DaysembleRoot(c, settings) }
    }

    private fun settle(n: Int = 20) = repeat(n) {
        compose.mainClock.advanceTimeBy(100)
        Thread.sleep(20)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; settle() }

    private fun tab(label: String) =
        compose.onNode(hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))

    private fun gone(text: String) = assertThat(compose.onAllNodesWithText(text).fetchSemanticsNodes()).isEmpty()

    @Test fun firstUse_nameIsSavedWithCompletion_andShownBesideTheLogo() {
        showApp(); settle()
        compose.onNodeWithText("Make room for what matters.").assertIsDisplayed()
        compose.onNodeWithText("Get started").performClick(); settle()
        compose.onNode(hasSetTextAction()).performTextInput("  Ada    Lovelace ")
        compose.onNodeWithText("Continue").performClick(); settle()
        compose.onNodeWithText("Start using Daysemble").performClick(); settle()

        val s = runBlocking { c.settings.current() }
        assertThat(s.onboardingDone).isTrue()
        assertThat(s.displayName).isEqualTo("Ada Lovelace")
        gone("Make room for what matters.")
        compose.onNodeWithText("Ada Lovelace").assertIsDisplayed()
    }

    @Test fun firstUse_skipSetup_finishesWithoutAName() {
        showApp(); settle()
        compose.onNodeWithText("Skip setup").performClick(); settle()
        val s = runBlocking { c.settings.current() }
        assertThat(s.onboardingDone).isTrue()
        assertThat(s.displayName).isNull()
        compose.onNodeWithText("Daysemble").assertIsDisplayed() // app name as the fallback beside the logo
    }

    @Test fun firstUse_backGoesToThePreviousStep() {
        showApp(); settle()
        compose.onNodeWithText("Get started").performClick(); settle()
        compose.onNodeWithText("What should we call you?").assertIsDisplayed()
        back()
        compose.onNodeWithText("Make room for what matters.").assertIsDisplayed()
        assertThat(runBlocking { c.settings.current() }.onboardingDone).isFalse()
    }

    @Test fun returningUser_goesStraightToToday() {
        runBlocking { c.settings.completeOnboarding("Sam") }
        showApp(); settle()
        gone("Make room for what matters.")
        compose.onNodeWithText("Sam").assertIsDisplayed()
        tab("Today").assertIsSelected()
    }

    @Test fun tabs_keepTheirState_andBackReturnsToToday() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        tab("Calendar").performClick(); settle()
        compose.onNodeWithText("Week").performClick(); settle()
        compose.onNodeWithText("Week").assertIsSelected()

        tab("Tasks").performClick(); settle()
        tab("Tasks").assertIsSelected()
        tab("Calendar").performClick(); settle()
        compose.onNodeWithText("Week").assertIsSelected() // calendar view survived the switch

        // Reselecting the current tab, even repeatedly, changes nothing.
        tab("Calendar").performClick(); tab("Calendar").performClick(); settle()
        compose.onNodeWithText("Week").assertIsSelected()

        back()
        tab("Today").assertIsSelected()
    }

    @Test fun settings_nestedPages_returnWithAppBarAndSystemBack() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        // A double tap must open Settings once: one back press returns to Today.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        settle()
        compose.onNodeWithText("Appearance").performClick(); settle()
        compose.onNodeWithText("APP LOGO").assertIsDisplayed()

        compose.onNodeWithContentDescription("Back").performClick(); settle() // app-bar back
        compose.onNodeWithText("Appearance").assertIsDisplayed()
        back() // system back
        gone("Appearance")
        tab("Today").assertIsSelected()
    }

    /**
     * Settings shows the current name in its "Your name" row. Not opened here: a focused text field
     * inside a dialog keeps its cursor animating in the dialog's own window, which the test clock
     * doesn't control, so Robolectric never goes idle. Saving and clearing the name are covered by
     * SettingsRepositoryTest; typing a name is covered by the onboarding test.
     */
    @Test fun settings_showsTheCurrentName() {
        runBlocking { c.settings.completeOnboarding("Sam") }
        showApp(); settle()
        compose.onNodeWithContentDescription("Settings").performClick(); settle()
        compose.onNodeWithText("Your name").assertIsDisplayed()
        compose.onNodeWithText("Sam").assertIsDisplayed()
    }
}
