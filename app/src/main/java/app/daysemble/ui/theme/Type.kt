package app.daysemble.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val Family = FontFamily.Default

private fun style(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = Family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = tracking.em,
    // Trim keeps text optically centred in rows and prevents height jumps while animating.
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

/** Editorial type scale. Sizes are in sp so they follow the user's font scale. */
@Immutable
data class DsTypography(
    val display: TextStyle = style(44, 50, FontWeight.Bold, -0.02),
    val headline: TextStyle = style(28, 34, FontWeight.Bold, -0.01),
    val summary: TextStyle = style(22, 30, FontWeight.SemiBold, -0.01),
    val titleLarge: TextStyle = style(20, 26, FontWeight.SemiBold),
    val title: TextStyle = style(17, 22, FontWeight.SemiBold),
    val body: TextStyle = style(16, 22, FontWeight.Normal),
    val bodyStrong: TextStyle = style(16, 22, FontWeight.Medium),
    val bodySmall: TextStyle = style(14, 20, FontWeight.Normal),
    val label: TextStyle = style(13, 18, FontWeight.Medium),
    val caps: TextStyle = style(11, 14, FontWeight.SemiBold, 0.06),
    val numeric: TextStyle = style(15, 20, FontWeight.Medium).copy(
        fontFeatureSettings = "tnum",
    ),
)

val LocalDsTypography = staticCompositionLocalOf { DsTypography() }
