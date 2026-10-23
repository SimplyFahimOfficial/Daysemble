package app.daysemble.data.google.tasks

import app.daysemble.data.google.GoogleHttp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable data class TaskListsPage(val items: List<TaskListDto> = emptyList(), val nextPageToken: String? = null)
@Serializable data class TaskListDto(val id: String, val title: String = "", val updated: String? = null)
@Serializable data class TasksPage(val items: List<TaskDto> = emptyList(), val nextPageToken: String? = null)

@Serializable data class TaskDto(
    val id: String? = null,
    val etag: String? = null,
    val title: String? = null,
    val notes: String? = null,
    /** "needsAction" | "completed" */
    val status: String? = null,
    /** RFC 3339; only the date part is meaningful (Google Tasks has no due time). */
    val due: String? = null,
    val completed: String? = null,
    val deleted: Boolean = false,
    val hidden: Boolean = false,
    val parent: String? = null,
    val position: String? = null,
    val updated: String? = null,
)

/** Fields Daysemble manages; null values are sent as JSON null to clear them remotely. */
data class TaskPatch(val title: String, val notes: String, val dueDate: String?, val completed: Boolean, val completedAt: String?)

/** Google Tasks API v1, just the endpoints Daysemble uses. */
class TasksApi(private val http: GoogleHttp, private val base: String = "https://tasks.googleapis.com/tasks/v1/") {
    private val json get() = http.json
    private fun u(path: String): HttpUrl.Builder = (base + path).toHttpUrl().newBuilder()

    suspend fun lists(pageToken: String?): TaskListsPage =
        json.decodeFromString(http.get(u("users/@me/lists").addQueryParameter("maxResults", "100").apply { pageToken?.let { addQueryParameter("pageToken", it) } }.build()))

    suspend fun tasks(listId: String, updatedMin: String?, pageToken: String?): TasksPage {
        val b = u("lists").addPathSegment(listId).addPathSegment("tasks")
            .addQueryParameter("maxResults", "100")
            .addQueryParameter("showCompleted", "true")
            .addQueryParameter("showDeleted", "true")
            .addQueryParameter("showHidden", "true")
        updatedMin?.let { b.addQueryParameter("updatedMin", it) }
        pageToken?.let { b.addQueryParameter("pageToken", it) }
        return json.decodeFromString(http.get(b.build()))
    }

    suspend fun get(listId: String, taskId: String): TaskDto =
        json.decodeFromString(http.get(u("lists").addPathSegment(listId).addPathSegment("tasks").addPathSegment(taskId).build()))

    suspend fun insert(listId: String, patch: TaskPatch, parent: String?): TaskDto {
        val url = u("lists").addPathSegment(listId).addPathSegment("tasks").apply { parent?.let { addQueryParameter("parent", it) } }.build()
        return json.decodeFromString(http.send("POST", url, body(patch)))
    }

    suspend fun patch(listId: String, taskId: String, patch: TaskPatch): TaskDto =
        json.decodeFromString(http.send("PATCH", u("lists").addPathSegment(listId).addPathSegment("tasks").addPathSegment(taskId).build(), body(patch)))

    suspend fun delete(listId: String, taskId: String) {
        http.send("DELETE", u("lists").addPathSegment(listId).addPathSegment("tasks").addPathSegment(taskId).build(), null)
    }

    private fun body(p: TaskPatch): String = buildJsonObject {
        put("title", JsonPrimitive(p.title))
        put("notes", JsonPrimitive(p.notes))
        put("due", p.dueDate?.let { JsonPrimitive("${it}T00:00:00.000Z") } ?: JsonNull)
        put("status", JsonPrimitive(if (p.completed) "completed" else "needsAction"))
        put("completed", if (p.completed && p.completedAt != null) JsonPrimitive(p.completedAt) else JsonNull)
    }.toString()
}
