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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.daysemble.data.prefs.AppSettings
import app.daysemble.ui.DaysembleRoot
import app.daysemble.ui.theme.DaysembleTheme
import kotlinx.coroutines.flow.Flow

/** A request to open an item, e.g. from a reminder notification. */
data class OpenRequest(val type: OwnerType, val id: String, val occurrenceKey: String?)

class MainActivity : ComponentActivity() {

    private val openRequests = MutableStateFlow<OpenRequest?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parse(intent)?.let { openRequests.value = it }
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
        if (savedInstanceState == null) parse(intent)?.let { openRequests.value = it }

        // Keep the splash until settings are read, so the first frame has the right theme.
        var settingsLoaded = false
        splash.setKeepOnScreenCondition { !settingsLoaded }

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
                LaunchedEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    )
                }
                DaysembleRoot(
                    container = container, settings = s,
                    openRequests = openRequests, onOpenHandled = { openRequests.value = null },
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
