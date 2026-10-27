package app.daysemble.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.daysemble.ui.onboarding.OnboardingScreen
import app.daysemble.ui.theme.DaysembleTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The onboarding step and the typed name survive the activity being recreated (rotation, process death). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class OnboardingRestorationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun restoresStepAndName() {
        val restore = StateRestorationTester(compose)
        restore.setContent {
            DaysembleTheme { OnboardingScreen(initialName = null) { _, _ -> } }
        }
        compose.onNodeWithText("Get started").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Ada")
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("What should we call you?").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Ada")).assertIsDisplayed()
    }
}
