package app.daysemble.ui.brand

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.booleanResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.daysemble.R
import app.daysemble.ui.theme.DsTheme

val LocalLogoVariant = staticCompositionLocalOf { LogoVariant.DEFAULT }

/** True when the supplied artwork for [variant] is bundled (false = neutral placeholder). */
@Composable
fun isLogoSupplied(variant: LogoVariant): Boolean = booleanResource(variant.suppliedFlag)

/**
 * Renders the supplied logo artwork unmodified (scaled only). When the asset hasn't been
 * supplied yet, a neutral placeholder with a "missing" label is shown instead of a substitute logo.
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    variant: LogoVariant = LocalLogoVariant.current,
    size: Dp = 40.dp,
    decorative: Boolean = false,
) {
    val supplied = isLogoSupplied(variant)
    val label = stringResource(R.string.logo_content_description, stringResource(variant.label))
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .then(if (decorative) Modifier else Modifier.semantics { contentDescription = label }),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(variant.artwork),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size),
        )
        // Shown below the tile (never over it) so the placeholder stays clearly a placeholder.
        if (!supplied && size >= 56.dp) {
            Text(
                text = stringResource(R.string.logo_missing),
                // Plain style (no line-height trimming) so descenders are never clipped.
                style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, lineHeight = 16.sp, letterSpacing = 0.02.em),
                color = DsTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * The logo with the user's display name beside it (or the app name when none is set), as shown at the
 * top of Today. The name takes only the width it needs and ends with "…" when the space runs out, so
 * it never pushes the actions next to it off screen. Logo and text are centred on one line, and the
 * text uses the theme's line height, so tall scripts and large font sizes aren't clipped.
 */
@Composable
fun BrandNameplate(displayName: String?, modifier: Modifier = Modifier, logoSize: Dp = 30.dp) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BrandLogo(size = logoSize)
        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
        Text(
            text = displayName ?: stringResource(R.string.app_name),
            style = DsTheme.type.title,
            color = DsTheme.colors.textPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/**
 * The supplied horizontal wordmark. Its artwork has dark lettering made for light backgrounds, so on
 * dark themes (or before it is supplied) the logo tile plus the app name in type is shown instead.
 */
@Composable
fun BrandWordmark(modifier: Modifier = Modifier, height: Dp = 56.dp) {
    val supplied = booleanResource(R.bool.wordmark_supplied)
    val name = stringResource(R.string.app_name)
    if (supplied && !DsTheme.colors.isDark) {
        Image(
            painter = painterResource(R.drawable.wordmark),
            contentDescription = name,
            contentScale = ContentScale.Fit,
            modifier = modifier.height(height),
        )
    } else {
        androidx.compose.foundation.layout.Row(
            modifier.semantics(mergeDescendants = true) { contentDescription = name },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandLogo(size = height, decorative = true)
            androidx.compose.foundation.layout.Spacer(Modifier.size(12.dp))
            Text(name, style = DsTheme.type.headline, color = DsTheme.colors.textPrimary)
            androidx.compose.foundation.layout.Box(
                Modifier.padding(start = 2.dp, top = 10.dp).size(8.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape).background(DsTheme.colors.accentFill),
            )
        }
    }
}

