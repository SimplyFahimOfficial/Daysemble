package app.daysemble.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.daysemble.ui.theme.Space

/** One button of a dialog's action row; see [DsDialogButtons]. */
@Immutable
data class DsDialogAction(
    val label: String,
    val onClick: () -> Unit,
    val kind: Kind = Kind.Confirm,
    val enabled: Boolean = true,
) {
    enum class Kind {
        /** The action the dialog is for (Save, Done, Move). Ink pill; at most one per dialog. */
        Confirm,
        /** Confirm for something that can't be undone (Delete). */
        Destructive,
        /**
         * Another way out that isn't the main action (Clear, Use default, Only this event). Outlined:
         * a tonal fill nearly vanishes on the dark dialog surface.
         */
        Alternative,
        /** Leaves without changing anything. Lowest emphasis. */
        Cancel,
    }
}

/**
 * The one action row used by every dialog, in the AlertDialog's `confirmButton` slot (leave
 * `dismissButton` empty). The same kinds look the same everywhere: the main action is the ink
 * pill, a destructive one is red, alternatives are outlined and Cancel is plain text.
 *
 * Side by side (the default for up to two actions, and for short labels like a picker's Cancel,
 * Clear, Done): at the end, in the given order, Cancel first and the main action last, where the
 * thumb ends. [stacked] (the default for three or more, e.g. "this event / all events" of a
 * series): full width, one per line, choices first and Cancel last, so long labels never get cut
 * and each choice is an equally easy target.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DsDialogButtons(actions: List<DsDialogAction>, modifier: Modifier = Modifier, stacked: Boolean = actions.size > 2) {
    if (stacked) {
        val (cancel, choices) = actions.partition { it.kind == DsDialogAction.Kind.Cancel }
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            (choices + cancel).forEach { DialogButton(it, Modifier.fillMaxWidth()) }
        }
    } else {
        FlowRow(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            actions.forEach { DialogButton(it) }
        }
    }
}

@Composable
private fun DialogButton(a: DsDialogAction, modifier: Modifier = Modifier) {
    DsButton(
        text = a.label,
        onClick = a.onClick,
        modifier = modifier,
        enabled = a.enabled,
        style = when (a.kind) {
            DsDialogAction.Kind.Confirm -> DsButtonStyle.Primary
            DsDialogAction.Kind.Destructive -> DsButtonStyle.Destructive
            DsDialogAction.Kind.Alternative -> DsButtonStyle.Ghost
            DsDialogAction.Kind.Cancel -> DsButtonStyle.Text
        },
    )
}
