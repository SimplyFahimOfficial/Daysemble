package app.daysemble.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import kotlinx.coroutines.withTimeoutOrNull
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
    val targetDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Motion runs only when both the Appearance setting and Android's animator scale allow it:
    // "Full" never overrides Android's "Remove animations".
    val systemReduced = isSystemReducedMotion()
    val reduced = reducedMotionOverride == true || systemReduced
    val motion = remember(reduced) { DsMotion(reduced) }
    val crossfade = rememberThemeCrossfade(targetDark, motion)
    // The palette changes once, in one recomposition. (It used to animate every colour for 300 ms,
    // handing a new palette through a static composition local on each frame, so the whole app,
    // including the tabs kept composed under Settings, recomposed about 18 times per theme change.)
    val colors = if (crossfade.dark) DarkDsColors else LightDsColors
    val scheme = remember(colors) { colors.toMaterial() }
    val type = remember { DsTypography() }
    val materialType = remember(type) { materialTypography(type) }

    CompositionLocalProvider(
        LocalDsColors provides colors,
        LocalDsTypography provides type,
        LocalDsMotion provides motion,
        LocalLogoVariant provides logo,
    ) {
        MaterialTheme(colorScheme = scheme, typography = materialType) {
            Box(propagateMinConstraints = true) {
                Box(crossfade.content, propagateMinConstraints = true) { content() }
                // The old frame, on its own layer: each frame of the fade changes only this layer's
                // alpha (no re-recording of the app, no recomposition). Draws nothing at rest.
                Box(Modifier.matchParentSize().then(crossfade.overlay))
            }
        }
    }
}

/**
 * Theme switches as one cross-fade: the frame showing the old theme is captured, the palette is
 * swapped, and the capture fades out over the re-themed app. Only an image's alpha animates, in
 * the draw phase, so nothing recomposes per frame. Reduced motion, or a capture that fails, swaps
 * at once.
 */
@Stable
private class ThemeCrossfade(initialDark: Boolean) {
    var dark by mutableStateOf(initialDark)
    var snapshot by mutableStateOf<ImageBitmap?>(null)
    val fade = Animatable(0f)
    lateinit var layer: GraphicsLayer
    /** Records the app into [layer] (to capture it) and draws it. */
    val content: Modifier = Modifier.drawWithContent {
        layer.record { this@drawWithContent.drawContent() }
        drawLayer(layer)
    }
    val overlay: Modifier = Modifier
        .graphicsLayer { alpha = fade.value }
        .drawBehind { snapshot?.let { drawImage(it) } }
}

@Composable
private fun rememberThemeCrossfade(targetDark: Boolean, motion: DsMotion): ThemeCrossfade {
    val layer = rememberGraphicsLayer()
    val state = remember { ThemeCrossfade(targetDark) }
    state.layer = layer
    LaunchedEffect(targetDark) {
        if (state.dark == targetDark) return@LaunchedEffect
        val shot = if (motion.reduced || layer.size.width == 0) null
        else withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { runCatching { layer.toImageBitmap() }.getOrNull() }
        state.snapshot = shot
        state.fade.snapTo(1f)
        state.dark = targetDark
        if (shot != null) {
            try {
                state.fade.animateTo(0f, tween(motion.medium, easing = DsMotion.Standard))
            } finally {
                state.snapshot = null
            }
        }
    }
    return state
}

private const val CAPTURE_TIMEOUT_MS = 120L

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
