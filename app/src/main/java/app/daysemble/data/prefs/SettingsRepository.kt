package app.daysemble.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.daysemble.ui.brand.LogoVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import app.daysemble.core.time.DaySchedulePrefs
import app.daysemble.core.time.SleepSchedule
import java.io.IOException
import java.time.DayOfWeek

/** File name is referenced by backup rules (datastore/settings.preferences_pb). */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Light (white) is the default; Dark is near-black; System follows the phone. */
enum class ThemeMode(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: LIGHT
    }
}

enum class ClockFormat(val key: String) {
    SYSTEM("system"), H12("12"), H24("24");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class TaskSort(val key: String) {
    DUE("due"), PRIORITY("priority"), CREATED("created"), MANUAL("manual");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key } ?: DUE
    }
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val logo: LogoVariant = LogoVariant.DEFAULT,
    /** null = follow the system animator scale. */
    val reducedMotionOverride: Boolean? = null,
    val remindersEnabled: Boolean = true,
    /** Minutes before start. null = no default reminder. */
    val defaultEventReminderMinutes: Int? = 10,
    /** Minute of day used for date-only task reminders. */
    val defaultTaskReminderMinuteOfDay: Int = 9 * 60,
    val hideNotificationDetails: Boolean = false,
    /** null = locale default. */
    val firstDayOfWeek: DayOfWeek? = null,
    val clockFormat: ClockFormat = ClockFormat.SYSTEM,
    val defaultEventDurationMinutes: Int = 60,
    val showCompletedOnToday: Boolean = true,
    val taskSort: TaskSort = TaskSort.DUE,
    val defaultTaskListId: String? = null,
    val onboardingDone: Boolean = false,
    /** Optional name shown beside the logo on Today. null = not set (the app name is shown instead). */
    val displayName: String? = null,
    /** Intended waking hours (wake-up and bedtime), used for free time and the scheduled sleep. */
    val schedule: DaySchedulePrefs = DaySchedulePrefs(),
)

/** Longest display name kept; longer input is cut (the header also truncates visually). */
const val DISPLAY_NAME_MAX = 40

/** Trims and collapses whitespace; blank becomes null. */
fun normalizeDisplayName(raw: String?): String? =
    raw?.trim()?.replace(Regex("\\s+"), " ")?.take(DISPLAY_NAME_MAX)?.trim()?.takeIf { it.isNotEmpty() }

class SettingsRepository(private val store: DataStore<Preferences>) {

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val logo = stringPreferencesKey("logo_variant")
        val reducedMotion = stringPreferencesKey("reduced_motion") // "on" | "off" | absent
        val remindersEnabled = booleanPreferencesKey("reminders_enabled")
        val defaultEventReminder = intPreferencesKey("default_event_reminder") // -1 = none
        val defaultTaskReminderTime = intPreferencesKey("default_task_reminder_minute")
        val hideDetails = booleanPreferencesKey("hide_notification_details")
        val firstDay = intPreferencesKey("first_day_of_week") // 1..7, absent = locale
        val clock = stringPreferencesKey("clock_format")
        val eventDuration = intPreferencesKey("default_event_duration")
        val showCompleted = booleanPreferencesKey("show_completed_today")
        val taskSort = stringPreferencesKey("task_sort")
        val defaultList = stringPreferencesKey("default_task_list")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val displayName = stringPreferencesKey("display_name")
        val wake = intPreferencesKey("schedule_wake_minute")
        val bed = intPreferencesKey("schedule_bed_minute")
        val perWeekday = booleanPreferencesKey("schedule_per_weekday")
        val weekdayOverrides = stringPreferencesKey("schedule_weekday_overrides")
    }

    val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { p -> p.toSettings() }
        .distinctUntilChanged()

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = ThemeMode.fromKey(this[Keys.theme]),
            logo = LogoVariant.fromKey(this[Keys.logo]),
            reducedMotionOverride = when (this[Keys.reducedMotion]) {
                "on" -> true; "off" -> false; else -> null
            },
            remindersEnabled = this[Keys.remindersEnabled] ?: d.remindersEnabled,
            defaultEventReminderMinutes = when (val v = this[Keys.defaultEventReminder]) {
                null -> d.defaultEventReminderMinutes; -1 -> null; else -> v
            },
            defaultTaskReminderMinuteOfDay = (this[Keys.defaultTaskReminderTime] ?: d.defaultTaskReminderMinuteOfDay)
                .coerceIn(0, 24 * 60 - 1),
            hideNotificationDetails = this[Keys.hideDetails] ?: d.hideNotificationDetails,
            firstDayOfWeek = this[Keys.firstDay]?.takeIf { it in 1..7 }?.let(DayOfWeek::of),
            clockFormat = ClockFormat.fromKey(this[Keys.clock]),
            defaultEventDurationMinutes = (this[Keys.eventDuration] ?: d.defaultEventDurationMinutes).coerceIn(5, 24 * 60),
            showCompletedOnToday = this[Keys.showCompleted] ?: d.showCompletedOnToday,
            taskSort = TaskSort.fromKey(this[Keys.taskSort]),
            defaultTaskListId = this[Keys.defaultList],
            onboardingDone = this[Keys.onboarding] ?: false,
            displayName = normalizeDisplayName(this[Keys.displayName]),
            schedule = schedule(),
        )
    }

    private fun Preferences.schedule(): DaySchedulePrefs {
        val d = SleepSchedule.DEFAULT
        val wake = this[Keys.wake]?.takeIf { it in 0 until SleepSchedule.MINUTES_PER_DAY } ?: d.wakeMinute
        val bed = this[Keys.bed]?.takeIf { it in 0 until SleepSchedule.MINUTES_PER_DAY && it != wake } ?: if (d.bedMinute != wake) d.bedMinute else (wake + 16 * 60) % SleepSchedule.MINUTES_PER_DAY
        return DaySchedulePrefs(
            default = SleepSchedule(wake, bed),
            perWeekday = this[Keys.perWeekday] ?: false,
            overrides = DaySchedulePrefs.decodeOverrides(this[Keys.weekdayOverrides]),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) = store.edit { it[Keys.theme] = mode.key }
    suspend fun setLogo(variant: LogoVariant) = store.edit { it[Keys.logo] = variant.key }
    suspend fun setReducedMotion(value: Boolean?) = store.edit {
        if (value == null) it.remove(Keys.reducedMotion) else it[Keys.reducedMotion] = if (value) "on" else "off"
    }
    suspend fun setRemindersEnabled(v: Boolean) = store.edit { it[Keys.remindersEnabled] = v }
    suspend fun setDefaultEventReminder(minutes: Int?) = store.edit { it[Keys.defaultEventReminder] = minutes ?: -1 }
    suspend fun setDefaultTaskReminderTime(minuteOfDay: Int) =
        store.edit { it[Keys.defaultTaskReminderTime] = minuteOfDay.coerceIn(0, 24 * 60 - 1) }
    suspend fun setHideNotificationDetails(v: Boolean) = store.edit { it[Keys.hideDetails] = v }
    suspend fun setFirstDayOfWeek(day: DayOfWeek?) = store.edit {
        if (day == null) it.remove(Keys.firstDay) else it[Keys.firstDay] = day.value
    }
    suspend fun setClockFormat(f: ClockFormat) = store.edit { it[Keys.clock] = f.key }
    suspend fun setDefaultEventDuration(minutes: Int) = store.edit { it[Keys.eventDuration] = minutes.coerceIn(5, 24 * 60) }
    suspend fun setShowCompletedOnToday(v: Boolean) = store.edit { it[Keys.showCompleted] = v }
    suspend fun setTaskSort(s: TaskSort) = store.edit { it[Keys.taskSort] = s.key }
    suspend fun setDefaultTaskList(id: String?) = store.edit {
        if (id == null) it.remove(Keys.defaultList) else it[Keys.defaultList] = id
    }
    suspend fun setOnboardingDone() = store.edit { it[Keys.onboarding] = true }

    /**
     * Sets the default waking hours. Refused (returns false, nothing saved) when wake-up and bedtime
     * are the same minute: that day would have no sleep or no waking hours.
     */
    suspend fun setDefaultSchedule(wakeMinute: Int, bedMinute: Int): Boolean {
        if (!validSchedule(wakeMinute, bedMinute)) return false
        store.edit { it[Keys.wake] = wakeMinute; it[Keys.bed] = bedMinute }
        return true
    }

    suspend fun setPerWeekdaySchedule(on: Boolean) = store.edit { it[Keys.perWeekday] = on }

    /** Sets (or with null clears) the hours of one weekday. Same rule as [setDefaultSchedule]. */
    suspend fun setWeekdaySchedule(day: DayOfWeek, schedule: SleepSchedule?): Boolean {
        if (schedule != null && !validSchedule(schedule.wakeMinute, schedule.bedMinute)) return false
        store.edit {
            val current = DaySchedulePrefs.decodeOverrides(it[Keys.weekdayOverrides]).toMutableMap()
            if (schedule == null) current.remove(day) else current[day] = schedule
            if (current.isEmpty()) it.remove(Keys.weekdayOverrides) else it[Keys.weekdayOverrides] = DaySchedulePrefs.encodeOverrides(current)
        }
        return true
    }

    /** Back to 07:00–23:00 every day, without weekday schedules. Sleep records are not touched. */
    suspend fun resetSchedule() = store.edit {
        it.remove(Keys.wake); it.remove(Keys.bed); it.remove(Keys.perWeekday); it.remove(Keys.weekdayOverrides)
    }

    private fun validSchedule(wake: Int, bed: Int) =
        wake in 0 until SleepSchedule.MINUTES_PER_DAY && bed in 0 until SleepSchedule.MINUTES_PER_DAY && wake != bed

    suspend fun setDisplayName(name: String?) = store.edit {
        val n = normalizeDisplayName(name)
        if (n == null) it.remove(Keys.displayName) else it[Keys.displayName] = n
    }

    /**
     * Finishes first-use setup in one write, so the app never sees "done" without the chosen name
     * (or the name without "done"). A null/blank name leaves any existing name untouched.
     */
    suspend fun completeOnboarding(name: String?) = store.edit {
        normalizeDisplayName(name)?.let { n -> it[Keys.displayName] = n }
        it[Keys.onboarding] = true
    }
}
