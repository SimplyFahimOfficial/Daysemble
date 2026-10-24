package app.daysemble.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.daysemble.R
import app.daysemble.data.db.SyncState
import app.daysemble.data.prefs.DISPLAY_NAME_MAX
import app.daysemble.data.prefs.normalizeDisplayName
import app.daysemble.domain.model.Priority
import app.daysemble.domain.model.Task
import app.daysemble.ui.brand.BrandLogo
import app.daysemble.ui.brand.BrandNameplate
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsTextField
import app.daysemble.ui.components.HairlineDivider
import app.daysemble.ui.components.TaskRow
import app.daysemble.ui.theme.DsMotion
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import java.time.Instant

private const val STEPS = 3

/**
 * First-use setup in three short steps: welcome, an optional display name, optional reminders and
 * Google. Nothing is required: "Skip setup" finishes straight away, and every later step can be
 * skipped too. Shown only until setup is finished (`onboarding_done`, persisted with the name in one
 * write by [app.daysemble.data.prefs.SettingsRepository.completeOnboarding]).
 *
 * The step, the typed name and whether the entrance already played are saved, so rotation and
 * process death resume where the user was without replaying the entrance.
 */
@Composable
fun OnboardingScreen(initialName: String?, onDone: (name: String?, openGoogle: Boolean) -> Unit) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    var page by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(initialName.orEmpty()) }
    var finished by remember { mutableStateOf(false) }
    val finish: (Boolean) -> Unit = { google ->
        // Guards against a double tap while the app fades in over this screen.
        if (!finished) { finished = true; onDone(normalizeDisplayName(name), google) }
    }

    // One entrance, played once (not on rotation, process recreation or resume), never with reduced motion.
    var introPlayed by rememberSaveable { mutableStateOf(false) }
    val intro = remember { Animatable(if (introPlayed || m.reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        introPlayed = true
        if (intro.value < 1f) intro.animateTo(1f, tween(640, easing = DsMotion.EmphasizedDecelerate))
    }
    val progress: () -> Float = { intro.value }

    BackHandler(enabled = page > 0) { page-- }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = Space.gutter),
    ) {
        // Fixed-height top row so the content below doesn't jump when the back button appears.
        Box(Modifier.fillMaxWidth().height(Sizes.touch), contentAlignment = Alignment.CenterStart) {
            androidx.compose.animation.AnimatedVisibility(page > 0, enter = fadeIn(m.standard(m.short)), exit = fadeOut(m.standard(m.short))) {
                // Pulled left so the arrow lines up with the text below, which starts at the gutter.
                DsIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back), { if (page > 0) page-- }, modifier = Modifier.offset(x = -Space.m))
            }
        }
        AnimatedContent(
            page,
            transitionSpec = { m.fadeThrough(Integer.signum(targetState - initialState), rtl) },
            label = "onboardingStep",
            modifier = Modifier.weight(1f),
        ) { p ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (p) {
                    0 -> Welcome(progress)
                    1 -> NameStep(name, { name = it.take(DISPLAY_NAME_MAX) }, onContinue = { page = 2 })
                    else -> Extras(onGoogle = { finish(true) })
                }
            }
        }
        // Actions stay in place across steps; only their labels change.
        Column(
            Modifier.fillMaxWidth().padding(top = Space.m, bottom = Space.l).entrance(progress, 4),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepDots(page)
            Spacer(Modifier.height(Space.l))
            when (page) {
                0 -> {
                    DsButton(stringResource(R.string.onboarding_get_started), { page = 1 }, Modifier.fillMaxWidth())
                    DsButton(stringResource(R.string.onboarding_skip_setup), { finish(false) }, Modifier.fillMaxWidth(), style = DsButtonStyle.Ghost)
                }
                1 -> {
                    DsButton(stringResource(R.string.onboarding_continue), { page = 2 }, Modifier.fillMaxWidth())
                    DsButton(stringResource(R.string.onboarding_skip), { name = ""; page = 2 }, Modifier.fillMaxWidth(), style = DsButtonStyle.Ghost)
                }
                else -> {
                    DsButton(stringResource(R.string.onboarding_start), { finish(false) }, Modifier.fillMaxWidth())
                    // Same height as the ghost button on the other steps, so the layout doesn't jump.
                    Spacer(Modifier.heightIn(min = Sizes.touch))
                }
            }
        }
    }
}

/**
 * Staggered entrance driven by one shared progress value (read in the draw phase only, so it doesn't
 * recompose): element [index] fades in and rises 16 dp. The whole sequence takes 640 ms; buttons
 * respond from the first frame.
 */
private fun Modifier.entrance(progress: () -> Float, index: Int): Modifier = graphicsLayer {
    val start = index * 0.12f
    val t = ((progress() - start) / 0.52f).coerceIn(0f, 1f)
    alpha = t
    translationY = (1f - t) * 16.dp.toPx()
}

@Composable
private fun Welcome(progress: () -> Float) {
    val c = DsTheme.colors
    Spacer(Modifier.height(Space.xl))
    BrandLogo(size = 64.dp, modifier = Modifier.entrance(progress, 0))
    Spacer(Modifier.height(Space.xxl))
    Text(
        stringResource(R.string.onboarding_headline), style = DsTheme.type.headline, color = c.textPrimary,
        modifier = Modifier.semantics { heading() }.entrance(progress, 1),
    )
    Spacer(Modifier.height(Space.m))
    Text(
        stringResource(R.string.onboarding_body), style = DsTheme.type.body, color = c.textSecondary,
        modifier = Modifier.entrance(progress, 2),
    )
    Spacer(Modifier.height(Space.xxl))
    DayPreview(Modifier.entrance(progress, 3))
    Spacer(Modifier.height(Space.l))
}

/**
 * A small, static example of a day built from the app's real components (the same task rows and
 * colours as Today). It is decoration: one description for screen readers and no touch handling.
 */
@Composable
private fun DayPreview(modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val desc = stringResource(R.string.onboarding_preview_cd)
    val now = remember { Instant.now() }
    fun task(id: String, title: String, done: Boolean, priority: Priority = Priority.NONE) = Task(
        id = id, listId = "preview", parentId = null, title = title, notes = "", dueDate = null, dueTime = null,
        priority = priority, isCompleted = done, completedAt = if (done) now else null,
        createdAt = now, updatedAt = now, sortKey = 0, isGoogle = false, syncState = SyncState.LOCAL,
    )
    val t1 = stringResource(R.string.onboarding_preview_task_1)
    val t2 = stringResource(R.string.onboarding_preview_task_2)
    val t3 = stringResource(R.string.onboarding_preview_task_3)
    val tasks = remember(t1, t2, t3) { listOf(task("p1", t1, true), task("p2", t2, false, Priority.HIGH), task("p3", t3, false)) }
    Column(
        modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clip(Radii.card)
            .background(c.surface)
            .border(Sizes.hairline, c.outline, Radii.card)
            .clearAndSetSemantics { contentDescription = desc }
            // Swallow touches so the sample rows can't be toggled.
            .pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
            }
            .padding(vertical = Space.m),
    ) {
        Text(
            stringResource(R.string.onboarding_preview_day).uppercase(), style = DsTheme.type.caps, color = c.textTertiary,
            modifier = Modifier.padding(horizontal = Space.l),
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(4.dp).height(36.dp).clip(Radii.pill).background(c.violet))
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.onboarding_preview_event), style = DsTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1)
                Text(stringResource(R.string.onboarding_preview_event_time), style = DsTheme.type.label, color = c.textSecondary, maxLines = 1)
            }
            Text(
                stringResource(R.string.onboarding_preview_in), style = DsTheme.type.label, color = c.accent, maxLines = 1,
                modifier = Modifier.clip(Radii.pill).background(c.accentContainer).padding(horizontal = Space.s, vertical = Space.xxs),
            )
        }
        HairlineDivider(inset = true)
        tasks.forEach { t -> TaskRow(t, now, onToggle = {}, onClick = {}, showDue = false) }
    }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, onContinue: () -> Unit) {
    val c = DsTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Spacer(Modifier.height(Space.xl))
    Text(stringResource(R.string.onboarding_name_title), style = DsTheme.type.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Space.s))
    Text(stringResource(R.string.onboarding_name_body), style = DsTheme.type.body, color = c.textSecondary)
    Spacer(Modifier.height(Space.xxl))
    // Live preview of the top of Today, so it's clear where the name appears.
    BrandNameplate(
        normalizeDisplayName(name),
        Modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(c.surface)
            .border(Sizes.hairline, c.outline, Radii.card)
            .padding(horizontal = Space.l, vertical = Space.m),
    )
    Spacer(Modifier.height(Space.l))
    DsTextField(
        value = name, onValueChange = onName,
        placeholder = stringResource(R.string.onboarding_name_hint),
        imeAction = ImeAction.Done, onImeAction = onContinue,
        focusRequester = focus, capitalization = KeyboardCapitalization.Words,
    )
    Spacer(Modifier.height(Space.l))
}

@Composable
private fun Extras(onGoogle: () -> Unit) {
    val c = DsTheme.colors
    val ctx = LocalContext.current
    var granted by rememberSaveable {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Spacer(Modifier.height(Space.xl))
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
        action = stringResource(R.string.connect_google), enabled = true, onClick = onGoogle,
    )
    Spacer(Modifier.height(Space.l))
}

@Composable
private fun SetupCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    val c = DsTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(c.surface)
            .border(Sizes.hairline, c.outline, Radii.card)
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

/** Progress for the three steps; the current one is a longer pill that glides with the step. */
@Composable
private fun StepDots(page: Int) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val desc = stringResource(R.string.onboarding_step, page + 1, STEPS)
    Row(Modifier.semantics { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        repeat(STEPS) { i ->
            val w by animateDpAsState(if (i == page) 20.dp else 8.dp, if (m.reduced) androidx.compose.animation.core.snap() else m.indicator(), label = "stepDot")
            val color by androidx.compose.animation.animateColorAsState(if (i == page) c.inverse else c.outlineStrong, m.indicator(), label = "stepDotColor")
            Box(Modifier.size(width = w, height = 8.dp).clip(Radii.pill).background(color))
        }
    }
}
