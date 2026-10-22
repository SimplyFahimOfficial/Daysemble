package app.daysemble.ui.brand

import androidx.annotation.BoolRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.daysemble.R

/**
 * The three supplied Daysemble logos. The mapping to supplied artwork is explicit and does not
 * depend on declaration or file order:
 *
 *  - [CLASSIC]  = supplied logo #1: cream tile, black orbit, coral + violet glass squares. DEFAULT.
 *  - [MIDNIGHT] = supplied logo #2: black tile, cream orbit, filled squares.
 *  - [OUTLINE]  = supplied logo #3: black tile, outlined squares and circles.
 *
 * [key] is the persisted value. Never rename keys, because that would reset users' choices.
 */
enum class LogoVariant(
    val key: String,
    @param:DrawableRes val artwork: Int,
    @param:BoolRes val suppliedFlag: Int,
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    /** Fully-qualified activity-alias that carries this launcher icon. */
    val launcherAlias: String,
    /** True when the tile itself is dark (used to pick contrasting chrome around it). */
    val isDarkTile: Boolean,
) {
    CLASSIC(
        key = "classic",
        artwork = R.drawable.logo_classic,
        suppliedFlag = R.bool.logo_classic_supplied,
        label = R.string.logo_classic,
        description = R.string.logo_classic_desc,
        launcherAlias = "app.daysemble.LauncherClassic",
        isDarkTile = false,
    ),
    MIDNIGHT(
        key = "midnight",
        artwork = R.drawable.logo_midnight,
        suppliedFlag = R.bool.logo_midnight_supplied,
        label = R.string.logo_midnight,
        description = R.string.logo_midnight_desc,
        launcherAlias = "app.daysemble.LauncherMidnight",
        isDarkTile = true,
    ),
    OUTLINE(
        key = "outline",
        artwork = R.drawable.logo_outline,
        suppliedFlag = R.bool.logo_outline_supplied,
        label = R.string.logo_outline,
        description = R.string.logo_outline_desc,
        launcherAlias = "app.daysemble.LauncherOutline",
        isDarkTile = true,
    );

    companion object {
        /** Supplied logo #1. Stated explicitly so it isn't derived from enum order. */
        val DEFAULT: LogoVariant = CLASSIC

        fun fromKey(key: String?): LogoVariant = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
