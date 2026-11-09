package app.daysemble.ui.search

import app.daysemble.ui.components.itemMotion
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.daysemble.R
import app.daysemble.core.time.AppClock
import app.daysemble.data.repo.EventRepository
import app.daysemble.data.repo.TaskRepository
import app.daysemble.domain.TaskRules
import app.daysemble.domain.model.EventOccurrence
import app.daysemble.domain.model.Task
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.EventRow
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

enum class SearchScope { ALL, UPCOMING, OVERDUE, COMPLETED }

@Immutable
data class SearchUiState(
    val query: String = "",
    val scope: SearchScope = SearchScope.ALL,
    val events: List<EventOccurrence> = emptyList(),
    val tasks: List<Task> = emptyList(),
)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(
    private val events: EventRepository,
    private val tasks: TaskRepository,
    private val clock: AppClock,
    private val time: kotlinx.coroutines.flow.StateFlow<app.daysemble.core.time.TimeContext>,
    private val handle: SavedStateHandle,
) : ViewModel() {
    private fun zone(): ZoneId = time.value.zone
    private val query = MutableStateFlow(handle["q"] ?: "")
    private val scope = MutableStateFlow(handle.get<String>("s")?.let { runCatching { SearchScope.valueOf(it) }.getOrNull() } ?: SearchScope.ALL)

    val state: StateFlow<SearchUiState> = combine(query.debounce(150), scope, time) { q, s, _ -> q to s }.flatMapLatest { (q, s) ->
        if (q.isBlank()) flowOf(SearchUiState(q, s))
        else combine(events.search(q, clock.now(), zone()), tasks.allTasks) { evs, all ->
            val now = clock.now(); val z = zone()
            SearchUiState(
                query = q, scope = s,
                events = evs.filter { e ->
                    when (s) {
                        SearchScope.ALL -> true
                        SearchScope.UPCOMING -> e.end > now
                        SearchScope.OVERDUE -> false
                        SearchScope.COMPLETED -> e.end <= now
                    }
                },
                tasks = all.filter { TaskRules.matchesQuery(it, q) }.filter { t ->
                    when (s) {
                        SearchScope.ALL -> true
                        SearchScope.UPCOMING -> !t.isCompleted && !TaskRules.isOverdue(t, now, z)
                        SearchScope.OVERDUE -> TaskRules.isOverdue(t, now, z)
                        SearchScope.COMPLETED -> t.isCompleted
                    }
                }.sortedWith(TaskRules.comparator(app.daysemble.data.prefs.TaskSort.DUE, z)).take(200),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState(query.value, scope.value))

    val rawQuery: StateFlow<String> = query

    fun setQuery(q: String) { query.value = q; handle["q"] = q }
    fun setScope(s: SearchScope) { scope.value = s; handle["s"] = s.name }
    fun setCompleted(id: String, done: Boolean, onResult: (app.daysemble.data.repo.TaskRepository.Completion) -> Unit = {}) = viewModelScope.launch { tasks.setCompleted(id, done)?.let(onResult) }
}

/**
 * The results of the header search: events and tasks matching [query], with scope chips. The query
 * is typed in the header (see MainHeader); results update as it changes and rows animate in and out.
 */
@Composable
fun SearchResults(query: String, onOpenEvent: (String, String) -> Unit, onOpenTask: (String) -> Unit, modifier: Modifier = Modifier) {
    val vm = daysembleViewModel { c, h -> SearchViewModel(c.events, c.tasks, c.clock, c.timeContext, h) }
    val onCompleted = app.daysemble.ui.LocalTaskCompleted.current
    val state by vm.state.collectAsStateWithLifecycle()
    val now: Instant = rememberMinuteTicker()
    LaunchedEffect(query) { vm.setQuery(query) }
    val c = DsTheme.colors

    Column(modifier.fillMaxSize().background(c.background)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.gutter - Space.xs, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            SearchScope.entries.forEach { s ->
                DsChip(
                    stringResource(
                        when (s) {
                            SearchScope.ALL -> R.string.filter_all
                            SearchScope.UPCOMING -> R.string.filter_upcoming
                            SearchScope.OVERDUE -> R.string.filter_overdue
                            SearchScope.COMPLETED -> R.string.filter_completed_past
                        },
                    ),
                    state.scope == s, { vm.setScope(s) },
                )
            }
        }
        when {
            query.isBlank() -> StateMessage(
                Icons.Rounded.Search, stringResource(R.string.search_prompt), Modifier.padding(top = Space.x3),
                body = stringResource(R.string.search_prompt_body),
            )
            state.query.isNotBlank() && state.events.isEmpty() && state.tasks.isEmpty() ->
                StateMessage(Icons.Rounded.SearchOff, stringResource(R.string.search_no_results, state.query), Modifier.padding(top = Space.x3))
            else -> LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = Space.xxl)) {
                if (state.events.isNotEmpty()) {
                    item(key = "eh") { SectionHeader(stringResource(R.string.search_events), Modifier.padding(top = Space.s).then(itemMotion())) }
                    items(state.events, key = { "e" + it.stableKey }) { e ->
                        EventRow(e, now, onClick = { onOpenEvent(e.eventId, e.occurrenceKey) }, showDate = true, modifier = Modifier.then(itemMotion()), onLongClick = app.daysemble.ui.actions.LocalItemActions.current?.let { a -> { a.onEventLongPress(e) } })
                    }
                }
                if (state.tasks.isNotEmpty()) {
                    item(key = "th") { SectionHeader(stringResource(R.string.search_tasks), Modifier.padding(top = Space.m).then(itemMotion())) }
                    items(state.tasks, key = { "t" + it.id }) { t ->
                        TaskRow(t, now, onToggle = { done -> vm.setCompleted(t.id, done) { r -> if (done) onCompleted(t.id, r) } }, onClick = { onOpenTask(t.id) }, modifier = Modifier.then(itemMotion()), onLongClick = app.daysemble.ui.actions.LocalItemActions.current?.let { a -> { a.onTaskLongPress(t) } })
                    }
                }
            }
        }
    }
}
