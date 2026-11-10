package app.daysemble.ui.settings

import android.Manifest
import android.provider.CalendarContract
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.Lifecycle
import app.daysemble.data.device.FakeCalendarProvider
import app.daysemble.ui.theme.DaysembleTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Settings → Lists & calendars: device calendar permission states and preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class DeviceCalendarSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application

    @Before fun setUp() {
        val today = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant()
        val soon = Instant.now().plus(1, ChronoUnit.HOURS)
        FakeCalendarProvider.failure = null
        FakeCalendarProvider.rows = listOf(
            arrayOf(1L, "Public holiday", today.toEpochMilli(), today.plus(2, ChronoUnit.DAYS).toEpochMilli(), 1),
            arrayOf(2L, "Dentist", soon.toEpochMilli(), soon.plus(1, ChronoUnit.HOURS).toEpochMilli(), 0),
        )
        Robolectric.setupContentProvider(FakeCalendarProvider::class.java, CalendarContract.AUTHORITY)
    }

    private fun show() = compose.setContent { DaysembleTheme { ListsCalendarsRoute(onBack = {}) } }

    private fun waitFor(text: String, substring: Boolean = true) =
        compose.waitUntil(5_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test fun deniedOffersAllowAndReadsNothing() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        FakeCalendarProvider.lastUri = null
        show()
        waitFor("Calendar access")
        compose.onNodeWithText("Allow").assertExists()
        compose.onAllNodesWithText("Upcoming device events").assertCountEquals(0)
        check(FakeCalendarProvider.lastUri == null)
    }

    @Test fun grantedShowsOccurrencesIncludingAllDay() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        show()
        waitFor("Dentist")
        compose.onNodeWithText("Public holiday").assertExists()
        compose.onNodeWithText("All day", substring = true).assertExists()
        compose.onAllNodesWithText("Allow").assertCountEquals(0)
    }

    @Test fun revokingInAndroidSettingsHidesThePreviewOnReturn() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        show()
        waitFor("Dentist")
        shadowOf(app).denyPermissions(Manifest.permission.READ_CALENDAR)
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitFor("Allow", substring = false)
        compose.onAllNodesWithText("Dentist").assertCountEquals(0)
    }

    @Test fun providerFailureShowsErrorAndRetry() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CALENDAR)
        FakeCalendarProvider.failure = IllegalStateException("provider crashed")
        show()
        waitFor("Couldn’t read device calendars")
        compose.onNodeWithText("Try again").assertExists()
    }
}
