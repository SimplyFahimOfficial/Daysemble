package app.daysemble.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space

@Composable
fun DsSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val c = DsTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touch)
            .clip(Radii.pill)
            .background(c.surfaceVariant)
            .padding(start = Space.l, end = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = DsTheme.type.body, color = c.textTertiary, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = DsTheme.type.body.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = placeholder }
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        AnimatedVisibility(value.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            DsIconButton(Icons.Rounded.Close, stringResource(R.string.cd_clear_search), { onValueChange("") }, tint = c.textSecondary)
        }
    }
}

/**
 * Flat text field: label above, hairline container, inline error below.
 * [large] renders the title style used at the top of editors.
 */
@Composable
fun DsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    error: String? = null,
    singleLine: Boolean = true,
    large: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onImeAction: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
) {
    val c = DsTheme.colors
    val style: TextStyle = if (large) DsTheme.type.titleLarge else DsTheme.type.body
    Column(modifier.fillMaxWidth()) {
        if (label != null) {
            Text(label, style = DsTheme.type.label, color = c.textSecondary, modifier = Modifier.padding(bottom = Space.xs))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Sizes.touch)
                .then(
                    if (large) Modifier
                    else Modifier
                        .clip(Radii.control)
                        .background(c.surface)
                        .border(Sizes.hairline, if (error != null) c.error else c.outline, Radii.control)
                        .padding(horizontal = Space.m, vertical = Space.m),
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) Text(placeholder, style = style, color = c.textTertiary)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                textStyle = style.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = if (singleLine) imeAction else ImeAction.Default),
                keyboardActions = KeyboardActions(onAny = { onImeAction() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .semantics {
                        contentDescription = label ?: placeholder
                        if (error != null) error(error)
                    },
            )
        }
        AnimatedVisibility(error != null) {
            Text(error.orEmpty(), style = DsTheme.type.label, color = c.error, modifier = Modifier.padding(top = Space.xs))
        }
    }
}

/** A tappable value "pill" used for dates/times/options in editors. */
@Composable
fun DsValueChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    contentDescription: String? = null,
    emphasized: Boolean = false,
    enabled: Boolean = true,
) {
    val c = DsTheme.colors
    Row(
        modifier
            .heightIn(min = Sizes.touch)
            .then(if (contentDescription != null) Modifier.semantics(mergeDescendants = true) { this.contentDescription = "$contentDescription: $text" } else Modifier)
            .clip(Radii.control)
            .background(if (emphasized) c.inverse else c.surfaceVariant)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        if (icon != null) Icon(icon, null, tint = if (emphasized) c.onInverse else c.textSecondary, modifier = Modifier.size(Sizes.iconSmall))
        Text(text, style = DsTheme.type.bodyStrong, color = if (!enabled) c.textTertiary else if (emphasized) c.onInverse else c.textPrimary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
