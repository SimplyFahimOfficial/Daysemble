package app.daysemble.ui.brand

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Enables exactly one launcher activity-alias matching the chosen [LogoVariant].
 * Uses DONT_KILL_APP so the running app isn't killed. Some launchers refresh the icon
 * only after a short delay; the UI says so.
 */
class LauncherIconSwitcher(private val context: Context) {

    fun apply(variant: LogoVariant): Boolean = runCatching {
        val pm = context.packageManager
        // Enable the target first so the app never has zero launcher entries.
        set(pm, variant.launcherAlias, enabled = true)
        LogoVariant.entries.filter { it != variant }.forEach { set(pm, it.launcherAlias, enabled = false) }
        true
    }.getOrDefault(false)

    fun current(): LogoVariant? = runCatching {
        val pm = context.packageManager
        LogoVariant.entries.firstOrNull { v ->
            val state = pm.getComponentEnabledSetting(ComponentName(context, v.launcherAlias))
            when (state) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                // DEFAULT means "as declared in the manifest", where only CLASSIC is enabled.
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> v == LogoVariant.CLASSIC
                else -> false
            }
        }
    }.getOrNull()

    private fun set(pm: PackageManager, alias: String, enabled: Boolean) {
        val component = ComponentName(context, alias)
        val desired = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(component) != desired) {
            pm.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
        }
    }
}
