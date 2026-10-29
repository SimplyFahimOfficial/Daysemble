package app.daysemble.ui.format

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.core.os.ConfigurationCompat
import app.daysemble.R
import app.daysemble.core.time.AppClock
import app.daysemble.core.time.Relative
import app.daysemble.core.time.RelativeTime
import app.daysemble.data.prefs.ClockFormat
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** User-dependent formatting preferences, provided at the root. */
data class TimePrefs(
    val use24h: Boolean,
    val firstDayOfWeek: DayOfWeek,
    val zone: ZoneId,
    val locale: Locale,
)

val LocalTimePrefs = staticCompositionLocalOf {
    TimePrefs(use24h = true, firstDayOfWeek = DayOfWeek.MONDAY, zone = ZoneId.systemDefault(), locale = Locale.getDefault())
}

val LocalAppClock = staticCompositionLocalOf<AppClock> { app.daysemble.core.time.SystemAppClock }

@Composable
fun rememberTimePrefs(clockFormat: ClockFormat, firstDay: DayOfWeek?, zone: ZoneId): TimePrefs {
    val ctx = LocalContext.current
    val config = LocalConfiguration.current
    val locale = ConfigurationCompat.getLocales(config)[0] ?: Locale.ROOT
    return remember(clockFormat, firstDay, zone, locale) {
        TimePrefs(
            use24h = when (clockFormat) {
                ClockFormat.SYSTEM -> DateFormat.is24HourFormat(ctx)
                ClockFormat.H24 -> true
                ClockFormat.H12 -> false
            },
            firstDayOfWeek = firstDay ?: WeekFields.of(locale).firstDayOfWeek,
            zone = zone,
            locale = locale,
        )
    }
}

object Fmt {
    fun time(t: LocalTime, p: TimePrefs): String =
        DateTimeFormatter.ofPattern(if (p.use24h) "HH:mm" else "h:mm a", p.locale).format(t)

    fun time(i: Instant, p: TimePrefs): String = time(i.atZone(p.zone).toLocalTime(), p)

    /** Compact hour for grid gutters: "14" / "2 PM". */
    fun hourShort(h: Int, p: TimePrefs): String =
        DateTimeFormatter.ofPattern(if (p.use24h) "HH" else "h a", p.locale).format(LocalTime.of(h, 0))

    fun weekdayShort(d: DayOfWeek, p: TimePrefs): String = d.getDisplayName(TextStyle.SHORT, p.locale)
    fun weekdayNarrow(d: DayOfWeek, p: TimePrefs): String = d.getDisplayName(TextStyle.NARROW, p.locale)
    fun weekdayFull(d: DayOfWeek, p: TimePrefs): String = d.getDisplayName(TextStyle.FULL, p.locale)

    fun monthDay(d: LocalDate, p: TimePrefs): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(p.locale, "MMMMd"), p.locale).format(d)

    fun monthDayShort(d: LocalDate, p: TimePrefs): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(p.locale, "MMMd"), p.locale).format(d)

    fun monthYear(d: LocalDate, p: TimePrefs): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(p.locale, "MMMMyyyy"), p.locale).format(d)

    fun fullDate(d: LocalDate, p: TimePrefs): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(p.locale).format(d)

    fun mediumDate(d: LocalDate, p: TimePrefs): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(p.locale, "EEEMMMd"), p.locale).format(d)

    /** "Mon, 9 Dec" style with year only when not the current year. */
    fun smartDate(d: LocalDate, today: LocalDate, p: TimePrefs): String =
        if (d.year == today.year) mediumDate(d, p)
        else DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(p.locale, "EEEMMMdyyyy"), p.locale).format(d)

    fun weekStart(d: LocalDate, p: TimePrefs): LocalDate {
        val diff = ((d.dayOfWeek.value - p.firstDayOfWeek.value) + 7) % 7
        return d.minusDays(diff.toLong())
    }
}

/** Relative day name ("Today"/"Tomorrow"/"Yesterday") or the smart date. */
@Composable
@ReadOnlyComposable
fun dayLabel(d: LocalDate, today: LocalDate, p: TimePrefs): String = when (d) {
    today -> stringResource(R.string.day_today)
    today.plusDays(1) -> stringResource(R.string.day_tomorrow)
    today.minusDays(1) -> stringResource(R.string.day_yesterday)
    else -> Fmt.smartDate(d, today, p)
}

@Composable
fun durationText(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> pluralStringResource(R.plurals.minutes_short, m.toInt(), m.toInt())
        m == 0L -> pluralStringResource(R.plurals.hours_short, h.toInt(), h.toInt())
        else -> stringResource(R.string.hours_minutes_short, h, m)
    }
}

@Composable
fun relativeText(r: Relative): String = when (r) {
    Relative.Now -> stringResource(R.string.rel_now)
    Relative.Ended -> stringResource(R.string.rel_ended)
    Relative.AllDayToday -> stringResource(R.string.rel_all_day_today)
    is Relative.StartsIn -> stringResource(R.string.rel_in, durationText(r.minutes))
    is Relative.EndsIn -> stringResource(R.string.rel_ends_in, durationText(r.minutes))
    is Relative.StartsInDays -> if (r.days == 1L) stringResource(R.string.rel_tomorrow)
    else pluralStringResource(R.plurals.rel_in_days, r.days.toInt(), r.days.toInt())
}

/**
 * Current instant, refreshed on each minute boundary while in composition and the lifecycle is at
 * least STARTED. It stops in the background and reads the clock again on return, so a screen
 * that comes back after hours shows the right "in 5 min" / countdown at once.
 */
@Composable
fun rememberMinuteTicker(clock: AppClock = LocalAppClock.current): Instant {
    var now by remember { mutableStateOf(clock.now()) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clock, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            now = clock.now()
            while (true) {
                delay(RelativeTime.nextTickDelayMs(clock.now()) + 50)
                now = clock.now()
            }
        }
    }
    return now
}
