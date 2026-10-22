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

@Composable
fun DaysembleTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
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

/** Cross-fades the core surfaces on theme change so switching modes isn't a hard cut. */
@Composable
private fun animatedColors(target: DsColors, motion: DsMotion): DsColors {
    val spec = tween<androidx.compose.ui.graphics.Color>(motion.long, easing = DsMotion.Standard)
    val bg by animateColorAsState(target.background, spec, label = "bg")
    val surface by animateColorAsState(target.surface, spec, label = "surface")
    val variant by animateColorAsState(target.surfaceVariant, spec, label = "variant")
    val text by animateColorAsState(target.textPrimary, spec, label = "text")
    val text2 by animateColorAsState(target.textSecondary, spec, label = "text2")
    val outline by animateColorAsState(target.outline, spec, label = "outline")
    return target.copy(
        background = bg, surface = surface, surfaceVariant = variant,
        textPrimary = text, textSecondary = text2, outline = outline,
    )
}

private fun DsColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accentFill,
        onPrimary = onAccent,
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
        inversePrimary = accent,
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
