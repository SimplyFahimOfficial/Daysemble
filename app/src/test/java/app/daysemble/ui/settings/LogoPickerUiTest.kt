package app.daysemble.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.daysemble.ui.brand.LogoVariant
import app.daysemble.ui.theme.DaysembleTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LogoPickerUiTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun showsAllThreeVariantsWithClassicMarkedDefaultAndSelectable() {
        compose.setContent {
            DaysembleTheme {
                var selected by remember { mutableStateOf(LogoVariant.DEFAULT) }
                LogoPicker(selected = selected, onSelect = { selected = it })
            }
        }
        compose.onNodeWithContentDescription("Classic, Default").assertIsSelected()
        compose.onNodeWithContentDescription("Midnight").assertIsNotSelected()
        compose.onNodeWithContentDescription("Outline").assertIsNotSelected()

        compose.onNodeWithContentDescription("Midnight").performClick()
        compose.onNodeWithContentDescription("Midnight").assertIsSelected()
        compose.onNodeWithContentDescription("Classic, Default").assertIsNotSelected()
    }
}
