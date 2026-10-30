package app.daysemble.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.daysemble.R
import app.daysemble.core.time.AppClock
import app.daysemble.core.time.Countdown
import app.daysemble.core.time.CountdownParts
import app.daysemble.core.time.CountdownState
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/**
 * The current instant, refreshed on every whole second while the caller is in composition AND its
 * lifecycle is at least STARTED (so it stops in the background and under the lock screen, and
 * resumes with a fresh reading). One ticker per visible countdown: lists don't use it (their
 * compact countdowns follow the screen's minute ticker), so no list recomposes every second.
 */
@Composable
fun rememberSecondTicker(enabled: Boolean, clock: AppClock = LocalAppClock.current): Instant {
    var now by remember(clock) { mutableStateOf(clock.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(enabled, clock, lifecycle) {
        if (!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = clock.now()
                delay(Countdown.msToNextSecond(clock.now()))
            }
        }
    }
    return now
}

// ---- Text --------------------------------------------------------------------------------------

/** "2 months · 4 days · 03:12:45": calendar units that aren't zero, then the time of day left. */
@Composable
fun countdownLong(p: CountdownParts): String {
    val units = buildList {
        if (p.years > 0) add(pluralStringResource(R.plurals.cd_years, p.years.toInt(), p.years.toInt()))
        if (p.months > 0) add(pluralStringResource(R.plurals.cd_months, p.months.toInt(), p.months.toInt()))
        if (p.days > 0) add(pluralStringResource(R.plurals.cd_days, p.days.toInt(), p.days.toInt()))
    }
    val clock = "%02d:%02d:%02d".format(p.hours, p.minutes, p.seconds)
    return (units + clock).joinToString(" · ")
}

/** The two largest units at minute precision, for cards: "2 mo 4 d", "3 h 12 min", "< 1 min". */
@Composable
fun countdownShort(p: CountdownParts): String {
    val units = listOf(
        p.years to R.string.cd_short_years, p.months to R.string.cd_short_months, p.days to R.string.cd_short_days,
        p.hours to R.string.cd_short_hours, p.minutes to R.string.cd_short_minutes,
    ).filter { it.first > 0 }.take(2)
    if (units.isEmpty()) return stringResource(R.string.cd_short_under_minute)
    return units.map { (v, res) -> stringResource(res, v) }.joinToString(" ")
}

/**
 * For screen readers, at minute precision ("Due in 2 months, 4 days, 3 hours and 12 minutes"):
 * it only changes once a minute, so a ticking countdown isn't re-announced every second.
 */
@Composable
fun countdownSpoken(state: CountdownState): String = when (state) {
    CountdownState.NoDeadline -> stringResource(R.string.cd_needs_due)
    CountdownState.Completed -> stringResource(R.string.cd_completed)
    is CountdownState.Remaining -> stringResource(R.string.cd_spoken_remaining, spokenParts(state.parts))
    is CountdownState.Overdue -> stringResource(R.string.cd_spoken_overdue, spokenParts(state.parts))
}

@Composable
private fun spokenParts(p: CountdownParts): String {
    val parts = buildList {
        if (p.years > 0) add(pluralStringResource(R.plurals.cd_years, p.years.toInt(), p.years.toInt()))
        if (p.months > 0) add(pluralStringResource(R.plurals.cd_months, p.months.toInt(), p.months.toInt()))
        if (p.days > 0) add(pluralStringResource(R.plurals.cd_days, p.days.toInt(), p.days.toInt()))
        if (p.hours > 0) add(pluralStringResource(R.plurals.cd_hours, p.hours.toInt(), p.hours.toInt()))
        if (p.minutes > 0) add(pluralStringResource(R.plurals.cd_minutes, p.minutes.toInt(), p.minutes.toInt()))
    }
    if (parts.isEmpty()) return stringResource(R.string.cd_less_than_minute)
    if (parts.size == 1) return parts.single()
    return parts.dropLast(1).joinToString(", ") + " " + stringResource(R.string.summary_and) + " " + parts.last()
}

// ---- Components --------------------------------------------------------------------------------

/**
 * Compact countdown pill for task cards and rows ("in 2 d 4 h", "3 h overdue"), at minute
 * precision from the [now] the list already has. Nothing for tasks without a deadline or done.
 */
@Composable
fun CountdownPill(deadline: Instant?, completed: Boolean, now: Instant, zone: ZoneId, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    when (val s = Countdown.state(deadline, completed, now, zone)) {
        is CountdownState.Remaining -> Pill(
            stringResource(R.string.cd_short_in, countdownShort(s.parts)), icon = Icons.Rounded.HourglassTop,
            fg = c.accent, bg = c.accentContainer, modifier = modifier, spoken = countdownSpoken(s),
        )
        is CountdownState.Overdue -> Pill(
            stringResource(R.string.cd_short_overdue, countdownShort(s.parts)), icon = Icons.Rounded.Warning,
            fg = c.error, bg = c.errorContainer, modifier = modifier, spoken = countdownSpoken(s),
        )
        else -> Unit
    }
}

/**
 * The full live countdown for a task's detail view: "2 months · 4 days · 03:12:45" and the deadline
 * under it. Ticks every second while visible; its own composable, so only this text recomposes.
 * Overdue shows how long ago it was due; completed and no-deadline states don't tick.
 */
@Composable
fun LiveCountdown(deadline: Instant?, completed: Boolean, modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val p = LocalTimePrefs.current
    val ticking = deadline != null && !completed
    val now = rememberSecondTicker(enabled = ticking)
    val state = Countdown.state(deadline, completed, now, p.zone)
    val spoken = countdownSpoken(state)
    val (fg, bg) = when (state) {
        is CountdownState.Overdue -> c.error to c.errorContainer
        is CountdownState.Remaining -> c.textPrimary to c.surfaceVariant
        else -> c.textSecondary to c.surfaceVariant
    }
    Column(
        modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(bg)
            .clearAndSetSemantics { contentDescription = spoken }
            .padding(horizontal = Space.l, vertical = Space.m),
    ) {
        // The kind of state changes (remaining → overdue, → completed) cross-fade; the ticking
        // digits inside one state change in place.
        AnimatedContent(state::class, transitionSpec = { m.fadeThrough(size = m.contentSize()) }, label = "countdownState") { kind ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                val icon = when (kind) {
                    CountdownState.Completed::class -> Icons.Rounded.CheckCircle
                    CountdownState.Overdue::class -> Icons.Rounded.Warning
                    else -> Icons.Rounded.HourglassTop
                }
                Icon(icon, null, tint = if (kind == CountdownState.Overdue::class) c.error else c.textSecondary, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(
                        when (kind) {
                            CountdownState.Overdue::class -> R.string.cd_label_overdue
                            CountdownState.Completed::class -> R.string.cd_label_completed
                            CountdownState.NoDeadline::class -> R.string.cd_label_countdown
                            else -> R.string.cd_label_remaining
                        },
                    ).uppercase(),
                    style = DsTheme.type.caps, color = if (kind == CountdownState.Overdue::class) c.error else c.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(Space.xs))
        val big = when (state) {
            is CountdownState.Remaining -> countdownLong(state.parts)
            is CountdownState.Overdue -> countdownLong(state.parts)
            CountdownState.Completed -> stringResource(R.string.cd_completed)
            CountdownState.NoDeadline -> stringResource(R.string.cd_needs_due)
        }
        Text(
            big,
            style = if (ticking) DsTheme.type.titleLarge.copy(fontFeatureSettings = "tnum") else DsTheme.type.body,
            color = fg,
        )
        if (deadline != null && state !is CountdownState.Completed) {
            Text(
                stringResource(R.string.cd_until, Fmt.mediumDate(deadline.atZone(p.zone).toLocalDate(), p) + " · " + Fmt.time(deadline, p)),
                style = DsTheme.type.label, color = c.textSecondary,
            )
        }
    }
}

/**
 * The countdown as the centre of a task's detail screen: the calendar part large ("2 months ·
 * 4 days", or "3 h 12 min" on the last day), the live clock part small under it ("03:12:45"), then
 * the deadline. Overdue reads "Overdue by …" in the error tone; never a negative number. It ticks
 * once a second only while visible and STARTED; it reads [deadline] on every tick, so a deadline
 * edited while the screen is open (or a zone change) shows at once. Only this composable recomposes.
 */
@Composable
fun CountdownHero(deadline: Instant?, completed: Boolean, modifier: Modifier = Modifier) {
    val p = LocalTimePrefs.current
    val ticking = deadline != null && !completed
    val now = rememberSecondTicker(enabled = ticking)
    val state = Countdown.state(deadline, completed, now, p.zone)
    val spoken = countdownSpoken(state)
    Column(modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken }) {
        CountdownHeroContent(state)
        if (deadline != null && state !is CountdownState.Completed) {
            CountdownHeroDeadline(deadline)
        }
    }
}

@Composable
private fun CountdownHeroContent(state: CountdownState) {
    val c = DsTheme.colors
    when (state) {
        is CountdownState.Remaining -> CountdownHeroActive(state.parts, overdue = false)
        is CountdownState.Overdue -> CountdownHeroActive(state.parts, overdue = true)
        CountdownState.Completed -> Text(stringResource(R.string.cd_completed), style = DsTheme.type.titleLarge, color = c.textSecondary)
        CountdownState.NoDeadline -> Text(stringResource(R.string.cd_needs_due), style = DsTheme.type.body, color = c.textSecondary)
    }
}

@Composable
private fun CountdownHeroActive(parts: CountdownParts, overdue: Boolean) {
    val c = DsTheme.colors
    Text(
        stringResource(if (overdue) R.string.cd_hero_overdue else R.string.cd_hero_remaining).uppercase(),
        style = DsTheme.type.caps, color = if (overdue) c.error else c.textSecondary,
    )
    Spacer(Modifier.height(Space.xs))
    Text(countdownHeroCalendarUnits(parts), style = DsTheme.type.hero, color = if (overdue) c.error else c.textPrimary)
    Text(
        "%02d:%02d:%02d".format(parts.hours, parts.minutes, parts.seconds),
        style = DsTheme.type.titleLarge.copy(fontFeatureSettings = "tnum"),
        color = if (overdue) c.error else c.accent,
    )
}

@Composable
private fun countdownHeroCalendarUnits(parts: CountdownParts): String {
    val units = buildList {
        if (parts.years > 0) add(pluralStringResource(R.plurals.cd_years, parts.years.toInt(), parts.years.toInt()))
        if (parts.months > 0) add(pluralStringResource(R.plurals.cd_months, parts.months.toInt(), parts.months.toInt()))
        if (parts.days > 0) add(pluralStringResource(R.plurals.cd_days, parts.days.toInt(), parts.days.toInt()))
    }
    return if (units.isNotEmpty()) units.joinToString(" · ") else countdownShort(parts.copy(seconds = 0))
}

@Composable
private fun CountdownHeroDeadline(deadline: Instant) {
    val p = LocalTimePrefs.current
    Spacer(Modifier.height(Space.xs))
    Text(
        stringResource(R.string.cd_until, Fmt.fullDate(deadline.atZone(p.zone).toLocalDate(), p) + " · " + Fmt.time(deadline, p)),
        style = DsTheme.type.label, color = DsTheme.colors.textSecondary,
    )
}

/** Small rounded label used on cards (due, duration, repeat, countdown). */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    fg: androidx.compose.ui.graphics.Color = DsTheme.colors.textSecondary,
    bg: androidx.compose.ui.graphics.Color = DsTheme.colors.background,
    spoken: String? = null,
) {
    Row(
        modifier
            .clip(Radii.pill)
            .background(bg)
            .then(if (spoken != null) Modifier.clearAndSetSemantics { contentDescription = spoken } else Modifier)
            .padding(horizontal = Space.s + Space.xxs, vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, style = DsTheme.type.label, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
