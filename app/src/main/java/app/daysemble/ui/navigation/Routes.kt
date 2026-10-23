package app.daysemble.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object TodayRoute
@Serializable data object CalendarRoute
@Serializable data object TasksRoute

@Serializable data class TaskEditorRoute(val taskId: String? = null, val parentId: String? = null, val dueEpochDay: Long? = null)
@Serializable data class EventDetailRoute(val eventId: String, val occurrenceKey: String? = null)
@Serializable data class EventEditorRoute(
    val eventId: String? = null,
    val dateEpochDay: Long? = null,
    val startMinute: Int? = null,
    val title: String? = null,
)
@Serializable data object SearchRoute
@Serializable data object SettingsRoute
@Serializable data class SettingsPageRoute(val page: String)
