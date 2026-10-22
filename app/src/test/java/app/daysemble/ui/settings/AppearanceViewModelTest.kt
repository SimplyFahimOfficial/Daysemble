package app.daysemble.ui.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.brand.LogoVariant
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {

    @get:Rule val tmp = TemporaryFolder()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `selecting a logo persists it and switches the launcher alias`() = runTest(dispatcher) {
        val scope = CoroutineScope(dispatcher)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "s.preferences_pb") })
        val applied = mutableListOf<LogoVariant>()
        val vm = AppearanceViewModel(repo, { applied += it; true }, io = dispatcher)

        vm.selectLogo(LogoVariant.MIDNIGHT)

        assertThat(repo.current().logo).isEqualTo(LogoVariant.MIDNIGHT)
        assertThat(applied).containsExactly(LogoVariant.MIDNIGHT)
        scope.cancel()
    }

    @Test
    fun `logo preference persists even if the launcher switch fails`() = runTest(dispatcher) {
        val scope = CoroutineScope(dispatcher)
        val repo = SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "t.preferences_pb") })
        val vm = AppearanceViewModel(repo, { false }, io = dispatcher)
        vm.selectLogo(LogoVariant.OUTLINE)
        vm.setTheme(ThemeMode.LIGHT)
        assertThat(repo.current().logo).isEqualTo(LogoVariant.OUTLINE)
        assertThat(repo.current().themeMode).isEqualTo(ThemeMode.LIGHT)
        scope.cancel()
    }
}
