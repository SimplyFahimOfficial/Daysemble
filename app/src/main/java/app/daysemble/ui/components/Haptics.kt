package app.daysemble.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * The app's only haptics entry point. It goes through Compose's [HapticFeedback], which follows the
 * system "touch feedback" setting, so nothing buzzes for a user who turned haptics off. Haptics are
 * for commits and selections, never for decoration.
 *
 * - [tick]: a day, chip or segment selected; a swipe reaching its threshold.
 * - [confirm]: a task completed, something saved.
 * - [reject]: a delete.
 */
@Stable
class DsHaptics internal constructor(private val feedback: HapticFeedback) {
    fun tick() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
    fun confirm() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)
    fun reject() = feedback.performHapticFeedback(HapticFeedbackType.Reject)
}

@Composable
fun rememberHaptics(): DsHaptics {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { DsHaptics(feedback) }
}
