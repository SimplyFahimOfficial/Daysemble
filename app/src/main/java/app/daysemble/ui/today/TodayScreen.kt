package app.daysemble.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.R
import app.daysemble.core.time.Availability
import app.daysemble.core.time.AvailabilitySummary
import app.daysemble.ui.components.DashedDivider
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsChip
import app.daysemble.ui.components.AgendaEventCard
import app.daysemble.ui.components.AgendaTaskCard
import app.daysemble.ui.components.SectionHeader
import app.daysemble.ui.components.StateMessage
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.components.WeekStrip
import app.daysemble.ui.components.itemMotion
import app.daysemble.ui.daysembleViewModel
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.format.TimePrefs
import app.daysemble.ui.format.durationText
import app.daysemble.ui.format.rememberMinuteTicker
import app.daysemble.ui.theme.DsMotion
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun TodayRoute(
    contentPadding: PaddingValues,
    onOpenEvent: (eventId: String, key: String) -> Unit,
    onOpenTask: (String) -> Unit,
    onFocusedDate: (LocalDate) -> Unit = {},
    onAddEvent: (LocalDate) -> Unit = {},
    onAddTask: (LocalDate) -> Unit = {},
) {
    val vm = daysembleViewModel { c, h -> TodayViewModel(c.events, c.tasks, c.settings, c.clock, c.timeContext, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val now = rememberMinuteTicker()
    val onCompleted = app.daysemble.ui.LocalTaskCompleted.current
    LaunchedEffect(now) { vm.onTick(now) }
    LaunchedEffect(state.date) { onFocusedDate(state.date) }
    TodayScreen(
        state = state, now = now, contentPadding = contentPadding,
        displayName = app.daysemble.ui.LocalAppSettings.current?.displayName,
        onSelectDate = vm::select, onToday = vm::goToToday,
        onToggleTask = { id, done -> vm.setCompleted(id, done) { r -> if (done) onCompleted(id, r) } }, onOpenEvent = onOpenEvent, onOpenTask = onOpenTask,
        onAddEvent = onAddEvent, onAddTask = onAddTask,
    )
}

/** Test tags for the Today layout (gesture tests and visual QA). */
object TodayTags {
    const val SCREEN = "today"
    const val OVERVIEW = "today_overview"
    const val AGENDA = "today_agenda"
}

/** Whether the daily overview above the agenda is folded into one line or shown in full. */
enum class OverviewValue { Collapsed, Expanded }

/**
 * Today: a daily overview on the canvas and the agenda panel below it.
 *
 * Collapsed (default), the overview is one compact line (weekday, date dot, date, counts) and the
 * panel takes the rest of the screen. Expanded, the panel moves down and reveals the full overview:
 * the large weekday and date, a greeting with the day's briefing, and free/busy time.
 *
 * Gestures (all on one [AnchoredDraggableState]; the panel follows the finger and settles on
 * release by position and velocity):
 * - Drag the panel's handle row (handle and week strip) down or up, or tap the handle.
 * - Drag the overview area itself down or up.
 * - In the agenda list, a pull down that *starts* with the list at the top expands the overview;
 *   scrolling the list up first folds an expanded overview away, then scrolls the list.
 * Nothing else reacts: the status bar and its shade are above the shared header, the week strip
 * scrolls horizontally, and a pull that starts mid-list only scrolls the list.
 */
@Composable
fun TodayScreen(
    state: TodayUiState,
    now: Instant,
    contentPadding: PaddingValues,
    onSelectDate: (LocalDate) -> Unit,
    onToday: () -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    displayName: String? = null,
    onAddEvent: (LocalDate) -> Unit = {},
    onAddTask: (LocalDate) -> Unit = {},
) {
    val c = DsTheme.colors
    val panel = rememberOverviewPanel()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val minPanel = with(density) { (contentPadding.calculateBottomPadding() + 196.dp).roundToPx() }
    val expandedTarget = panel.state.targetValue == OverviewValue.Expanded
    val overviewScroll = remember(panel) { OverviewScroll(panel) }
    val overviewScrollState = rememberScrollState()

    Layout(
        modifier = Modifier.fillMaxSize().background(c.background).testTag(TodayTags.SCREEN),
        content = {
            // 0: the compact overview (fades out as the panel moves down).
            // The two overviews never show at once: the compact one is gone by 35 % of the way,
            // the full one fades in over the rest (no double image mid-drag).
            Box(
                Modifier
                    .graphicsLayer {
                        val p = panel.progress()
                        alpha = (1f - p / 0.35f).coerceIn(0f, 1f)
                        translationY = -p * 8.dp.toPx()
                    }
                    .then(if (expandedTarget) Modifier.clearAndSetSemantics { } else Modifier),
            ) { CompactOverview(state) }
            // 1: the full overview (fades in). Scrollable only when it is taller than the space
            // above the panel (large text); otherwise every drag on it moves the panel.
            Box(Modifier.testTag(TodayTags.OVERVIEW).then(if (!expandedTarget) Modifier.clearAndSetSemantics { } else Modifier)) {
                Column(
                    Modifier
                        .nestedScroll(overviewScroll)
                        .verticalScroll(overviewScrollState, overscrollEffect = null)
                        .graphicsLayer { alpha = ((panel.progress() - 0.35f) / 0.65f).coerceIn(0f, 1f) },
                ) { ExpandedOverview(state, now, displayName) }
            }
            // 2: the agenda panel.
            AgendaPanel(
                state, now, contentPadding, panel,
                onToggle = { scope.launch { panel.animateTo(if (expandedTarget) OverviewValue.Collapsed else OverviewValue.Expanded) } },
                onSelectDate = onSelectDate, onToday = onToday, onToggleTask = onToggleTask,
                onOpenEvent = onOpenEvent, onOpenTask = onOpenTask, onAddEvent = onAddEvent, onAddTask = onAddTask,
            )
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val loose = Constraints(minWidth = w, maxWidth = w, minHeight = 0, maxHeight = h)
        val compact = measurables[0].measure(loose)
        val cap = (h - minPanel).coerceAtLeast(compact.height)
        val expanded = measurables[1].measure(loose.copy(maxHeight = cap))
        val collapsedTop = compact.height.toFloat()
        val expandedTop = maxOf(expanded.height.toFloat(), collapsedTop + 1f)
        val anchors = DraggableAnchors {
            OverviewValue.Collapsed at collapsedTop
            OverviewValue.Expanded at expandedTop
        }
        if (panel.state.anchors != anchors) panel.state.updateAnchors(anchors, panel.state.targetValue)
        val top = panel.state.offset.takeIf { !it.isNaN() }
            ?: if (panel.state.currentValue == OverviewValue.Expanded) expandedTop else collapsedTop
        val y = top.roundToInt().coerceIn(0, h)
        val agenda = measurables[2].measure(Constraints.fixed(w, h - y))
        layout(w, h) {
            compact.place(0, 0)
            expanded.place(0, 0)
            agenda.place(0, y)
        }
    }
}

// ---- Overview ---------------------------------------------------------------------------------

@Composable
private fun CompactOverview(state: TodayUiState) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    AnimatedContent(
        targetState = state, contentKey = { it.date },
        transitionSpec = { m.fadeThrough(targetState.date.compareTo(initialState.date).coerceIn(-1, 1), rtl) },
        label = "compactOverview",
    ) { s ->
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(top = Space.xs, bottom = Space.l)) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom) {
                WeekdayHeading(s.date, s.date == s.today, DsTheme.type.headline, dot = 9.dp, Modifier.alignByBaseline())
                Spacer(Modifier.weight(1f))
                Text(
                    Fmt.monthDay(s.date, p), style = DsTheme.type.title, color = c.textSecondary, maxLines = 1,
                    modifier = Modifier.padding(start = Space.m).alignByBaseline(),
                )
            }
            Spacer(Modifier.height(Space.xxs))
            Text(compactLine(s, p), style = DsTheme.type.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExpandedOverview(state: TodayUiState, now: Instant, name: String?) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Another day fades through in the direction of travel, and the panel follows the new height.
    AnimatedContent(
        targetState = state, contentKey = { it.date },
        transitionSpec = { m.fadeThrough(targetState.date.compareTo(initialState.date).coerceIn(-1, 1), rtl, m.contentSize()) },
        label = "overview",
    ) { s ->
        Column(Modifier.fillMaxWidth().padding(horizontal = Space.gutter).padding(top = Space.xs, bottom = Space.xxl)) {
            // Weekday left, date right; with large text the date moves under the weekday instead of
            // squeezing it.
            FlowRow(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                WeekdayHeading(s.date, s.date == s.today, DsTheme.type.display, dot = 12.dp, Modifier.align(Alignment.Bottom))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.align(Alignment.Bottom).padding(start = Space.m, bottom = Space.xs)) {
                    Text(Fmt.monthDay(s.date, p), style = DsTheme.type.title, color = c.textSecondary, maxLines = 1)
                    Text(s.date.year.toString(), style = DsTheme.type.title, color = c.textTertiary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(Space.xl))
            val (text, icons) = briefing(s, now, name, p)
            Text(text, style = DsTheme.type.summary, inlineContent = icons)
            Metrics(s, p)
        }
    }
}

/**
 * The weekday with the orange date dot after it (today only). The dot is a fixed-size circle
 * whose centre sits half an x-height above the baseline, so it lines up with the lowercase letters
 * at every font size, as in the wordmark.
 */
@Composable
private fun WeekdayHeading(date: LocalDate, isToday: Boolean, style: TextStyle, dot: Dp, modifier: Modifier = Modifier) {
    val p = LocalTimePrefs.current
    val density = LocalDensity.current
    val lift = with(density) { (style.fontSize * 0.26f).roundToPx() }
    Row(modifier) {
        Text(Fmt.weekdayShort(date.dayOfWeek, p), style = style, color = DsTheme.colors.textPrimary, maxLines = 1, modifier = Modifier.alignByBaseline())
        if (isToday) {
            Box(
                Modifier
                    .padding(start = dot / 2)
                    .alignBy { it.measuredHeight / 2 + lift }
                    .size(dot)
                    .clip(CircleShape)
                    .background(DsTheme.colors.accentFill),
            )
        }
    }
}

/** "5 events · 3 tasks · 1 overdue · free after 1:30 PM", or that nothing is scheduled. */
@Composable
private fun compactLine(s: TodayUiState, p: TimePrefs): String {
    if (s.isLoading) return " "
    val parts = buildList {
        if (s.eventCount > 0) add(pluralStringResource(R.plurals.n_events, s.eventCount, s.eventCount))
        if (s.taskCount > 0) add(pluralStringResource(R.plurals.n_tasks, s.taskCount, s.taskCount))
        if (s.overdueCount > 0) add(pluralStringResource(R.plurals.n_overdue_short, s.overdueCount, s.overdueCount))
        val a = s.availability
        if (a != null && (s.eventCount > 0 || s.taskCount > 0)) {
            when {
                a.isFree -> add(stringResource(if (s.date == s.today) R.string.compact_free_now else R.string.compact_free_all))
                a.freeAfter != null -> add(stringResource(if (a.mostly) R.string.compact_mostly_free_after else R.string.compact_free_after, Fmt.time(a.freeAfter, p)))
            }
        }
    }
    return if (parts.isEmpty()) stringResource(R.string.compact_nothing) else parts.joinToString(" · ")
}

private const val IC_EVENT = "ic_event"
private const val IC_TASK = "ic_task"
private const val IC_OVERDUE = "ic_overdue"

/**
 * The greeting and briefing, e.g. "Good morning, Sam. You have [▦] 3 events, [✓] 2 tasks and
 * [!] 1 overdue task today. You're mostly free after 4:00 PM." Counts come straight from the
 * day's data; the availability sentence from [app.daysemble.core.time.Availability].
 */
@Composable
private fun briefing(s: TodayUiState, now: Instant, name: String?, p: TimePrefs): Pair<AnnotatedString, Map<String, InlineTextContent>> {
    val c = DsTheme.colors
    val strong = SpanStyle(color = c.textPrimary)
    val hour = now.atZone(p.zone).hour
    val greeting = stringResource(
        when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            in 17..21 -> R.string.greeting_evening
            else -> R.string.greeting_night
        },
    )
    val isToday = s.date == s.today
    val past = s.date < s.today
    val dayName = when {
        abs(ChronoUnit.DAYS.between(s.today, s.date)) <= 6 -> Fmt.weekdayFull(s.date.dayOfWeek, p)
        else -> Fmt.smartDate(s.date, s.today, p)
    }
    val lead = when {
        isToday -> stringResource(R.string.briefing_today)
        s.date == s.today.plusDays(1) -> stringResource(R.string.briefing_tomorrow)
        s.date == s.today.minusDays(1) -> stringResource(R.string.briefing_yesterday)
        past -> stringResource(R.string.briefing_past_day, dayName)
        else -> stringResource(R.string.briefing_future_day, dayName)
    }
    val parts = buildList {
        if (s.eventCount > 0) add(IC_EVENT to pluralStringResource(R.plurals.n_events, s.eventCount, s.eventCount))
        if (s.taskCount > 0) add(IC_TASK to pluralStringResource(R.plurals.n_tasks, s.taskCount, s.taskCount))
        if (s.overdueCount > 0) add(IC_OVERDUE to pluralStringResource(R.plurals.n_overdue, s.overdueCount, s.overdueCount))
    }
    val and = stringResource(R.string.summary_and)
    val nothing = when {
        isToday -> stringResource(R.string.briefing_nothing_today)
        s.date == s.today.plusDays(1) -> stringResource(R.string.briefing_nothing_tomorrow)
        past -> stringResource(R.string.briefing_nothing_past, dayName)
        else -> stringResource(R.string.briefing_nothing_day, dayName)
    }
    val a = s.availability
    val availability: Pair<String, List<String>>? = if (a == null || parts.isEmpty()) null else when (val sum = a.summary()) {
        AvailabilitySummary.FreeAllDay -> stringResource(if (isToday) R.string.avail_free_rest else R.string.avail_free_all) to emptyList()
        is AvailabilitySummary.FreeAfter ->
            stringResource(if (sum.mostly) R.string.avail_mostly_free_after else R.string.avail_free_after) to listOf(Fmt.time(sum.at, p))
        is AvailabilitySummary.LongestBreak ->
            stringResource(R.string.avail_longest_break) to listOf(Fmt.time(sum.range.start, p), Fmt.time(sum.range.endInclusive, p))
        is AvailabilitySummary.ShortBreaks ->
            stringResource(R.string.avail_short_breaks) to listOf(durationText(sum.total.roundToMinutes()))
        AvailabilitySummary.Booked -> stringResource(if (isToday) R.string.avail_booked_rest else R.string.avail_booked) to emptyList()
    }

    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = c.textSecondary)) {
            if (s.isLoading) { append(" "); return@withStyle }
            append(greeting)
            if (name != null) { append(", "); withStyle(strong) { append(name) } }
            append(". ")
            if (parts.isEmpty()) {
                append(nothing)
            } else {
                append(lead); append(" ")
                parts.forEachIndexed { i, (icon, label) ->
                    if (i > 0) append(if (i == parts.lastIndex) " $and " else ", ")
                    // The icon is decorative (the words say the same); its text stand-in is invisible.
                    appendInlineContent(icon, "\u2060")
                    append(" ")
                    withStyle(strong) { append(label) }
                }
                append(if (isToday) " " + stringResource(R.string.briefing_today_suffix) else ".")
            }
            availability?.let { (template, args) -> append(" "); appendTemplate(template, args, strong) }
        }
    }
    val icon = @Composable { v: ImageVector, tint: Color -> Icon(v, null, tint = tint, modifier = Modifier.fillMaxSize()) }
    val box = Placeholder(0.95.em, 0.95.em, PlaceholderVerticalAlign.TextCenter)
    val icons = mapOf(
        IC_EVENT to InlineTextContent(box) { icon(Icons.Rounded.CalendarMonth, c.textPrimary) },
        IC_TASK to InlineTextContent(box) { icon(Icons.Rounded.CheckCircleOutline, c.textPrimary) },
        IC_OVERDUE to InlineTextContent(box) { icon(Icons.Rounded.ErrorOutline, c.error) },
    )
    return text to icons
}

/** Appends a "%1$s"-style template with its arguments emphasised (keeps sentences translatable). */
private fun AnnotatedString.Builder.appendTemplate(template: String, args: List<String>, strong: SpanStyle) {
    val rx = Regex("%(\\d+\\$)?s")
    var last = 0
    var n = 0
    rx.findAll(template).forEach { m ->
        append(template.substring(last, m.range.first))
        val idx = m.groupValues[1].takeIf { it.isNotEmpty() }?.dropLast(1)?.toInt()?.minus(1) ?: n
        withStyle(strong) { append(args.getOrElse(idx) { "" }) }
        n++
        last = m.range.last + 1
    }
    append(template.substring(last))
}

/** Free and booked time from the day's events, and what's next; with the window it counts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Metrics(s: TodayUiState, p: TimePrefs) {
    val c = DsTheme.colors
    val a = s.availability ?: return
    Spacer(Modifier.height(Space.l))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Metric(Icons.Rounded.Schedule, c.success, stringResource(R.string.metric_free, durationText(a.free.roundToMinutes())))
        if (!a.isFree) Metric(Icons.Rounded.EventBusy, c.violet, stringResource(R.string.metric_booked, durationText(a.busy.roundToMinutes())))
        s.nextEvent?.let { e ->
            Metric(
                Icons.AutoMirrored.Rounded.ArrowForward, c.accent,
                stringResource(R.string.metric_next, e.title.ifBlank { stringResource(R.string.untitled_event) }, Fmt.time(e.start, p)),
            )
        }
    }
    Spacer(Modifier.height(Space.xs))
    Text(
        stringResource(R.string.metric_window_note, Fmt.time(Availability.DAY_START, p), Fmt.time(Availability.DAY_END, p)),
        style = DsTheme.type.label, color = c.textTertiary,
    )
}

private fun Duration.roundToMinutes(): Long = (toMillis() + 30_000) / 60_000

@Composable
private fun Metric(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(Sizes.iconSmall - 2.dp))
        Text(text, style = DsTheme.type.label, color = DsTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---- Agenda panel -----------------------------------------------------------------------------

private val PanelShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

@Composable
private fun AgendaPanel(
    state: TodayUiState,
    now: Instant,
    contentPadding: PaddingValues,
    panel: OverviewPanel,
    onToggle: () -> Unit,
    onSelectDate: (LocalDate) -> Unit,
    onToday: () -> Unit,
    onToggleTask: (String, Boolean) -> Unit,
    onOpenEvent: (String, String) -> Unit,
    onOpenTask: (String) -> Unit,
    onAddEvent: (LocalDate) -> Unit,
    onAddTask: (LocalDate) -> Unit,
) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val fling = AnchoredDraggableDefaults.flingBehavior(panel.state, positionalThreshold = { it / 2f }, animationSpec = panel.spec)
    Column(
        Modifier
            .fillMaxSize()
            // Light: a soft ambient shadow lifts the white panel off the white canvas. Dark: tone
            // and the hairline do it (shadows don't show on near-black).
            .then(if (!c.isDark) Modifier.shadow(14.dp, PanelShape, ambientColor = c.scrim, spotColor = Color.Transparent) else Modifier)
            .clip(PanelShape)
            .background(c.surface)
            .border(Sizes.hairline, c.outline, PanelShape),
    ) {
        // The handle row: dragging it (or the week strip under it) moves the panel; tapping toggles.
        Column(Modifier.anchoredDraggable(panel.state, Orientation.Vertical, flingBehavior = fling)) {
            Handle(expanded = panel.state.targetValue == OverviewValue.Expanded, onToggle = onToggle)
            WeekStrip(selected = state.date, today = state.today, onSelect = onSelectDate)
        }
        DashedDivider(Modifier.padding(horizontal = Space.gutter, vertical = Space.xs))
        val list = rememberLazyListState()
        val startedAtTop = remember { booleanArrayOf(false) }
        val listScroll = remember(panel) { AgendaScroll(panel) { startedAtTop[0] } }
        var showCompleted by rememberSaveable { mutableStateOf(false) }
        LazyColumn(
            state = list,
            modifier = Modifier
                .weight(1f)
                .testTag(TodayTags.AGENDA)
                .pointerInput(list) {
                    // Remember whether the gesture began with the list at its top: only then may a
                    // pull down open the overview (see AgendaScroll).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        startedAtTop[0] = !list.canScrollBackward
                    }
                }
                .nestedScroll(listScroll),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + Space.xl),
        ) {
            if (state.date != state.today) {
                item(key = "backToToday") {
                    Row(Modifier.padding(horizontal = Space.gutter - Space.xs).then(itemMotion())) {
                        DsChip(stringResource(R.string.back_to_today), selected = false, onClick = onToday, role = Role.Button)
                    }
                }
            }
            if (!state.isLoading && state.allDay.isEmpty() && state.timeline.isEmpty() && state.overdue.isEmpty() && state.completed.isEmpty()) {
                item(key = "empty") {
                    StateMessage(
                        icon = Icons.Rounded.WbTwilight,
                        title = stringResource(R.string.today_empty_title),
                        body = stringResource(R.string.today_agenda_empty_body),
                        modifier = Modifier.padding(top = Space.l).then(itemMotion()),
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                DsButton(stringResource(R.string.add_event), { onAddEvent(state.date) }, style = DsButtonStyle.Secondary)
                                DsButton(stringResource(R.string.add_task), { onAddTask(state.date) }, style = DsButtonStyle.Secondary)
                            }
                        },
                    )
                }
            }
            items(state.allDay, key = { it.key }) { item ->
                AgendaEventCard(
                    item.event, now, onClick = { onOpenEvent(item.event.eventId, item.event.occurrenceKey) },
                    modifier = Modifier.then(itemMotion()),
                )
            }
            items(state.timeline, key = { it.key }) { item ->
                when (item) {
                    is TimelineItem.EventItem -> AgendaEventCard(
                        item.event, now,
                        onClick = { onOpenEvent(item.event.eventId, item.event.occurrenceKey) },
                        focused = item.event.stableKey == state.focusEventKey,
                        modifier = Modifier.then(itemMotion()),
                    )
                    is TimelineItem.TaskItem -> AgendaTaskCard(
                        item.task, now,
                        onToggle = { onToggleTask(item.task.id, it) },
                        onClick = { onOpenTask(item.task.id) },
                        modifier = Modifier.then(itemMotion()),
                    )
                }
            }
            if (state.overdue.isNotEmpty()) {
                item(key = "overdueHeader") {
                    SectionHeader(stringResource(R.string.section_overdue), Modifier.padding(top = Space.l).then(itemMotion()))
                }
                items(state.overdue, key = { "o:" + it.id }) { t ->
                    AgendaTaskCard(t, now, onToggle = { onToggleTask(t.id, it) }, onClick = { onOpenTask(t.id) }, showDate = true, modifier = Modifier.then(itemMotion()))
                }
            }
            if (state.completed.isNotEmpty()) {
                item(key = "completedHeader") {
                    CompletedHeader(state.completed.size, showCompleted, { showCompleted = !showCompleted }, Modifier.then(itemMotion()))
                }
                item(key = "completedList") {
                    AnimatedVisibility(visible = showCompleted, enter = m.expandIn(), exit = m.collapseOut()) {
                        Column {
                            state.completed.forEach { t ->
                                TaskRow(t, now, onToggle = { onToggleTask(t.id, it) }, onClick = { onOpenTask(t.id) }, showDue = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The drag handle; also a button (tap, or a screen reader's double tap) that toggles the overview. */
@Composable
private fun Handle(expanded: Boolean, onToggle: () -> Unit) {
    val c = DsTheme.colors
    val label = stringResource(R.string.overview_label)
    val state = stringResource(if (expanded) R.string.overview_expanded else R.string.overview_collapsed)
    val action = stringResource(if (expanded) R.string.overview_collapse else R.string.overview_expand)
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .clickable(onClick = onToggle, onClickLabel = action, role = Role.Button)
            .semantics {
                contentDescription = label
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(c.outlineStrong.copy(alpha = 0.45f)))
    }
}

@Composable
private fun CompletedHeader(count: Int, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, DsTheme.motion.standard(), label = "chev")
    Row(
        modifier
            .padding(top = Space.l)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.completed_count, count, count).uppercase(),
            style = DsTheme.type.caps, color = c.textSecondary, modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.ExpandMore,
            stringResource(if (expanded) R.string.cd_collapse else R.string.cd_expand),
            tint = c.textSecondary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

// ---- Panel state and gestures -----------------------------------------------------------------

/**
 * The overview panel's position (its top edge, in px from the top of Today) between two anchors,
 * plus the settling rule shared by the handle drag and the nested-scroll paths: a fling faster
 * than 125 dp/s goes in its direction, otherwise the nearer half wins.
 */
@Stable
internal class OverviewPanel(
    val state: AnchoredDraggableState<OverviewValue>,
    val spec: AnimationSpec<Float>,
    private val velocityThreshold: Float,
) {
    private val collapsedPos get() = state.anchors.positionOf(OverviewValue.Collapsed)
    private val expandedPos get() = state.anchors.positionOf(OverviewValue.Expanded)

    /** 0 = collapsed, 1 = expanded; read in the draw phase only. */
    fun progress(): Float {
        val o = state.offset
        val c = collapsedPos
        val e = expandedPos
        if (o.isNaN() || c.isNaN() || e.isNaN() || e <= c) return if (state.currentValue == OverviewValue.Expanded) 1f else 0f
        return ((o - c) / (e - c)).coerceIn(0f, 1f)
    }

    fun isAtExpanded(): Boolean = state.offset.let { !it.isNaN() && abs(it - expandedPos) < 0.5f }
    fun isAtAnchor(): Boolean = state.offset.let { !it.isNaN() && (abs(it - collapsedPos) < 0.5f || abs(it - expandedPos) < 0.5f) }
    fun isAboveCollapsed(): Boolean = state.offset.let { !it.isNaN() && it > collapsedPos + 0.5f }

    suspend fun settle(velocity: Float) {
        val o = state.offset
        if (o.isNaN()) return
        val target = when {
            abs(velocity) >= velocityThreshold -> if (velocity > 0) OverviewValue.Expanded else OverviewValue.Collapsed
            o - collapsedPos >= (expandedPos - collapsedPos) / 2 -> OverviewValue.Expanded
            else -> OverviewValue.Collapsed
        }
        state.animateTo(target, spec)
    }

    suspend fun animateTo(target: OverviewValue) = state.animateTo(target, spec)
}

@Composable
private fun rememberOverviewPanel(): OverviewPanel {
    val m = DsTheme.motion
    val density = LocalDensity.current
    // Survives tab switches (the tab stays composed), rotation and process death.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val state = remember { AnchoredDraggableState(if (expanded) OverviewValue.Expanded else OverviewValue.Collapsed) }
    LaunchedEffect(state) { snapshotFlow { state.settledValue }.collect { expanded = it == OverviewValue.Expanded } }
    // Follows the finger directly; on release a critically damped spring carries the gesture's
    // velocity into the settle. Reduced motion: a short tween, no spring.
    val spec: AnimationSpec<Float> = remember(m.reduced) {
        if (m.reduced) tween(m.short, easing = DsMotion.Standard) else spring(dampingRatio = 1f, stiffness = 650f)
    }
    val threshold = with(density) { 125.dp.toPx() }
    return remember(state, spec, threshold) { OverviewPanel(state, spec, threshold) }
}

/**
 * Drags on the overview area. Until the overview is fully expanded every drag moves the panel
 * (and the hidden full overview must not scroll). Once expanded, the overview scrolls first when
 * it's taller than its space (large text), and the rest of the drag moves the panel.
 */
private class OverviewScroll(private val panel: OverviewPanel) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || panel.isAtExpanded()) return Offset.Zero
        panel.state.dispatchRawDelta(available.y)
        return Offset(0f, available.y)
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, panel.state.dispatchRawDelta(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (!panel.isAtExpanded()) { panel.settle(available.y); available } else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (!panel.isAtAnchor()) { panel.settle(available.y); available } else Velocity.Zero
}

/**
 * Scrolling in the agenda list. Up: an expanded overview folds away first, then the list scrolls.
 * Down: the list scrolls; only a pull that started with the list at its top goes on to open the
 * overview. Flings never open it on their own.
 */
private class AgendaScroll(private val panel: OverviewPanel, private val startedAtTop: () -> Boolean) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val d = available.y
        return if (d < 0 && source == NestedScrollSource.UserInput && panel.isAboveCollapsed()) Offset(0f, panel.state.dispatchRawDelta(d)) else Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val d = available.y
        return if (d > 0 && source == NestedScrollSource.UserInput && startedAtTop()) Offset(0f, panel.state.dispatchRawDelta(d)) else Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (available.y < 0 && panel.isAboveCollapsed()) { panel.settle(available.y); available } else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (!panel.isAtAnchor()) { panel.settle(available.y); available } else Velocity.Zero
}
