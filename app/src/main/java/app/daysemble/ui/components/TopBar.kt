package app.daysemble.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.daysemble.R
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Space

/** Flat top bar for secondary screens: back, title, actions. No elevation or tint change on scroll. */
@Composable
fun DsTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            DsIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back), onBack)
        } else {
            Spacer(Modifier.width(Space.l))
        }
        Text(
            title,
            style = DsTheme.type.titleLarge,
            color = DsTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Space.xs)
                .semantics { heading() },
        )
        actions()
    }
}
