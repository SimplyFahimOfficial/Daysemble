package app.daysemble.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.daysemble.R
import app.daysemble.ui.brand.BrandLogo
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Space

/**
 * Two short pages: what Daysemble is, then optional permissions and Google. Every step can be
 * skipped; nothing is requested without the user tapping a button that says what it's for.
 */
@Composable
fun OnboardingScreen(onDone: (openGoogle: Boolean) -> Unit) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Space.gutter, vertical = Space.xl),
    ) {
        AnimatedContent(
            page,
            transitionSpec = {
                if (m.reduced) fadeIn(m.enter(m.short)) togetherWith fadeOut(m.exit())
                else (fadeIn(m.enter(m.long)) + slideInHorizontally(m.enter(m.long)) { it / 8 }) togetherWith
                    (fadeOut(m.exit()) + slideOutHorizontally(m.exit()) { -it / 8 })
            },
            label = "onboarding",
            modifier = Modifier.weight(1f),
        ) { p ->
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (p == 0) Welcome() else Setup(onDone)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dots(page)
            Spacer(Modifier.weight(1f))
            if (page == 0) {
                DsButton(stringResource(R.string.onboarding_skip), { onDone(false) }, style = DsButtonStyle.Ghost)
                Spacer(Modifier.size(Space.s))
                DsButton(stringResource(R.string.onboarding_next), { page = 1 })
            } else {
                DsButton(stringResource(R.string.onboarding_start), { onDone(false) })
            }
        }
    }
}

@Composable
private fun Welcome() {
    val c = DsTheme.colors
    Spacer(Modifier.height(Space.x4))
    BrandLogo(size = 88.dp)
    Spacer(Modifier.height(Space.xxl))
    app.daysemble.ui.brand.BrandWordmark(Modifier.semantics { heading() }, height = 52.dp)
    Spacer(Modifier.height(Space.l))
    Text(stringResource(R.string.onboarding_tagline), style = DsTheme.type.summary, color = c.textSecondary)
    Spacer(Modifier.height(Space.xxl))
    listOf(R.string.onboarding_point_1, R.string.onboarding_point_2, R.string.onboarding_point_3).forEach {
        Row(Modifier.padding(vertical = Space.s), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            androidx.compose.foundation.layout.Box(Modifier.padding(top = 8.dp).size(8.dp).clip(Radii.pill).background(c.violet))
            Text(stringResource(it), style = DsTheme.type.body, color = c.textPrimary)
        }
    }
}

@Composable
private fun Setup(onDone: (Boolean) -> Unit) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    var granted by rememberSaveable {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Spacer(Modifier.height(Space.x4))
    Text(stringResource(R.string.onboarding_setup_title), style = DsTheme.type.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Space.s))
    Text(stringResource(R.string.onboarding_setup_body), style = DsTheme.type.body, color = c.textSecondary)
    Spacer(Modifier.height(Space.xxl))
    SetupCard(
        Icons.Rounded.Notifications, stringResource(R.string.onboarding_notifications_title), stringResource(R.string.onboarding_notifications_body),
        action = if (granted) stringResource(R.string.onboarding_allowed) else stringResource(R.string.action_allow),
        enabled = !granted,
    ) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
    Spacer(Modifier.height(Space.m))
    SetupCard(
        Icons.Rounded.AccountCircle, stringResource(R.string.onboarding_google_title), stringResource(R.string.onboarding_google_body),
        action = stringResource(R.string.connect_google), enabled = true,
    ) { onDone(true) }
}

@Composable
private fun SetupCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    val c = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(c.surface)
            .padding(Space.l),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            Icon(icon, null, tint = c.textSecondary)
            Text(title, style = DsTheme.type.title, color = c.textPrimary)
        }
        Spacer(Modifier.height(Space.xs))
        Text(body, style = DsTheme.type.bodySmall, color = c.textSecondary)
        Spacer(Modifier.height(Space.m))
        DsButton(action, onClick, style = DsButtonStyle.Secondary, enabled = enabled)
    }
}

@Composable
private fun Dots(page: Int) {
    val c = DsTheme.colors
    val desc = stringResource(R.string.page_x_of_y, page + 1, 2)
    Row(Modifier.semantics { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        repeat(2) { i ->
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = if (i == page) 20.dp else 8.dp, height = 8.dp).clip(Radii.pill)
                    .background(if (i == page) c.inverse else c.outlineStrong),
            )
        }
    }
}
