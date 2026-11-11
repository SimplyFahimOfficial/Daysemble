package app.daysemble.ui.onboarding

import android.Manifest
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import app.daysemble.ui.theme.DaysembleTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * First launch: the calendar-access request is part of the optional last step. It is asked for only
 * when the user taps Allow, once; a "no" is handled quietly (the button stays off, Settings is the
 * way back), and finishing without it never asks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h1000dp-xxhdpi")
class OnboardingCalendarPermissionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application as Application

    /** Answers every permission request with [grant] and records what was asked. */
    private class FakePermissions(var grant: Boolean) : ActivityResultRegistryOwner {
        val asked = mutableListOf<Any?>()
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                asked += input
                @Suppress("UNCHECKED_CAST")
                dispatchResult(requestCode, grant as O)
            }
        }
    }

    private var finished: Boolean = false

    private fun showLastStep(perms: FakePermissions) {
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides perms) {
                DaysembleTheme { OnboardingScreen(initialName = null) { finished = true } }
            }
        }
        compose.onNodeWithText("Get started").performClick()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithText("Phone calendars").assertExists()
    }

    @Test fun tappingAllowAsksOnceForReadCalendarAndShowsAllowed() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val perms = FakePermissions(grant = true)
        showLastStep(perms)

        compose.onAllNodesWithText("Allow")[1].performClick() // the first Allow is for reminders
        assertThat(perms.asked).containsExactly(Manifest.permission.READ_CALENDAR)
        compose.onNodeWithText("Allowed").assertExists()
    }

    @Test fun aDenialIsHandledQuietly_andNeverAsksAgain() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val perms = FakePermissions(grant = false)
        showLastStep(perms)

        compose.onAllNodesWithText("Allow")[1].performClick()
        compose.onNodeWithText("Not allowed").assertExists().assertIsNotEnabled()
        compose.onNodeWithText("No problem. You can allow it later in Settings", substring = true).assertExists()
        // Tapping the disabled button again must not prompt.
        compose.onNodeWithText("Not allowed").performClick()
        assertThat(perms.asked).hasSize(1)
        // Setup can still be finished.
        compose.onNodeWithText("Start using Daysemble").assertIsEnabled().performClick()
        assertThat(finished).isTrue()
    }

    @Test fun alreadyAllowedAsksForNothing() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        val perms = FakePermissions(grant = true)
        showLastStep(perms)

        compose.onNodeWithText("Allowed").assertExists()
        assertThat(perms.asked).isEmpty()
    }

    @Test fun finishingWithoutTappingAllowNeverAsks() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        val perms = FakePermissions(grant = true)
        showLastStep(perms)

        compose.onNodeWithText("Start using Daysemble").performClick()
        assertThat(finished).isTrue()
        assertThat(perms.asked).isEmpty()
    }
}
