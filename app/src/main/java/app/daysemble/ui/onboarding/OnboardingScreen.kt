package app.daysemble.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.daysemble.R
import app.daysemble.data.prefs.DISPLAY_NAME_MAX
import app.daysemble.data.prefs.normalizeDisplayName
import app.daysemble.ui.brand.BrandLogo
import app.daysemble.ui.brand.BrandNameplate
import app.daysemble.ui.components.DsButton
import app.daysemble.ui.components.DsButtonStyle
import app.daysemble.ui.components.DsIconButton
import app.daysemble.ui.components.DsTextField
import app.daysemble.ui.format.Fmt
import app.daysemble.ui.format.LocalAppClock
import app.daysemble.ui.format.LocalTimePrefs
import app.daysemble.ui.theme.DsMotion
import app.daysemble.ui.theme.DsTheme
import app.daysemble.ui.theme.Radii
import app.daysemble.ui.theme.Sizes
import app.daysemble.ui.theme.Space
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

private const val STEPS = 3

/**
 * First-use setup in three short steps: welcome, an optional display name, optional reminders and
 * Google. Nothing is required: "Skip setup" finishes straight away, and every later step can be
 * skipped too. Shown only until setup is finished (`onboarding_done`, persisted with the name in one
 * write by [app.daysemble.data.prefs.SettingsRepository.completeOnboarding]).
 *
 * This screen follows the system splash (a separate stage, see SplashExit); it adds no waiting of
 * its own. Layout: the step's content scrolls in the middle, the step indicator and actions stay
 * fixed at the bottom and only change their labels, so nothing jumps between steps.
 *
 * The step, the typed name and whether the entrance already played are saved, so rotation and
 * process death resume where the user was without replaying the entrance.
 */
@Composable
fun OnboardingScreen(initialName: String?, onDone: (name: String?, openGoogle: Boolean) -> Unit) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
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
        if (intro.value < 1f) intro.animateTo(1f, tween(m.entrance, easing = DsMotion.Standard))
    }
    val progress: () -> Float = { intro.value }

    BackHandler(enabled = page > 0) { page-- }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Fixed-height top row so the content below doesn't jump when the back button appears.
        Box(Modifier.fillMaxWidth().height(Sizes.touch).padding(horizontal = Space.xs), contentAlignment = Alignment.CenterStart) {
            androidx.compose.animation.AnimatedVisibility(page > 0, enter = fadeIn(m.standard(m.short)), exit = fadeOut(m.standard(m.short))) {
                DsIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back), { if (page > 0) page-- })
            }
        }
        AnimatedContent(
            page,
            transitionSpec = { m.fadeThrough(Integer.signum(targetState - initialState), rtl) },
            label = "onboardingStep",
            modifier = Modifier.weight(1f),
        ) { p ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.gutter + Space.xs),
            ) {
                when (p) {
                    0 -> Welcome(progress)
                    1 -> NameStep(name, { name = it.take(DISPLAY_NAME_MAX) }, onContinue = { page = 2 })
                    else -> Extras(onGoogle = { finish(true) })
                }
            }
        }
        // Actions stay in place across steps; only their labels change. Visible from the first frame.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.gutter + Space.xs)
                .padding(top = Space.m, bottom = Space.m),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepDots(page)
            Spacer(Modifier.height(Space.l))
            val primary = Modifier.fillMaxWidth().heightIn(min = 52.dp)
            when (page) {
                0 -> {
                    DsButton(stringResource(R.string.onboarding_get_started), { page = 1 }, primary)
                    DsButton(stringResource(R.string.onboarding_skip_setup), { finish(false) }, Modifier.fillMaxWidth(), style = DsButtonStyle.Text)
                }
                1 -> {
                    DsButton(stringResource(R.string.onboarding_continue), { page = 2 }, primary)
                    DsButton(stringResource(R.string.onboarding_skip), { name = ""; page = 2 }, Modifier.fillMaxWidth(), style = DsButtonStyle.Text)
                }
                else -> {
                    DsButton(stringResource(R.string.onboarding_start), { finish(false) }, primary)
                    // Same height as the text button on the other steps, so the layout doesn't jump.
                    Spacer(Modifier.heightIn(min = Sizes.touch))
                }
            }
        }
    }
}

/**
 * The entrance, in two groups driven by one progress value read in the draw phase (no
 * recomposition): the words first, then the illustration [index] = 1 a moment later. Each group
 * fades in while settling 12 dp. The actions don't animate: they work from the first frame.
 */
private fun Modifier.entrance(progress: () -> Float, index: Int): Modifier = graphicsLayer {
    val start = index * 0.18f
    val t = ((progress() - start) / (1f - 0.18f)).coerceIn(0f, 1f)
    val eased = DsMotion.EmphasizedDecelerate.transform(t)
    alpha = eased
    translationY = (1f - eased) * 12.dp.toPx()
}

@Composable
private fun Welcome(progress: () -> Float) {
    val c = DsTheme.colors
    Column(Modifier.entrance(progress, 0)) {
        Spacer(Modifier.height(Space.s))
        // Same tile as the launch splash, with the same soft shadow, so the hand-off reads as one logo.
        BrandLogo(
            size = 52.dp,
            modifier = Modifier.shadow(10.dp, RoundedCornerShape(percent = 23), ambientColor = c.scrim, spotColor = c.scrim.copy(alpha = c.scrim.alpha * 0.5f)),
        )
        Spacer(Modifier.height(Space.xxl))
        // The full stop is the coral brand dot from the wordmark.
        val headline = stringResource(R.string.onboarding_headline)
        Text(
            buildAnnotatedString {
                if (headline.endsWith(".")) {
                    append(headline.dropLast(1))
                    withStyle(SpanStyle(color = c.accentFill)) { append(".") }
                } else append(headline)
            },
            style = DsTheme.type.hero, color = c.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Space.m))
        Text(stringResource(R.string.onboarding_body), style = DsTheme.type.body, color = c.textSecondary)
    }
    Spacer(Modifier.height(Space.xxl))
    PlannerPreview(Modifier.entrance(progress, 1))
    Spacer(Modifier.height(Space.l))
}

/**
 * Illustration of a day in Daysemble: a week row (calendar), two events and three tasks, drawn with
 * the app's own colours and type. Decorative: one description for screen readers, no touch targets.
 * A second sheet peeks out underneath for depth; the only shadow is a soft one under the front card.
 */
@Composable
private fun PlannerPreview(modifier: Modifier = Modifier) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val clock = LocalAppClock.current
    val today = remember(clock, p.zone) { clock.now().atZone(p.zone).toLocalDate() }
    val desc = stringResource(R.string.onboarding_preview_cd)
    val card = RoundedCornerShape(22.dp)
    Box(
        modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clearAndSetSemantics { contentDescription = desc },
    ) {
        // The sheet behind: same shape, slightly narrower, peeking out at the bottom.
        Box(
            Modifier
                .matchParentSize()
                .padding(horizontal = Space.l)
                .offset(y = 10.dp)
                .clip(card)
                .background(c.surfaceVariant)
                .border(Sizes.hairline, c.outline, card),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(16.dp, card, ambientColor = c.scrim, spotColor = c.scrim.copy(alpha = c.scrim.alpha * 0.6f))
                .clip(card)
                .background(c.surface)
                .border(Sizes.hairline, c.outline, card)
                .padding(Space.l),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.weekdayShort(today.dayOfWeek, p), style = DsTheme.type.titleLarge, color = c.textPrimary)
                Box(Modifier.padding(start = 3.dp, bottom = 7.dp).size(6.dp).clip(CircleShape).background(c.accentFill))
                Spacer(Modifier.weight(1f))
                Text(Fmt.monthDay(today, p), style = DsTheme.type.label, color = c.textSecondary, modifier = Modifier.padding(bottom = 3.dp))
            }
            Spacer(Modifier.height(Space.m))
            WeekRow(today)
            Spacer(Modifier.height(Space.s))
            PreviewEvent(c.violet, stringResource(R.string.onboarding_preview_event), stringResource(R.string.onboarding_preview_event_time), stringResource(R.string.onboarding_preview_in))
            PreviewEvent(c.accentFill, stringResource(R.string.onboarding_preview_event_2), stringResource(R.string.onboarding_preview_event_2_time), null)
            Spacer(Modifier.height(Space.xs))
            Box(Modifier.fillMaxWidth().height(Sizes.hairline).background(c.outline))
            Spacer(Modifier.height(Space.xs))
            PreviewTask(stringResource(R.string.onboarding_preview_task_1), done = true, flagged = false)
            PreviewTask(stringResource(R.string.onboarding_preview_task_2), done = false, flagged = true)
            PreviewTask(stringResource(R.string.onboarding_preview_task_3), done = false, flagged = false)
        }
    }
}

@Composable
private fun WeekRow(today: java.time.LocalDate) {
    val c = DsTheme.colors
    val p = LocalTimePrefs.current
    val start = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(p.firstDayOfWeek))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (0 until 7).forEach { i ->
            val d = start.plusDays(i.toLong())
            val sel = d == today
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.weekdayNarrow(d.dayOfWeek, p), style = DsTheme.type.caps, color = if (sel) c.accent else c.textTertiary)
                Spacer(Modifier.height(Space.xs))
                Box(
                    Modifier.size(30.dp).clip(CircleShape).background(if (sel) c.inverse else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(d.dayOfMonth.toString(), style = DsTheme.type.label, color = if (sel) c.onInverse else c.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun PreviewEvent(color: Color, title: String, time: String, countdown: String?) {
    val c = DsTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs + Space.xxs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(32.dp).clip(Radii.pill).background(color))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(title, style = DsTheme.type.bodyStrong, color = c.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(time, style = DsTheme.type.label, color = c.textSecondary, maxLines = 1)
        }
        if (countdown != null) {
            Text(
                countdown, style = DsTheme.type.label, color = c.accent, maxLines = 1,
                modifier = Modifier.clip(Radii.pill).background(c.accentContainer).padding(horizontal = Space.s + Space.xxs, vertical = Space.xxs),
            )
        }
    }
}

@Composable
private fun PreviewTask(title: String, done: Boolean, flagged: Boolean) {
    val c = DsTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = Space.xs + Space.xxs), verticalAlignment = Alignment.CenterVertically) {
        val ring = if (done) c.textPrimary else c.outlineStrong
        val fill = c.textPrimary
        val tick = c.surface
        Canvas(Modifier.padding(start = 1.dp).size(18.dp)) {
            val stroke = 1.6.dp.toPx()
            val r = size.minDimension / 2 - stroke / 2
            if (done) {
                drawCircle(fill, r + stroke / 2)
                drawLine(tick, Offset(size.width * 0.30f, size.height * 0.52f), Offset(size.width * 0.44f, size.height * 0.66f), stroke, StrokeCap.Round)
                drawLine(tick, Offset(size.width * 0.44f, size.height * 0.66f), Offset(size.width * 0.71f, size.height * 0.38f), stroke, StrokeCap.Round)
            } else {
                drawCircle(ring, r, style = Stroke(stroke))
            }
        }
        Spacer(Modifier.width(Space.m))
        Text(
            title, style = DsTheme.type.body, color = if (done) c.textTertiary else c.textPrimary,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (flagged) Icon(Icons.Rounded.Flag, null, tint = c.accent, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun NameStep(name: String, onName: (String) -> Unit, onContinue: () -> Unit) {
    val c = DsTheme.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Spacer(Modifier.height(Space.l))
    Text(stringResource(R.string.onboarding_name_title), style = DsTheme.type.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Space.s))
    Text(stringResource(R.string.onboarding_name_body), style = DsTheme.type.body, color = c.textSecondary)
    Spacer(Modifier.height(Space.xxl))
    // Live preview of the app's header, so it's clear where the name appears.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(c.surface)
            .border(Sizes.hairline, c.outline, Radii.card)
            .padding(horizontal = Space.l, vertical = Space.m),
    ) {
        Text(stringResource(R.string.onboarding_name_preview).uppercase(), style = DsTheme.type.caps, color = c.textTertiary)
        Spacer(Modifier.height(Space.s))
        BrandNameplate(normalizeDisplayName(name))
    }
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
    Spacer(Modifier.height(Space.l))
    Text(stringResource(R.string.onboarding_setup_title), style = DsTheme.type.headline, color = c.textPrimary, modifier = Modifier.semantics { heading() })
    Spacer(Modifier.height(Space.s))
    Text(stringResource(R.string.onboarding_setup_body), style = DsTheme.type.body, color = c.textSecondary)
    Spacer(Modifier.height(Space.xxl))
    // One grouped surface with a divider instead of two separate cards.
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Radii.card)
            .background(c.surface)
            .border(Sizes.hairline, c.outline, Radii.card),
    ) {
        SetupItem(
            Icons.Rounded.Notifications, stringResource(R.string.onboarding_notifications_title), stringResource(R.string.onboarding_notifications_body),
            action = if (granted) stringResource(R.string.onboarding_allowed) else stringResource(R.string.action_allow),
            enabled = !granted,
        ) { if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        Box(Modifier.padding(start = Space.l + 40.dp + Space.m).fillMaxWidth().height(Sizes.hairline).background(c.outline))
        SetupItem(
            Icons.Rounded.AccountCircle, stringResource(R.string.onboarding_google_title), stringResource(R.string.onboarding_google_body),
            action = stringResource(R.string.connect_google), enabled = true, onClick = onGoogle,
        )
    }
    Spacer(Modifier.height(Space.l))
}

@Composable
private fun SetupItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    val c = DsTheme.colors
    Row(Modifier.fillMaxWidth().padding(Space.l), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(c.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = c.textPrimary, modifier = Modifier.size(Sizes.iconSmall + 2.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = DsTheme.type.title, color = c.textPrimary)
            Spacer(Modifier.height(Space.xxs))
            Text(body, style = DsTheme.type.bodySmall, color = c.textSecondary)
            Spacer(Modifier.height(Space.m))
            DsButton(action, onClick, style = DsButtonStyle.Secondary, enabled = enabled)
        }
    }
}

/** Progress for the three steps; the current one is a longer pill that glides with the step. */
@Composable
private fun StepDots(page: Int) {
    val c = DsTheme.colors
    val m = DsTheme.motion
    val desc = stringResource(R.string.onboarding_step, page + 1, STEPS)
    Row(Modifier.semantics { contentDescription = desc }, horizontalArrangement = Arrangement.spacedBy(Space.xs + Space.xxs)) {
        repeat(STEPS) { i ->
            val w by animateDpAsState(if (i == page) 22.dp else 6.dp, if (m.reduced) snap() else m.indicator(), label = "stepDot")
            val color = app.daysemble.ui.components.animateStateColor(if (i == page) c.inverse else c.outlineStrong.copy(alpha = 0.5f), m.indicator(), label = "stepDotColor")
            Box(Modifier.size(width = w, height = 6.dp).clip(Radii.pill).background(color))
        }
    }
}
