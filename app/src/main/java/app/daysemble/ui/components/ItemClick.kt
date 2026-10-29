package app.daysemble.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import app.daysemble.R

/**
 * Tap and long-press on a task or agenda item, in one gesture detector: a tap opens the item's
 * detail screen ([onClick]); a long-press ([onLongClick]) opens its menu and never also fires
 * the tap. A press that turns into a scroll does neither (combinedClickable cancels on touch slop).
 * The handlers are read when the gesture ends, through [rememberUpdatedState], so after the list
 * recomposed or re-sorted under the finger the action goes to the item now in this slot's key,
 * i.e. the same item (lists key their items by id).
 *
 * Accessibility: the long-press is exposed as a labelled action ("More actions"), so TalkBack,
 * Switch Access and keyboards can reach it too; Enter on a focused item is the tap.
 */
@Composable
fun Modifier.itemClick(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    val haptics = LocalHapticFeedback.current
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    return this.combinedClickable(
        role = Role.Button,
        onClickLabel = stringResource(R.string.action_open),
        onLongClickLabel = if (onLongClick != null) stringResource(R.string.menu_more_actions) else null,
        onLongClick = if (onLongClick != null) ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); long?.invoke() }) else null,
        onClick = { click() },
    )
}
