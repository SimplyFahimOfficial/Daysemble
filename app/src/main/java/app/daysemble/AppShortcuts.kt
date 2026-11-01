package app.daysemble

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * The launcher shortcuts (long-press the app icon): New task, New event, Search.
 *
 * They are dynamic shortcuts published by the app rather than a static `shortcuts.xml`, for two
 * reasons:
 * - The launcher entry is one of three activity aliases (one per logo) and only one is enabled.
 *   Static shortcuts belong to one alias's meta-data, so they would vanish when the logo changes.
 *   Dynamic shortcuts belong to the app.
 * - A static shortcut's intent needs the literal package name, which differs in debug builds
 *   (`.debug`).
 *
 * No new exported component or permission is involved. The intents target the non-exported
 * [MainActivity], which the launcher starts on the app's behalf.
 */
enum class AppShortcut(val id: String, val action: String) {
    NEW_TASK("new_task", "app.daysemble.action.NEW_TASK"),
    NEW_EVENT("new_event", "app.daysemble.action.NEW_EVENT"),
    SEARCH("search", "app.daysemble.action.SEARCH");

    companion object {
        fun fromIntent(intent: Intent?): AppShortcut? = intent?.action?.let { a -> entries.firstOrNull { it.action == a } }
    }
}

object AppShortcuts {
    /**
     * Publishes the shortcuts with labels in the current language. It's cheap and idempotent, and
     * it runs at every launch, so a language change updates the labels. Call it off the main thread
     * (it's a binder call). Failures are ignored: shortcuts are a convenience, and some launchers
     * don't support them.
     */
    fun publish(context: Context) {
        runCatching {
            val shortcuts = listOf(
                build(context, AppShortcut.NEW_TASK, R.string.shortcut_new_task, R.string.shortcut_new_task_long, R.drawable.ic_shortcut_task),
                build(context, AppShortcut.NEW_EVENT, R.string.shortcut_new_event, R.string.shortcut_new_event_long, R.drawable.ic_shortcut_event),
                build(context, AppShortcut.SEARCH, R.string.shortcut_search, R.string.shortcut_search_long, R.drawable.ic_shortcut_search),
            )
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    private fun build(context: Context, s: AppShortcut, short: Int, long: Int, icon: Int): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, s.id)
            .setShortLabel(context.getString(short))
            .setLongLabel(context.getString(long))
            .setIcon(IconCompat.createWithResource(context, icon))
            .setIntent(
                Intent(context, MainActivity::class.java)
                    .setAction(s.action)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            .build()
}
