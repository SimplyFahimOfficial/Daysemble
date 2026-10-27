package app.daysemble.ui.theme

import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.daysemble.data.prefs.ThemeMode
import app.daysemble.ui.brand.LocalLogoVariant
import app.daysemble.ui.brand.LogoVariant

object DsTheme {
    val colors: DsColors
        @Composable @ReadOnlyComposable get() = LocalDsColors.current
    val type: DsTypography
        @Composable @ReadOnlyComposable get() = LocalDsTypography.current
    val motion: DsMotion
        @Composable @ReadOnlyComposable get() = LocalDsMotion.current
}

/** Android's "Remove animations" setting, re-read whenever the app resumes. */
@Composable
fun isSystemReducedMotion(): Boolean {
    val ctx = LocalContext.current
    fun read() = runCatching {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    var reduced by androidx.compose.runtime.remember(ctx) { androidx.compose.runtime.mutableStateOf(read()) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { reduced = read() }
    return reduced
}

/**
 * Hands the chosen theme to the system (Android 12+), which draws the launch splash before the
 * app runs and otherwise follows the phone: with this, the next launch's splash is white for
 * Light and black for Dark (System = no override). Changing it is a uiMode configuration change,
 * which MainActivity handles in place (configChanges="uiMode"), so nothing is recreated; Compose
 * picks up the new configuration itself.
 */
@Composable
fun SyncSystemNightMode(mode: ThemeMode) {
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(mode) {
        if (android.os.Build.VERSION.SDK_INT < 31) return@LaunchedEffect
        val target = when (mode) {
            ThemeMode.LIGHT -> android.app.UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> android.app.UiModeManager.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> android.app.UiModeManager.MODE_NIGHT_AUTO
        }
        runCatching { ctx.getSystemService(android.app.UiModeManager::class.java)?.setApplicationNightMode(target) }
    }
}

@Composable
fun DaysembleTheme(
    themeMode: ThemeMode = ThemeMode.LIGHT,
    logo: LogoVariant = LogoVariant.DEFAULT,
    reducedMotionOverride: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val reduced = reducedMotionOverride ?: isSystemReducedMotion()
    val motion = remember(reduced) { DsMotion(reduced) }
    val target = if (dark) DarkDsColors else LightDsColors
    val colors = animatedColors(target, motion)

    CompositionLocalProvider(
        LocalDsColors provides colors,
        LocalDsTypography provides DsTypography(),
        LocalDsMotion provides motion,
        LocalLogoVariant provides logo,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = materialTypography(DsTypography()),
            content = content,
        )
    }
}

/**
 * Cross-fades every theme colour together on theme change, so switching modes is one smooth change
 * (previously only six surfaces/text colours animated and accents, pills and inverse surfaces cut).
 */
@Composable
private fun animatedColors(target: DsColors, motion: DsMotion): DsColors {
    val spec = tween<androidx.compose.ui.graphics.Color>(motion.long, easing = DsMotion.Standard)
    @Composable fun a(c: androidx.compose.ui.graphics.Color, label: String) = animateColorAsState(c, spec, label = label).value
    return target.copy(
        background = a(target.background, "background"),
        surface = a(target.surface, "surface"),
        surfaceRaised = a(target.surfaceRaised, "surfaceRaised"),
        surfaceVariant = a(target.surfaceVariant, "surfaceVariant"),
        outline = a(target.outline, "outline"),
        outlineStrong = a(target.outlineStrong, "outlineStrong"),
        textPrimary = a(target.textPrimary, "textPrimary"),
        textSecondary = a(target.textSecondary, "textSecondary"),
        textTertiary = a(target.textTertiary, "textTertiary"),
        accent = a(target.accent, "accent"),
        accentFill = a(target.accentFill, "accentFill"),
        accentStrong = a(target.accentStrong, "accentStrong"),
        onAccent = a(target.onAccent, "onAccent"),
        inverseAccent = a(target.inverseAccent, "inverseAccent"),
        accentContainer = a(target.accentContainer, "accentContainer"),
        violet = a(target.violet, "violet"),
        violetContainer = a(target.violetContainer, "violetContainer"),
        success = a(target.success, "success"),
        warning = a(target.warning, "warning"),
        error = a(target.error, "error"),
        errorContainer = a(target.errorContainer, "errorContainer"),
        inverse = a(target.inverse, "inverse"),
        onInverse = a(target.onInverse, "onInverse"),
        scrim = a(target.scrim, "scrim"),
    )
}

private fun DsColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        // Material components use primary for text too, so it is the text-safe orange; onPrimary is
        // the colour that reads on it in this theme (white on the deep light-theme orange, near-black
        // on the light dark-theme orange). Checked in ColorContrastTest.
        primary = accent,
        onPrimary = onInverse,
        primaryContainer = accentContainer,
        onPrimaryContainer = textPrimary,
        secondary = violet,
        onSecondary = if (isDark) Brand.Ink else androidx.compose.ui.graphics.Color.White,
        secondaryContainer = violetContainer,
        onSecondaryContainer = textPrimary,
        tertiary = violet,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = background,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceRaised,
        surfaceContainerHighest = surfaceVariant,
        surfaceBright = surfaceRaised,
        surfaceDim = background,
        outline = outlineStrong,
        outlineVariant = outline,
        error = error,
        onError = if (isDark) Brand.Ink else androidx.compose.ui.graphics.Color.White,
        errorContainer = errorContainer,
        onErrorContainer = textPrimary,
        inverseSurface = inverse,
        inverseOnSurface = onInverse,
        inversePrimary = inverseAccent,
        scrim = scrim,
    )
}

private fun materialTypography(t: DsTypography) = Typography(
    displayLarge = t.display,
    displayMedium = t.display,
    displaySmall = t.headline,
    headlineLarge = t.headline,
    headlineMedium = t.headline,
    headlineSmall = t.titleLarge,
    titleLarge = t.titleLarge,
    titleMedium = t.title,
    titleSmall = t.label,
    bodyLarge = t.body,
    bodyMedium = t.bodySmall,
    bodySmall = t.label,
    labelLarge = t.label,
    labelMedium = t.label,
    labelSmall = t.caps,
)
