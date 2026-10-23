package app.daysemble.data.google.calendar

import app.daysemble.data.google.GoogleHttp
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

@Serializable data class CalendarListPage(val items: List<CalendarListEntryDto> = emptyList(), val nextPageToken: String? = null)

@Serializable data class CalendarListEntryDto(
    val id: String,
    val summary: String? = null,
    val summaryOverride: String? = null,
    val backgroundColor: String? = null,
    val accessRole: String = "reader",
    val primary: Boolean = false,
    val timeZone: String? = null,
    val deleted: Boolean = false,
    val defaultReminders: List<ReminderDto> = emptyList(),
)

@Serializable data class EventsPage(
    val items: List<EventDto> = emptyList(),
    val nextPageToken: String? = null,
    val nextSyncToken: String? = null,
    val timeZone: String? = null,
)

@Serializable data class EventDateTime(val date: String? = null, val dateTime: String? = null, val timeZone: String? = null)

// Google requires method + minutes on every override and an explicit useDefault, so always encode them.
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class ReminderDto(@EncodeDefault val method: String = "popup", @EncodeDefault val minutes: Int = 10)
@OptIn(ExperimentalSerializationApi::class)
@Serializable data class RemindersDto(@EncodeDefault val useDefault: Boolean = false, val overrides: List<ReminderDto>? = null)

@Serializable data class EventDto(
    val id: String? = null,
    val status: String? = null,
    val etag: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start: EventDateTime? = null,
    val end: EventDateTime? = null,
    val recurrence: List<String>? = null,
    val recurringEventId: String? = null,
    val originalStartTime: EventDateTime? = null,
    val updated: String? = null,
    val reminders: RemindersDto? = null,
    val colorId: String? = null,
    val eventType: String? = null,
)

/** Google Calendar API v3, just the endpoints Daysemble uses. */
class CalendarApi(private val http: GoogleHttp, private val base: String = "https://www.googleapis.com/calendar/v3/") {
    private val json get() = http.json
    private fun u(path: String): HttpUrl.Builder = (base + path).toHttpUrl().newBuilder()

    suspend fun calendarList(pageToken: String?): CalendarListPage =
        json.decodeFromString(http.get(u("users/me/calendarList").addQueryParameter("maxResults", "250")
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }.build()))

    /**
     * Incremental (syncToken) or full listing. singleEvents=false keeps series masters plus
     * their exceptions, which is what the local expander needs. showDeleted surfaces cancellations.
     */
    suspend fun events(calendarId: String, syncToken: String?, pageToken: String?): EventsPage {
        val b = u("calendars").addPathSegment(calendarId).addPathSegment("events")
            .addQueryParameter("maxResults", "250")
            .addQueryParameter("showDeleted", "true")
            .addQueryParameter("singleEvents", "false")
        // A full sync must not use timeMin/timeMax, or later syncToken requests are undefined.
        if (syncToken != null) b.addQueryParameter("syncToken", syncToken)
        pageToken?.let { b.addQueryParameter("pageToken", it) }
        return json.decodeFromString(http.get(b.build()))
    }

    /** Google-expanded instances of a series in [timeMin, timeMax); used for rules we can't expand locally. */
    suspend fun instances(calendarId: String, eventId: String, timeMin: String, timeMax: String, pageToken: String?): EventsPage =
        json.decodeFromString(
            http.get(
                u("calendars").addPathSegment(calendarId).addPathSegment("events").addPathSegment(eventId).addPathSegment("instances")
                    .addQueryParameter("timeMin", timeMin).addQueryParameter("timeMax", timeMax)
                    .addQueryParameter("maxResults", "250").addQueryParameter("showDeleted", "false")
                    .apply { pageToken?.let { addQueryParameter("pageToken", it) } }.build(),
            ),
        )

    suspend fun get(calendarId: String, eventId: String): EventDto =
        json.decodeFromString(http.get(u("calendars").addPathSegment(calendarId).addPathSegment("events").addPathSegment(eventId).build()))

    suspend fun insert(calendarId: String, event: EventDto): EventDto =
        json.decodeFromString(http.send("POST", u("calendars").addPathSegment(calendarId).addPathSegment("events").build(), json.encodeToString(EventDto.serializer(), event)))

    suspend fun patch(calendarId: String, eventId: String, event: EventDto, etag: String?): EventDto =
        json.decodeFromString(
            http.send("PATCH", u("calendars").addPathSegment(calendarId).addPathSegment("events").addPathSegment(eventId).build(),
                json.encodeToString(EventDto.serializer(), event), ifMatch = etag),
        )

    suspend fun delete(calendarId: String, eventId: String) {
        http.send("DELETE", u("calendars").addPathSegment(calendarId).addPathSegment("events").addPathSegment(eventId).build(), null)
    }
}
