package app.daysemble.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** A selectable IANA zone with its current UTC offset, e.g. "Europe/Berlin · GMT+02:00". */
data class ZoneOption(val id: String, val label: String, val offsetSeconds: Int)

object Zones {
    /** Region zones only (no "Etc/GMT+3" style ids), plus UTC, sorted by offset then name. */
    fun all(now: Instant = Instant.now(), locale: Locale = Locale.getDefault()): List<ZoneOption> =
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") || it == "UTC" }
            .map { id ->
                val z = ZoneId.of(id)
                val off = z.rules.getOffset(now)
                ZoneOption(id, "${id.replace('_', ' ')} · ${offsetLabel(off.totalSeconds)} · ${z.getDisplayName(TextStyle.SHORT, locale)}", off.totalSeconds)
            }
            .sortedWith(compareBy({ it.offsetSeconds }, { it.id }))

    fun offsetLabel(seconds: Int): String {
        if (seconds == 0) return "GMT"
        val sign = if (seconds < 0) "-" else "+"
        val abs = kotlin.math.abs(seconds)
        return "GMT$sign%02d:%02d".format(abs / 3600, (abs % 3600) / 60)
    }

    fun matches(o: ZoneOption, q: String): Boolean {
        val t = q.trim()
        return t.isEmpty() || o.label.contains(t, ignoreCase = true) || o.id.contains(t.replace(' ', '_'), ignoreCase = true)
    }
}

/** Searchable time-zone chooser. */
@Composable
fun ZonePickerDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val c = DsTheme.colors
    val all = remember { Zones.all() }
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(query) { all.filter { Zones.matches(it, query) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = Radii.dialog,
        containerColor = c.surfaceRaised,
        title = { Text(stringResource(R.string.zone_picker_title), style = DsTheme.type.titleLarge, color = c.textPrimary) },
        text = {
            Column {
                DsSearchField(query, { query = it }, stringResource(R.string.zone_search_hint))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp).padding(top = Space.s).selectableGroup()) {
                    items(shown, key = { it.id }) { z ->
                        DsRadioRow(z.label, z.id == current, { onPick(z.id); onDismiss() })
                    }
                }
                if (shown.isEmpty()) Text(stringResource(R.string.zone_none), style = DsTheme.type.bodySmall, color = c.textSecondary)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = c.textSecondary) } },
    )
}
