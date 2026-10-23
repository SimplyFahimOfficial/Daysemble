package app.daysemble.data.google.auth

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Separate file from settings and deliberately not included in backups (see backup rules). */
val Context.accountDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "account",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

data class GoogleAccountState(
    /** Email of the connected account; null = not connected. No tokens are stored, ever. */
    val email: String? = null,
    val calendarEnabled: Boolean = true,
    val tasksEnabled: Boolean = true,
) {
    val isConnected get() = email != null
}

class AccountStore(private val store: DataStore<Preferences>) {
    private object K {
        val email = stringPreferencesKey("google_email")
        val calendar = booleanPreferencesKey("google_calendar_enabled")
        val tasks = booleanPreferencesKey("google_tasks_enabled")
    }

    val state: Flow<GoogleAccountState> = store.data.catch { emit(emptyPreferences()) }.map {
        GoogleAccountState(it[K.email], it[K.calendar] ?: true, it[K.tasks] ?: true)
    }

    suspend fun current() = state.first()
    suspend fun setConnected(email: String) = store.edit { it[K.email] = email }
    suspend fun setCalendarEnabled(v: Boolean) = store.edit { it[K.calendar] = v }
    suspend fun setTasksEnabled(v: Boolean) = store.edit { it[K.tasks] = v }
    suspend fun clear() = store.edit { it.clear() }
}
