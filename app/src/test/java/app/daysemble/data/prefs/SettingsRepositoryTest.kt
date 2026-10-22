package app.daysemble.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.daysemble.ui.brand.LogoVariant
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.DayOfWeek

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun repo(file: File, scope: CoroutineScope) =
        SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })

    @Test
    fun `fresh install uses documented defaults including CLASSIC logo`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val s = repo(File(tmp.root, "a.preferences_pb"), scope).current()
        assertThat(s.logo).isEqualTo(LogoVariant.CLASSIC)
        assertThat(s.themeMode).isEqualTo(ThemeMode.SYSTEM)
        assertThat(s.reducedMotionOverride).isNull()
        assertThat(s.defaultEventReminderMinutes).isEqualTo(10)
        assertThat(s.firstDayOfWeek).isNull()
        scope.cancel()
    }

    @Test
    fun `logo and theme persist across repository instances (app restart)`() = runTest {
        val file = File(tmp.root, "b.preferences_pb")
        val scope1 = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        repo(file, scope1).apply {
            setLogo(LogoVariant.OUTLINE)
            setThemeMode(ThemeMode.DARK)
            setReducedMotion(true)
            setDefaultEventReminder(null)
            setFirstDayOfWeek(DayOfWeek.MONDAY)
        }
        scope1.cancel()

        val scope2 = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val s = repo(file, scope2).current()
        assertThat(s.logo).isEqualTo(LogoVariant.OUTLINE)
        assertThat(s.themeMode).isEqualTo(ThemeMode.DARK)
        assertThat(s.reducedMotionOverride).isTrue()
        assertThat(s.defaultEventReminderMinutes).isNull()
        assertThat(s.firstDayOfWeek).isEqualTo(DayOfWeek.MONDAY)
        scope2.cancel()
    }

    @Test
    fun `reduced motion can return to following the system`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val r = repo(File(tmp.root, "c.preferences_pb"), scope)
        r.setReducedMotion(false)
        assertThat(r.current().reducedMotionOverride).isFalse()
        r.setReducedMotion(null)
        assertThat(r.current().reducedMotionOverride).isNull()
        scope.cancel()
    }

    @Test
    fun `out of range values are clamped`() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val r = repo(File(tmp.root, "d.preferences_pb"), scope)
        r.setDefaultEventDuration(0)
        r.setDefaultTaskReminderTime(5000)
        assertThat(r.current().defaultEventDurationMinutes).isEqualTo(5)
        assertThat(r.current().defaultTaskReminderMinuteOfDay).isEqualTo(24 * 60 - 1)
        scope.cancel()
    }
}
