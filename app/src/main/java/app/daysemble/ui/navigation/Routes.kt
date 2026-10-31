package app.daysemble.ui.navigation

import kotlinx.serialization.Serializable

/**
 * The main screen: Today, Calendar and Tasks with the bottom bar. Switching tabs happens inside this
 * one destination, so it doesn't add or pop back-stack entries; screens opened from it (details,
 * editors, Settings) are pushed on top and cover the whole main screen, bottom bar included.
 */
@Serializable data object MainRoute

/** A task's detail and countdown view: where a tap on a task goes (editing is one step further). */
@Serializable data class TaskDetailRoute(val taskId: String)
@Serializable data class TaskEditorRoute(val taskId: String? = null, val parentId: String? = null, val dueEpochDay: Long? = null)
@Serializable data class EventDetailRoute(val eventId: String, val occurrenceKey: String? = null)
@Serializable data class EventEditorRoute(
    val eventId: String? = null,
    /** With [eventId] of a series: edit only this occurrence. */
    val occurrenceKey: String? = null,
    val dateEpochDay: Long? = null,
    val startMinute: Int? = null,
    val title: String? = null,
)
@Serializable data object SearchRoute
@Serializable data object SettingsRoute
@Serializable data class SettingsPageRoute(val page: String)
