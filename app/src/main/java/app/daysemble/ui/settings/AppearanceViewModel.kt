package app.daysemble.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.daysemble.data.prefs.AppSettings
import app.daysemble.data.prefs.SettingsRepository
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.brand.LogoVariant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Abstraction over [app.daysemble.ui.brand.LauncherIconSwitcher] for testability. */
fun interface LauncherIconApplier {
    fun apply(variant: LogoVariant): Boolean
}

class AppearanceViewModel(
    private val settings: SettingsRepository,
    private val launcherIcon: LauncherIconApplier,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val state: StateFlow<AppSettings?> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settings.setThemeMode(mode) }

    fun setReducedMotion(value: Boolean?) = viewModelScope.launch { settings.setReducedMotion(value) }

    fun selectLogo(variant: LogoVariant) = viewModelScope.launch {
        // Persist first: in-app branding follows the preference even if the launcher switch fails.
        settings.setLogo(variant)
        withContext(io) { launcherIcon.apply(variant) }
    }
}
