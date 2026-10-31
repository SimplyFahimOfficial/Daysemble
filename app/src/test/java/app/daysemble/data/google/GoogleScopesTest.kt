package app.daysemble.data.google

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.daysemble.data.db.SyncService
import app.daysemble.data.google.auth.AccountStore
import app.daysemble.data.google.auth.GoogleAuthManager
import app.daysemble.data.google.auth.GoogleAuthManager.Companion.SCOPE_CALENDAR_EVENTS
import app.daysemble.data.google.auth.GoogleAuthManager.Companion.SCOPE_CALENDAR_LIST
import app.daysemble.data.google.auth.GoogleAuthManager.Companion.SCOPE_TASKS
import app.daysemble.data.google.calendar.CalendarApi
import app.daysemble.data.google.calendar.CalendarSyncer
import app.daysemble.data.google.sync.SyncManager
import app.daysemble.data.google.tasks.TasksApi
import app.daysemble.data.google.tasks.TasksSyncer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The OAuth scopes asked for, partial consent, and a token that lacks a scope. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GoogleScopesTest : SyncTestBase() {
    @get:Rule val tmp = TemporaryFolder()
    private val acct = "me@example.com"

    @Test fun `only the narrow calendar scopes are requested, never full calendar access`() {
        val all = GoogleAuthManager.scopesFor(SyncService.entries.toSet())
        assertThat(all).containsExactly(SCOPE_CALENDAR_EVENTS, SCOPE_CALENDAR_LIST, SCOPE_TASKS, "email")
        assertThat(all).doesNotContain("https://www.googleapis.com/auth/calendar")
        assertThat(GoogleAuthManager.scopesFor(setOf(SyncService.TASKS))).containsExactly(SCOPE_TASKS, "email")
    }

    @Test fun `a service counts as allowed only with all of its scopes`() {
        assertThat(GoogleAuthManager.grantedServices(listOf(SCOPE_CALENDAR_EVENTS, SCOPE_CALENDAR_LIST, SCOPE_TASKS)))
            .containsExactly(SyncService.CALENDAR, SyncService.TASKS)
        assertThat(GoogleAuthManager.grantedServices(listOf(SCOPE_TASKS, "email"))).containsExactly(SyncService.TASKS)
        // Events without the calendar list can't sync: the calendar list is read first.
        assertThat(GoogleAuthManager.grantedServices(listOf(SCOPE_CALENDAR_EVENTS))).isEmpty()
    }

    private fun accounts() = AccountStore(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { File(tmp.root, "acct.preferences_pb") },
    )

    private fun manager(accounts: AccountStore, auth: GoogleAuthManager) = SyncManager(
        db, accounts, auth,
        CalendarSyncer(db, CalendarApi(http, base("/calendar/v3/")), clock),
        TasksSyncer(db, TasksApi(http, base("/tasks/v1/")), clock),
        clock, onLocalDataChanged = {},
    )

    @Test fun `connecting with only tasks allowed turns calendar sync off and never calls the calendar API`() = runTest {
        router.on("GET", "/tasks/v1/users/@me/lists") { json(body = """{"items":[]}""") }
        val accounts = accounts()
        val auth = GoogleAuthManager(ApplicationProvider.getApplicationContext())
        val m = manager(accounts, auth)
        m.connect(acct, granted = setOf(SyncService.TASKS), requested = SyncService.entries.toSet())
        val state = accounts.current()
        assertThat(state.calendarEnabled).isFalse()
        assertThat(state.tasksEnabled).isTrue()
        assertThat(auth.services).containsExactly(SyncService.TASKS)

        m.syncNow()
        assertThat(router.requests.none { it.url.encodedPath.startsWith("/calendar/") }).isTrue()
        assertThat(router.count("GET", "/tasks/v1/users/@me/lists")).isEqualTo(1)
    }

    @Test fun `allowing a service later only changes that service`() = runTest {
        val accounts = accounts()
        val m = manager(accounts, GoogleAuthManager(ApplicationProvider.getApplicationContext()))
        m.connect(acct, granted = setOf(SyncService.TASKS), requested = SyncService.entries.toSet())
        // The user turns Google Calendar on and allows it this time.
        m.connect(acct, granted = SyncService.entries.toSet(), requested = SyncService.entries.toSet())
        assertThat(accounts.current().services).containsExactly(SyncService.CALENDAR, SyncService.TASKS)
        // Turning Calendar on but declining it again leaves Tasks on.
        m.setCalendarEnabled(false)
        m.connect(acct, granted = setOf(SyncService.TASKS), requested = setOf(SyncService.CALENDAR, SyncService.TASKS))
        assertThat(accounts.current().services).containsExactly(SyncService.TASKS)
    }

    @Test fun `a token is cached only if it covers every synced service`() {
        val both = SyncService.entries.toSet()
        assertThat(GoogleAuthManager.coversSynced(granted = both, synced = setOf(SyncService.TASKS))).isTrue()
        // Requested before Calendar was turned on: it lacks Calendar's scopes, so it isn't kept.
        assertThat(GoogleAuthManager.coversSynced(granted = setOf(SyncService.TASKS), synced = both)).isFalse()
    }

    @Test fun `disconnecting also revokes the full calendar scope earlier versions asked for`() {
        assertThat(GoogleAuthManager.scopesToRevoke()).containsAtLeast(
            GoogleAuthManager.LEGACY_SCOPE_CALENDAR, SCOPE_CALENDAR_EVENTS, SCOPE_CALENDAR_LIST, SCOPE_TASKS,
        )
    }

    @Test fun `authorizing one more service keeps the other service's sync status`() = runTest {
        val accounts = accounts()
        val m = manager(accounts, GoogleAuthManager(ApplicationProvider.getApplicationContext()))
        m.connect(acct, granted = setOf(SyncService.TASKS), requested = SyncService.entries.toSet())
        db.sync().upsertState(app.daysemble.data.db.SyncStateEntity(SyncService.TASKS, lastSuccessAt = 42, lastError = "rejected_changes"))
        db.sync().upsertState(app.daysemble.data.db.SyncStateEntity(SyncService.CALENDAR, needsReauth = true))
        // The user turns Google Calendar on and allows it.
        m.connect(acct, granted = SyncService.entries.toSet(), requested = SyncService.entries.toSet() - SyncService.TASKS)
        assertThat(db.sync().state(SyncService.TASKS)!!.lastSuccessAt).isEqualTo(42)
        assertThat(db.sync().state(SyncService.CALENDAR)).isNull()
        // Another account starts clean.
        m.connect("other@example.com")
        assertThat(db.sync().state(SyncService.TASKS)).isNull()
    }

    @Test fun `reconnecting with both services off leaves them off`() = runTest {
        val accounts = accounts()
        val m = manager(accounts, GoogleAuthManager(ApplicationProvider.getApplicationContext()))
        m.connect(acct)
        m.setCalendarEnabled(false); m.setTasksEnabled(false)
        // Reconnect asks Google for both (it needs some scope) but applies the answer to neither.
        m.connect(acct, granted = SyncService.entries.toSet(), requested = emptySet())
        assertThat(accounts.current().services).isEmpty()
    }

    @Test fun `a 403 for a missing scope asks for consent instead of rejecting the change`() = runTest {
        router.on("GET", "/calendar/v3/users/me/calendarList") {
            json(403, """{"error":{"code":403,"message":"Request had insufficient authentication scopes.",
                "errors":[{"reason":"insufficientPermissions"}],"status":"PERMISSION_DENIED",
                "details":[{"reason":"ACCESS_TOKEN_SCOPE_INSUFFICIENT"}]}}""")
        }
        router.on("GET", "/calendar/v3/users/me/settings") { json(403, """{"error":{"errors":[{"reason":"forbidden"}]}}""") }
        val scope = runCatching { http.get(base("/calendar/v3/users/me/calendarList").toHttpUrl()) }.exceptionOrNull()
        assertThat(scope).isInstanceOf(ApiError.Auth::class.java)
        val other = runCatching { http.get(base("/calendar/v3/users/me/settings").toHttpUrl()) }.exceptionOrNull()
        assertThat(other).isInstanceOf(ApiError.Client::class.java)
    }
}
