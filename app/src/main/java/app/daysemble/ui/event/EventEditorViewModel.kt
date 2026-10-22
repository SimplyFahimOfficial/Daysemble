package app.daysemble.ui.event

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.daysemble.data.repo.EventRepository
import app.daysemble.domain.model.CalendarInfo
import app.daysemble.domain.model.EventValidation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.ZoneId

@Immutable
data class EventEditorUiState(
    val isNew: Boolean,
    val form: EventForm?,
    val original: EventForm?,
    val calendars: List<CalendarInfo> = emptyList(),
    val validation: EventValidation? = null,
    val showErrors: Boolean = false,
    val saveError: Boolean = false,
) {
    val isDirty get() = form != null && original != null && form != original
    val calendar get() = calendars.firstOrNull { it.id == form?.calendarId }
}

class EventEditorViewModel(
    private val repo: EventRepository,
    private val defaultCalendarId: suspend () -> String,
    private val eventId: String?,
    private val newDefaults: suspend () -> EventForm,
    private val deviceZone: ZoneId,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private val json = Json { ignoreUnknownKeys = true }
    private val form = MutableStateFlow(handle.get<String>(KEY)?.let { runCatching { json.decodeFromString<EventForm>(it) }.getOrNull() })
    private val original = MutableStateFlow(handle.get<String>(KEY_ORIG)?.let { runCatching { json.decodeFromString<EventForm>(it) }.getOrNull() })
    private val showErrors = MutableStateFlow(false)
    private val saveError = MutableStateFlow(false)
    val done = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            if (form.value != null) return@launch
            val f = if (eventId != null) repo.draftFor(eventId, deviceZone)?.let(EventForm::from) else {
                newDefaults().let { if (it.calendarId == null) it.copy(calendarId = defaultCalendarId()) else it }
            }
            if (f == null) { done.value = true; return@launch }
            original.value = if (eventId != null) f else f.copy(title = "")
            set(f)
        }
    }

    val state: StateFlow<EventEditorUiState> = combine(form, original, repo.calendarList, showErrors, saveError) { f, o, cals, err, saveErr ->
        EventEditorUiState(
            isNew = eventId == null, form = f, original = o,
            calendars = cals.filter { it.isWritable }, validation = f?.toDraft()?.validationError,
            showErrors = err, saveError = saveErr,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventEditorUiState(eventId == null, form.value, original.value))

    fun update(f: (EventForm) -> EventForm) { form.value?.let { set(f(it)) } }

    fun save() = viewModelScope.launch {
        val f = form.value ?: return@launch
        val draft = f.toDraft()
        if (draft.validationError != null) { showErrors.value = true; return@launch }
        val ok = runCatching {
            if (eventId == null) repo.create(draft, f.calendarId ?: defaultCalendarId()) else repo.update(eventId, draft)
        }.isSuccess
        if (ok) { original.value = f; done.value = true } else saveError.value = true
    }

    private fun set(f: EventForm) {
        form.value = f
        handle[KEY] = json.encodeToString(EventForm.serializer(), f)
        original.value?.let { handle[KEY_ORIG] = json.encodeToString(EventForm.serializer(), it) }
    }

    private companion object {
        const val KEY = "event_form"
        const val KEY_ORIG = "event_form_orig"
    }
}

/** Builds the initial form for a new event starting on [date] at [startMinute] (or the next half hour). */
fun newEventForm(date: LocalDate, startMinute: Int, durationMinutes: Int, zone: ZoneId, title: String, reminder: Int?): EventForm {
    val start = date.atTime(startMinute / 60, startMinute % 60)
    val end = start.plusMinutes(durationMinutes.toLong())
    return EventForm(
        title = title, startDay = date.toEpochDay(), startMinute = startMinute,
        endDay = end.toLocalDate().toEpochDay(), endMinute = end.hour * 60 + end.minute, zone = zone.id,
        reminders = listOfNotNull(reminder),
    )
}
