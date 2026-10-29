package app.daysemble.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onFirst
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
import app.daysemble.ui.onboarding.OnboardingScreen
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

    /**
     * System back. The handler writes state outside composition; the change is sent explicitly,
     * because in a long Robolectric run Compose's global snapshot notifier (shared by every test in
     * the JVM) doesn't always pick up such writes, and the screen then never recomposes.
     */
    private fun back() {
        compose.runOnUiThread {
            compose.activity.onBackPressedDispatcher.onBackPressed()
            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        }
        settle()
    }

    /** A bottom-bar tab (the Tasks filters also have a "Today" chip with the Tab role, outside a selectable group). */
    private fun tab(label: String) = compose.onNode(
        hasText(label) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and
            androidx.compose.ui.test.hasParent(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)),
    )

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

    @Test fun settings_doubleTapOnBack_popsOnlyOnePage() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        compose.onNodeWithContentDescription("Settings").performClick(); settle()
        compose.onNodeWithText("Appearance").performClick(); settle()
        compose.onNodeWithText("APP LOGO").assertIsDisplayed()
        // Second tap lands while the first pop is still animating: Appearance's back button is still on screen.
        val appBarBack = compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).onFirst()
        appBarBack.performClick()
        compose.mainClock.advanceTimeBy(50)
        compose.onAllNodes(hasContentDescription("Back") and hasClickAction()).onFirst().performClick()
        settle()
        compose.onNodeWithText("Appearance").assertIsDisplayed() // still on Settings, not back at Today
        gone("APP LOGO")
    }

    /**
     * Back works while a page is still opening. (Pages used to act on taps only once RESUMED, i.e.
     * after the 300 ms open animation, so an early Back or a row tapped right away did nothing.)
     */
    @Test fun settings_backDuringItsOpeningAnimation_closesIt() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.mainClock.advanceTimeBy(64) // a few frames into the open animation
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onNode(hasContentDescription("Back") and hasClickAction()).performClick()
        settle()
        gone("Appearance")
        tab("Today").assertIsSelected()
        // And a row tapped right after a page opens opens its page.
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.mainClock.advanceTimeBy(64)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        compose.onNodeWithText("Appearance").performClick()
        settle()
        compose.onNodeWithText("APP LOGO").assertIsDisplayed()
    }

    private fun monthCell(day: java.time.LocalDate) = compose.onNode(
        hasContentDescription(java.time.format.DateTimeFormatter.ofPattern("MMMM d,", java.util.Locale.US).format(day), substring = true) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
    )

    /**
     * The main screen is no longer a NavHost destination that is disposed under Settings: what you
     * left on Calendar and Tasks (view, selected day, filter) is exactly there when you come back,
     * and while Settings is open the hidden main screen isn't readable (no duplicate nodes).
     */
    @Test fun mainScreenState_survivesSettingsRoundTrip() {
        runBlocking { c.settings.completeOnboarding("Sam") }
        showApp(); settle()
        tab("Tasks").performClick(); settle()
        compose.onNodeWithText("Overdue").performClick(); settle()
        tab("Calendar").performClick(); settle()
        val today = java.time.LocalDate.now()
        val day = today.withDayOfMonth(if (today.dayOfMonth == 13) 14 else 13)
        monthCell(day).performClick(); settle()
        monthCell(day).assertIsSelected()

        compose.onNodeWithContentDescription("Settings").performClick(); settle()
        compose.onNodeWithText("Your name").assertIsDisplayed()
        assertThat(compose.onAllNodesWithText("Sam").fetchSemanticsNodes()).hasSize(1) // only the Settings row
        gone("Month")

        back()
        tab("Calendar").assertIsSelected()
        monthCell(day).assertIsSelected()
        tab("Tasks").performClick(); settle()
        compose.onNodeWithText("Overdue").assertIsSelected()
    }

    /** Taps that land before the previous switch finished still end on the last tab, with only its content readable. */
    @Test fun rapidTabSwitches_endOnTheLastTab() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        tab("Calendar").performClick(); compose.mainClock.advanceTimeBy(30)
        tab("Tasks").performClick(); compose.mainClock.advanceTimeBy(30)
        tab("Today").performClick(); compose.mainClock.advanceTimeBy(30)
        tab("Calendar").performClick(); compose.mainClock.advanceTimeBy(30)
        settle()
        tab("Calendar").assertIsSelected()
        compose.onNodeWithText("Month").assertIsDisplayed()
        gone("Search tasks") // Tasks stays composed in the background but isn't exposed
        back()
        tab("Today").assertIsSelected()
        gone("Month")
    }

    private fun taskSearch() = compose.onNode(hasSetTextAction() and hasContentDescription("Search tasks"))

    private fun softInputVisible(): Boolean {
        val imm = compose.activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        return org.robolectric.Shadows.shadowOf(imm).isSoftInputVisible
    }

    /**
     * Tabs stay composed when hidden, so a focused field in one of them must not keep focus (and the
     * keyboard) after switching away, or typing would go on changing the hidden tab.
     */
    @Test fun switchingTabs_clearsFocusFromTheHiddenTab() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        tab("Tasks").performClick(); settle()
        taskSearch().performClick(); settle()
        taskSearch().performTextInput("invoice"); settle()
        taskSearch().assertIsFocused()

        tab("Calendar").performClick(); settle()
        assertThat(softInputVisible()).isFalse()
        tab("Tasks").performClick(); settle()
        taskSearch().assertIsNotFocused()
        taskSearch().assert(hasText("invoice")) // the query itself is kept
    }

    /** The same for a page opened over the main screen (it stays composed underneath). */
    @Test fun openingAPage_clearsFocusFromTheMainScreen() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        tab("Tasks").performClick(); settle()
        taskSearch().performClick(); settle()
        taskSearch().assertIsFocused()

        compose.onNodeWithContentDescription("Settings").performClick(); settle()
        assertThat(softInputVisible()).isFalse()
        back()
        taskSearch().assertIsNotFocused()
    }

    /** Clearing the main screen's focus must not take focus away from the page that just opened. */
    @Test fun openingSearch_fromAFocusedTaskSearch_focusesTheSearchPage() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        tab("Tasks").performClick(); settle()
        taskSearch().performClick(); settle()
        compose.onNodeWithContentDescription("Search").performClick(); settle()
        compose.onNode(hasSetTextAction() and hasContentDescription("Events, tasks, places")).assertIsFocused()
    }

    /** Selecting days, including rapid taps, and moving between months keeps one selected day. */
    @Test fun calendar_dateSelection_andMonthNavigation() {
        runBlocking { c.settings.completeOnboarding(null) }
        showApp(); settle()
        tab("Calendar").performClick(); settle()
        val first = java.time.LocalDate.now().withDayOfMonth(12)
        listOf(0L, 1L, 2L).forEach { monthCell(first.plusDays(it)).performClick(); compose.mainClock.advanceTimeBy(16) }
        settle()
        monthCell(first.plusDays(2)).assertIsSelected()

        compose.onNodeWithContentDescription("Next").performClick(); settle()
        val next = first.plusMonths(1).withDayOfMonth(1)
        compose.onNodeWithText(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US).format(next)).assertIsDisplayed()
        monthCell(next).assertIsSelected()
        compose.onNodeWithContentDescription("Previous").performClick(); settle()
        compose.onNodeWithText(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.US).format(first)).assertIsDisplayed()
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
