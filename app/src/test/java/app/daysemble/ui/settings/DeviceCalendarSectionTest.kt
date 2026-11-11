package app.daysemble.ui.settings

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import app.daysemble.DaysembleApp
import app.daysemble.data.device.FakeCalendarProvider
import app.daysemble.data.device.FakeCalendarProvider.Companion.calendar
import app.daysemble.data.device.FakeCalendarProvider.Companion.instance
import app.daysemble.ui.theme.DaysembleTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/** Settings > Lists & calendars: calendar permission states and the phone's calendars in the list. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h1400dp-xxhdpi")
class DeviceCalendarSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application
    private val container get() = (app as DaysembleApp).container

    @Before fun setUp() {
        FakeCalendarProvider.install()
        runBlocking { container.clearLocalData() }
        FakeCalendarProvider.calendars = listOf(calendar(1, "Work", account = "work@example.com"), calendar(2, "Family"))
        FakeCalendarProvider.instances = listOf(
            instance(10, 1, "Standup", Instant.now().plusSeconds(3600).toEpochMilli(), Instant.now().plusSeconds(4500).toEpochMilli()),
        )
        FakeCalendarProvider.queries = 0
    }

    private fun show() = compose.setContent { DaysembleTheme { ListsCalendarsRoute(onBack = {}) } }

    private fun waitFor(text: String, substring: Boolean = true) =
        compose.waitUntil(8_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    private fun waitUntilGone(text: String) =
        compose.waitUntil(8_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty() }

    @Test fun deniedOffersAllowAndShowsNoPhoneCalendars() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        runBlocking { container.deviceCalendars.refresh() }
        show()
        waitFor("Calendar access")
        compose.onNodeWithText("Allow").assertExists()
        compose.onAllNodesWithText("Work").assertCountEquals(0)
        check(FakeCalendarProvider.queries == 0) { "nothing may be read without permission" }
    }

    @Test fun grantedListsThePhonesCalendarsWithTheirAccount_andTheSwitchHidesOne() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        runBlocking { container.deviceCalendars.refresh() }
        show()
        waitFor("work@example.com")
        compose.onNodeWithText("Work").assertExists()
        compose.onNodeWithText("Family").assertExists()
        compose.onAllNodesWithText("Allow").assertCountEquals(0)
        compose.onNodeWithText("Allowed").assertExists()
        // Google Tasks are not part of the phone's calendars, and the page says so instead of implying a sync.
        compose.onNodeWithText("Google Tasks aren’t synced", substring = true).assertExists()

        compose.onNodeWithText("Work").performClick()
        compose.waitUntil(8_000) { runBlocking { container.database.calendars().get("device:1")!!.isVisible } == false }
    }

    @Test fun revokingInAndroidSettingsOffersAllowAgainOnReturn() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        runBlocking { container.deviceCalendars.refresh() }
        show()
        waitFor("Allowed", substring = false)
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("Allow", substring = false)
    }

    @Test fun providerFailureShowsAMessageAndRetryRecovers() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        FakeCalendarProvider.failure = IllegalStateException("provider crashed")
        runBlocking { container.deviceCalendars.refresh() }
        show()
        waitFor("Couldn’t read the phone’s calendars")
        compose.onNodeWithText("Try again").assertExists()

        FakeCalendarProvider.failure = null
        compose.onNodeWithText("Try again").performClick()
        waitUntilGone("Couldn’t read the phone’s calendars")
        waitFor("work@example.com")
    }
}
