package app.daysemble.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.daysemble.AppContainer
import app.daysemble.DaysembleApp

/** Creates a screen ViewModel with dependencies pulled from [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> daysembleViewModel(
    key: String? = null,
    crossinline create: (container: AppContainer, handle: SavedStateHandle) -> VM,
): VM = viewModel(
    key = key,
    factory = viewModelFactory {
        initializer {
            val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DaysembleApp
            create(app.container, createSavedStateHandle())
        }
    },
)
