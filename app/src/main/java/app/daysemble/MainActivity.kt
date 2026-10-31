package app.daysemble

import android.content.Context
import android.content.Intent
import android.os.Bundle
import app.daysemble.data.db.OwnerType
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toDrawable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.data.prefs.AppSettings
import app.daysemble.ui.DaysembleRoot
import app.daysemble.ui.theme.DaysembleTheme
import kotlinx.coroutines.flow.Flow
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** A request to open an item, e.g. from a reminder notification. */
data class OpenRequest(val type: OwnerType, val id: String, val occurrenceKey: String?)

class MainActivity : ComponentActivity() {

    private val openRequests = MutableStateFlow<OpenRequest?>(null)
    /** A launcher shortcut to act on (New task, New event, Search); cleared once handled. */
    private val shortcutRequests = MutableStateFlow<AppShortcut?>(null)

    /** Latest reduced-motion decision (system setting or the user's override), read by the splash exit. */
    private var reducedMotion = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parse(intent)?.let { openRequests.value = it }
        AppShortcut.fromIntent(intent)?.let { shortcutRequests.value = it }
    }

    private fun parse(intent: Intent?): OpenRequest? {
        val type = intent?.getStringExtra(EXTRA_TYPE)?.let { runCatching { OwnerType.valueOf(it) }.getOrNull() } ?: return null
        val id = intent.getStringExtra(EXTRA_ID) ?: return null
        return OpenRequest(type, id, intent.getStringExtra(EXTRA_KEY))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        val container = (application as DaysembleApp).container
        if (savedInstanceState == null) {
            parse(intent)?.let { openRequests.value = it }
            AppShortcut.fromIntent(intent)?.let { shortcutRequests.value = it }
        }
        lifecycleScope.launch(Dispatchers.Default) { AppShortcuts.publish(applicationContext) }

        // Keep the splash until settings are read, so the first frame has the right theme.
        var settingsLoaded = false
        splash.setKeepOnScreenCondition { !settingsLoaded }
        splash.setOnExitAnimationListener { provider -> SplashExit.play(provider, reducedMotion) }

        val settingsFlow: Flow<AppSettings?> = container.settings.settings
        setContent {
            val settings: AppSettings? by settingsFlow.collectAsStateWithLifecycle(initialValue = null)
            val s = settings ?: return@setContent
            LaunchedEffect(Unit) { settingsLoaded = true }

            DaysembleTheme(
                themeMode = s.themeMode,
                logo = s.logo,
                reducedMotionOverride = s.reducedMotionOverride,
            ) {
                val dark = app.daysemble.ui.theme.DsTheme.colors.isDark
                val reduced = app.daysemble.ui.theme.DsTheme.motion.reduced
                androidx.compose.runtime.SideEffect { reducedMotion = reduced }
                // Applied while composing the frame (not one frame later), so the system bar icons and
                // the window behind the app switch together with the in-app theme.
                androidx.compose.runtime.DisposableEffect(dark) {
                    val bg = if (dark) app.daysemble.ui.theme.DarkDsColors.background else app.daysemble.ui.theme.LightDsColors.background
                    window.setBackgroundDrawable(bg.toArgb().toDrawable())
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    )
                    onDispose { }
                }
                DaysembleRoot(
                    container = container, settings = s,
                    openRequests = openRequests, onOpenHandled = { openRequests.value = null },
                    shortcutRequests = shortcutRequests, onShortcutHandled = { shortcutRequests.value = null },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_TYPE = "open_type"
        private const val EXTRA_ID = "open_id"
        private const val EXTRA_KEY = "open_key"

        fun openIntent(context: Context, type: OwnerType, id: String, occurrenceKey: String?): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TYPE, type.name).putExtra(EXTRA_ID, id).putExtra(EXTRA_KEY, occurrenceKey)
    }
}
