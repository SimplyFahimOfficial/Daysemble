# Daysemble: UI/UX and motion audit (October 2026)

Branch: `main` at `6903ab5`, working tree only (not committed or pushed).
Reference: the `apple-design` guidance (WWDC *Designing Fluid Interfaces* and *Principles of Great Design*), translated from the web to Jetpack Compose.
Builds on [AUDIT.md](AUDIT.md), which already fixed the structural motion problems: whole-app recomposition, hidden tabs doing work, the panel re-measuring every frame, double-image day swaps, and the month height jump. This pass doesn't redo that work.

**Evidence levels.**

- **Confirmed** means I read the code path, or a test or render shows it.
- **Render** means a Robolectric native-graphics frame (`VisualAuditRender`). These frames show layout, not real-time smoothness.
- No device or emulator was available, so I report no frame timings.

## 1. What already holds up (kept unchanged)

- **One motion system.**
  - `DsMotion` defines the motion roles.
  - Selection indicators use critically damped springs.
  - Animated values are read in draw or placement.
  - The tab cross-fade is interruptible.
  - Pages arrive over a still page, and predictive back matches the close animation.
  - Reduced-motion support covers both the system setting and the in-app setting.
- **Spatial consistency.** Editors and event detail rise from the bottom and sink back. Pages push in and pop back the same way, mirrored in RTL.
- **Today panel.** It follows the finger 1:1 through `AnchoredDraggable`, and on release it settles with the gesture's velocity, so there is no seam between drag and settle.
- **Forgiveness.** Completing, deleting and rescheduling have Undo. Discarding an edited form asks first.
- **Identity.** The app uses a white or near-black ground, one orange accent and an ink inverse pill. I didn't change the palette, type scale or layout grammar.

## 2. Findings

Priority: C = Critical, H = High, M = Medium, L = Low. Complexity: S = small, M = medium.

### Micro-interactions and feedback

| # | Screen / component | Existing | Problem and why it matters | Fix | Cx | P | Files | Status |
|---|---|---|---|---|---|---|---|---|
| M1 | Every long-pressable item (cards, rows) | `itemClick` calls `performHapticFeedback(LongPress)` inside a `combinedClickable` | `combinedClickable` already plays the LongPress haptic (`hapticFeedbackEnabled = true`, Compose foundation 1.12.1 `Clickable.kt`). Every long press buzzed twice. This breaks the rule that feedback, motion and sound fire together, once. | Drop the manual call | S | **H** | `components/ItemClick.kt` | Done |
| M2 | Tasks, List order drag | On drop, `dragOffset = 0f` at once. Lift is `shadowElevation = 8f` in raw px. No haptics. | The row **jumped** from where the finger left it to its slot (up to half a row). The shadow depended on screen density. Nothing confirmed pickup or each change of place. | Animate the offset to 0 with `DsMotion.settle()`, starting from the live value. Grabbing the row again mid-glide continues from where it is. Commit the order first, then animate. Lift is 6dp, plus a 1.5 % scale (none with reduced motion). Haptics: LongPress on pickup, SegmentTick on each swap, GestureEnd on drop. | M | **H** | `tasks/TasksScreen.kt` | Done |
| M3 | Tasks, swipe to complete or delete | The action colour shows as soon as the drag has a direction | You couldn't tell when letting go would commit, so the outcome was a surprise. Apple's rule: hint at the outcome during the gesture. | Below the threshold: a neutral fill and a grey icon at 80 %. Past it ("armed"): the action colour, the icon at 100 % on the indicator spring, and one GestureThresholdActivate tick. The reset after a completion doesn't tick. | S | **H** | `tasks/TasksScreen.kt` | Done |
| M4 | Task check circle (`DsCheck`, used on every card, row and subtask) | Silent | Completing a task is the app's main commit and gave no tactile confirmation. | ToggleOn or ToggleOff haptic on the same frame the fill starts | S | M | `components/Selection.kt` | Done |
| M5 | Agenda cards (Today, the calendar's day lists) | Ripple only | Buttons scale on press but the main tappable surface didn't, so press feedback was inconsistent. | `pressScale(large = true)` (0.985), shared with the card's own interaction source. Applied on touch-down, read in the draw phase, none with reduced motion. | S | M | `components/Cards.kt`, `components/Buttons.kt` | Done |
| M6 | `DsChip` (filters, list choice, "Add event") | Ripple on the unclipped 48dp outer row | The press drew a rectangle around a pill-shaped chip. | Keep the 48dp touch target. Draw the indication and press scale on the visible pill. | S | M | `components/Selection.kt` | Done |
| M7 | `DsSegmented` (calendar view, theme) | The indicator springs, but the label colour switched in one frame | The label changed before the indicator arrived. | Animate the label colour on the same `indicator()` spring | S | L | `components/Selection.kt` | Done |

### Visual design

| # | Screen / component | Existing | Problem | Fix | Cx | P | Files | Status |
|---|---|---|---|---|---|---|---|---|
| D1 | Floating bottom bar (Today, Calendar, Tasks) | Opaque pills over content, with no edge treatment | Render: sliced cards, dividers and section headers show around and under the pills, which looks busy and unfinished. Apple uses a scroll edge effect where content meets floating chrome. | A static background-coloured fade that starts 28dp above the bar and is opaque from about the pills' middle down. One `drawBehind` gradient: no layer, blur or animation. The lists' bottom padding already ends where the fade starts. | S | **H** | `DaysembleRoot.kt` | Done |
| D2 | Event detail, "Open in Maps" | A Secondary (tonal) button inside a tonal tile | Render: the button's container was the tile's own colour, so the label floated off-centre and didn't read as a button. | New `DsButtonStyle.Raised` (surface plus hairline) for buttons on tonal tiles | S | **H** | `components/Buttons.kt`, `event/EventDetailScreen.kt` | Done |
| D3 | Settings | "Date & time" and "Lists & calendars" shared the `CalendarMonth` icon | The two rows looked like the same destination. | `ListAlt` for Lists & calendars | S | M | `settings/SettingsScreens.kt` | Done |
| D4 | Event detail, missing-event state | Back arrow, while the loaded page has a close X | Same screen, different exit control (wayfinding). | Use the close X in both states | S | L | `event/EventDetailScreen.kt` | Done |
| D5 | Radii | The 20dp card and 28dp panel radii were private literals | Token drift (11 literal shapes in total, see AUDIT.md A5). | `Radii.tile` and `Radii.panel`, used by the cards and the Today panel. The other literals are deliberate small radii (calendar blocks, history grid) and the fixed radius on the wrapping time pill. | S | L | `theme/Dimens.kt`, `components/Cards.kt`, `today/TodayScreen.kt` | Done (partial) |
| D6 | Dialogs | Some use `DsButton`; most use M3 `TextButton` with hand-set colours | Inconsistent button styling in dialogs | `DsDialogButtons` in every dialog (16 of them). Confirm is the ink pill, Destructive is red, an Alternative is outlined, Cancel is plain text. Up to two actions sit side by side; three or more stack full width with Cancel last. | M | M | `components/DialogButtons.kt`, plus Pickers, ZonePicker, ItemActions, EventDetail, ListsCalendars, Schedule, Settings, Sleep, TaskRepeat | Done |
| D7 | Shadows | 10dp scrim-coloured shadows on the bar and FAB; 14dp on the Today panel (light theme). The design note says elevation is tonal. | Heavy for a "calm" app, and it contradicts the stated rule | `Elevation` tokens: bar and FAB 6dp, panel 8dp, dragged row 6dp, shadow colour at 50 % of the scrim | S | L | `theme/Dimens.kt`, `DaysembleRoot.kt`, `TodayScreen.kt`, `TasksScreen.kt` | Done (judged on renders only, not on a device) |

### Motion system

| # | Finding | Fix | P | Status |
|---|---|---|---|---|
| A1 | The Today panel spring used a literal stiffness of 650, outside `DsMotion` | `DsMotion.settle()`: damping 1.0, response ≈ 0.25 s (2π/√650). Also used by M2. | M | Done |
| A2 | The skeleton (900 ms) and spinner (1100 ms) loops used literal durations | `skeletonPulse` and `spinnerTurn` tokens. Both are already static with reduced motion. | L | Done |
| A3 | The completed-section chevron rotated even with reduced motion | `snap()` when reduced | L | Done |
| A4 | The role table didn't cover drops, swipe arming, the scroll edge or haptics | Documented in `theme/Motion.kt`, with the haptic rule: commits only | L | Done |
| A5 | No shared element from a list item into its detail (AUDIT.md B5) | Needs a design for the recorded main layer under the NavHost | M | Recommended |
| A6 | Reorder can't auto-scroll past the visible rows (AUDIT.md F9) | Within 64dp of an edge the list scrolls under the held row, up to 900 dp/s and faster nearer the edge, and keeps going while the finger is still. A row held past the last visible row now takes that row's slot. Before this, the row's own slot could scroll away, which dropped the gesture without saving. A cancelled drag now keeps the order on screen. | M | Done |

### UX, accessibility and states

| # | Finding | Recommendation | P |
|---|---|---|---|
| U1 | `ErrorState` and `OfflineState` exist but no screen uses them. Sync problems show only in the header badge. | **Done:** Accounts shows `OfflineState` ("You're offline": changes are kept and sync later) or `ErrorState` (the reason, plus Try again). Neither shows while a sync is running or when a reconnect is needed. | M |
| U2 | Loading: Today shows blank placeholders, and Calendar and Task detail show nothing until Room responds. Normally this takes a few ms. | Keep it this way; skeletons would flash. Add one only if device timing shows a gap of 100 ms or more. | L |
| U3 | Time-grid chips are 22–28dp tall, and the grid has no accessible create-at-slot action (AUDIT.md A5, F11) | 48dp hit padding; a custom accessibility action | M |
| U4 | All haptics go through `LocalHapticFeedback`, so the system haptic setting turns them off. Reduced motion removes the new press and lift scales. | — | — |

### New features and integrations (recommended, not built)

- **Launcher shortcuts: done** (`AppShortcuts.kt`). New task, New event and Search are dynamic shortcuts, published at launch off the main thread.
  - Why dynamic: static `shortcuts.xml` belongs to one launcher alias, so it would vanish when the logo changes, and it needs the literal package name, which differs in debug builds.
  - What they open: on the main screen, New task and New event open Quick Add on that kind. Over another page they open the full editor on top, so an editor with unsaved changes is never closed. Search doesn't stack a second copy.
  - Manifest: no new exported component or permission. `apk_audit.py` passes on the release APK.
- **Today widget** (Glance). It adds a dependency, so it's roadmap item 9 in AUDIT.md.

### Performance

Nothing new was measured. Without a device there is no JankStats, Macrobenchmark or Perfetto data. What the code shows for the changes in this pass:

- Press scale, lift, swipe icon scale and the drop glide are all read inside `graphicsLayer`. They change draw properties only, with no recomposition or re-measure per frame.
- The scroll edge is one gradient draw per frame of the bar. It allocates one `Brush` per draw, which is negligible next to the existing text-clipping draws.
- Still open from AUDIT.md A3: `TaskDao.observeAll` re-emits the whole table on every write, and `briefing()` rebuilds its AnnotatedString on every recomposition.

## 3. Verification

**Environment.** I installed the Android SDK in the sandbox (platform 37, build-tools 36/37) and ran JDK 21.

**Baseline before any change.**

- `testDebugUnitTest`: 424 tests, 389 passed, 35 skipped, 0 failed.

**After the changes.**

- `assembleDebug testDebugUnitTest lintDebug`: BUILD SUCCESSFUL.
  - 424 tests, 389 passed, 35 skipped, 0 failed.
  - Lint: 0 errors, 11 warnings, none in code changed here.
- detekt 1.23.8 with the CI's exact arguments (main and test sources, baseline): no new findings.

**Regression caught and fixed.** `DatePressFeedbackTest` failed on my first chip change, because I had replaced the app's ambient indication with an explicit `ripple()`. The chip now uses `LocalIndication.current` on the pill, and the test passes.

**Renders.**

- I ran the opt-in `VisualAuditRender` / `NewFeaturesRender` suite before and after. Its 4 `details*` cases fail identically on the untouched baseline (a stale harness assertion), so that failure is pre-existing.
- Before/after crops confirm D1 (Today light and Tasks dark), D2 and D3.

**Not verified.** Nothing here was checked on a device:

- How the haptics feel, and whether the GestureThresholdActivate, SegmentTick and GestureEnd constants are supported on a given device. Unsupported types are no-ops.
- The real-time feel of the drop glide and swipe arming.
- Predictive back.
- Large-font layouts on hardware.
- Frame timing.

No new tests were added (none were requested). The arming, drop-glide and haptic paths are therefore covered only by compilation and the existing suites.

## 4. Second pass (same branch)

**Implemented:** D6, D7, A6, U1 and the launcher shortcuts, as described in the tables above.

**Checks after the second pass:**

- `assembleDebug assembleRelease testDebugUnitTest lintDebug`: BUILD SUCCESSFUL.
  - 431 tests (7 came in with the merge of `main`), 396 passed, 35 skipped, 0 failed.
  - Lint: 0 errors, 10 warnings and 1 hint. That is one warning fewer than before: Try again reuses the unused `action_retry` string. The hint suggests reporting shortcut usage.
- detekt: no findings in code changed here. Main at `a1a1ff6` already has 11 findings from PR #22 (`CalendarSyncer`, `EditOccurrenceFlowTest`, `EventEditorScreen`), and they are still there.
- `scripts/ci/apk_audit.py` on the release APK: PASS.

**Temporary checks.** I ran a set of temporary Robolectric checks and then deleted them; none are committed:

- The three shortcuts are published.
- New event opens Quick Add on Event, and New task on Task.
- Search opens once.
- Accounts shows the offline state, and the error state with Try again.
- The shared confirm row works (Cancel and Delete).
- Auto-scroll: a row held at the bottom edge of a 30-task list moved from position 0 to 29.
- The auto-scroll check first failed. That failure is what exposed the dropped-gesture bug, which is fixed above.

**Rendered and checked:** the dark series dialog, the time picker, both Accounts states and Today. The first dialog style had a tonal fill for alternatives, which nearly vanished on the dark dialog, so alternatives are now outlined.

## 4. Bottom bar responsiveness pass (November 2026)

No device or emulator was available (no KVM in the sandbox), so there are still no frame timings.
Findings are from the code path plus a Robolectric reproduction where noted.

| # | Finding | Fix | Evidence | Status |
|---|---|---|---|---|
| N1 | Every tab switch back to a tab, and every page close back to the main screen, gave the screen a new `now`: `rememberMinuteTicker` re-read the clock when its lifecycle restarted. Today, Calendar and Tasks pass `now` down widely and forward it to their ViewModel, so the whole tab recomposed, its ViewModel rebuilt its state off the main thread, and the tab recomposed again with that state, all during the 160 ms tab fade or the page-close animation. | Keep the old value when the clock is still in the same minute. Everything that reads it is minute-precision; a new minute while hidden still refreshes at once. | `VisibilityLifecycleTest.minuteTicker_returningWithinTheSameMinute_keepsItsValue` fails on the old code (a second value was delivered) and passes now | Done |
| N2 | The bottom-bar pill used `indicator()` with `StiffnessMediumLow` (response ≈ 0.31 s, 83 % of the way when the 160 ms tab fade ends). The tab had arrived while the pill was still gliding. | `indicator()` uses `StiffnessMedium` (response ≈ 0.16 s, about 98 % at 160 ms). Shared with the segmented control and step dots, so all selection indicators keep one speed. | Spring maths; not measured on a device | Done |
| N3 | Tab items had a ripple only; every other control in the app also scales slightly on press. | `pressScale` on each tab, shared by the dark and light label copies so both shrink together. Off with reduced motion. | Code path | Done |
| N4 | The snackbar's lift above the bar was an animated `padding`, read in composition, so the content around it recomposed on every frame of each page open and close. | Same animation applied as an `offset { }`, read in placement. | Code path | Done |

## 5. Interruption, navigation and performance pass (November 2026)

Branch `main` at `5aac787`. No device or emulator was available (no KVM), so there are still no frame timings from hardware.
Evidence levels: **Confirmed** is a code path read, or a test that fails on the old code and passes now. **Render** is a Robolectric native-graphics frame series, which shows what each frame contains, not how smooth it is in real time.

### Confirmed problems and fixes

| # | Problem (root cause) | Fix | Evidence |
|---|---|---|---|
| P1 | Every tab switch and page open/close drew **one fully blank frame**. The tab you left faded out (90 ms) before the new one faded in, and a page did the same. | Tabs cross-fade: the new tab fades in over the old one, which stays drawn (`TabFade`, 150 ms). Pages push in over a still page and fade in; Back slides out and fades over the revealed page. | Render: `FrameSeriesProbe` (below). `TransitionFramesTest` now allows no dipping frame |
| P2 | Rapid taps left the bottom-bar icon scaled. The icon bounce was a separate coroutine per tab; switching again cancelled it half way and nothing restored it. | Bounce removed. | Code path |
| P3 | Same bug in the check circle (pop cancelled by the next toggle) and the selected-date disc (pop cancelled by the next tap). | The check circle's pop is derived from the fill progress (`checkScale`: 1 where the fill rests). The date disc's pop is removed. | `MotionStateTest.checkScale_…` |
| P4 | Completing a task in Tasks waited 400 ms (`delay(medium + short)`) before saving, with a `Job` map to cancel it on a second tap. | The tap commits at once; the row's strike and check animate toward the saved state. Undo is unchanged. | `TaskCompletionTimingTest`: 25 frames on the old code, 12 allowed, passes now |
| P5 | The Calendar's Month/Week morph had several writers. Every drag delta launched its own coroutine (`snapTo`) that read a value its predecessor had not written yet, so deltas were lost. On release the drag and the mode change each started an animation of the same value, neither with the finger's velocity. `userScrollEnabled = morph.value < 0.5f` recomposed the whole mode on every frame. | `MorphState` is the `DraggableState` and the settle in one object behind one `MutatorMutex`: grabbing the grid stops a settle, a settle starts from the live value with the release velocity, a repeated request for the same target is ignored. The pager flag is a `derivedStateOf`. | `MotionStateTest.morph_…` |
| P6 | The Today day swipe slid the old day off, *then* selected the new date (about 200 ms later), then snapped the offset to 0. A new drag during that animation fought it. | `DaySwipeState` has one mutex like the morph. The date is selected on release; the old content waits where the finger left it until the new day arrives (300 ms limit, then it springs back), then the new day glides in from the side the swipe was heading to. | `MotionStateTest.daySwipe_…` |
| P7 | Search ran the recurrence expansion of matching events (±365 days) and the filtering and sorting of all tasks on the main thread (`viewModelScope` collects on Main). | `flowOn(compute)` like the other view models; `flatMapLatest` / `mapLatest` still cancel a superseded query. | Code path; the existing search UI tests pass |
| P8 | Tab preloading: `awaitMainThreadIdle(3 s)` returned on timeout and the tab was composed anyway, defeating the idle check. | It waits as long as needed; a tab that is never preloaded composes when opened. Whether preloading helps is still unmeasured: compare `TabSwitchBenchmark` with and without it on a device. | Code path |
| P9 | Quick Add focused its field after a fixed `delay(250)`. | Focus is requested once the open progress reaches 0.8 (immediately with motion off). The pull-down handle's return animation is cancelled when the finger takes the sheet again, and starts with the release velocity. | Code path |
| P10 | The search reveal and the header field read their animated value in composition (`if (open > 0.001f)`, `requiredWidth(lerp(...))`), recomposing the header and the results container on every frame. | `derivedStateOf` for the visibility test; the width is applied in a `layout` block. | Code path |
| P11 | Decorative motion competing with the main movement: staggered row, dot and form entrances (Tasks, month day list, Quick Add, empty-state buttons, day dots), and the looping "breathing" empty-state icon. | Removed. A list for another day or filter still swaps in one piece (`ContentSwap`). | Code path |
| P12 | The date disc waited for its own fade before sliding to a newer cell; a second Quick Add while a row's highlight was fading left that row tinted. | Fade and slide run together; the highlight is reset whenever the newest id changes. | Code path |

### Render comparison (Robolectric, 30 frames of 16 ms after each action; share of non-background pixels, rest = the emptier of the two screens)

| Transition | Old: emptiest frame | Old: frames under 60 % of rest | New: emptiest frame | New: frames under 60 % |
|---|---|---|---|---|
| Today → Tasks | 0 % (blank) | 2 | 100 % | 0 |
| Tasks → Calendar | 0 % | 5 | 100 % | 0 |
| Calendar → Today | 0 % | 4 | 100 % | 0 |
| Open Settings | 0 % | 5 | 100 % | 0 |
| Open Appearance | 0 % | 3 | 100 % | 0 |
| Back to Settings | 0 % | 3 | 100 % | 0 |
| Back to Today | 0 % | 5 | 100 % | 0 |

100 % means no frame had less content than the emptier of the two screens. This is the layout and draw order, not a timing measurement.

### Verification

- `assembleDebug assembleRelease testDebugUnitTest lintDebug`: BUILD SUCCESSFUL. 456 tests, 54 skipped (the opt-in render suites), 0 failures. Lint: no errors.
- detekt 1.23.8 with the CI arguments: no new findings. `scripts/ci/apk_audit.py` on the release APK: PASS.
- New tests: `MotionStateTest` (10), `TaskCompletionTimingTest` (1). `TransitionFramesTest` is stricter (no dipping frame at all).
- `:macrobenchmark:compileBenchmarkKotlin` compiles the new `InteractionBenchmark` journeys (not run).

### Not verified

- Anything on hardware: frame timing and jank, 60/90/120 Hz behaviour, thermal behaviour, the feel of the cross-fade length (150 ms) and of the page push, the day swipe's glide, and predictive back (both completed and cancelled). Predictive back was only reasoned from the code: the revealed page is complete and still, and the library seeks the closing page back when the gesture is cancelled.
- Whether tab preloading pays for itself.
- JankStats is not added (it would be a shipped dependency); the macrobenchmarks report frame timing instead.

## 6. Calendar view switching (November 2026)

Scope: Month, Week, Day and Agenda in `ui/calendar`. No device, so no frame timings; the screen recording that prompted this pass was not available to me, so the causes below come from the code and from frame-by-frame renders of the real app root, not from the recording.

| # | Cause | Fix | Evidence |
|---|---|---|---|
| C1 | **The load window depended on the mode** (Month 3 grids, Week 3 weeks, Day 3 days, Agenda 60 days), so every mode switch restarted `flatMapLatest`: the database queries and the recurrence expansion ran again, and `state.mode` only reached the screen when they finished. The control, the Month/Week morph and the content all waited for it (and Day and Week reloaded on every step). A drag release moved the morph at once but the state later, so the two could disagree. | One window per selected month, from the previous month's grid to the end of the agenda range. It covers every mode, so switching mode or stepping within a month changes only the mode in the next emission. | `CalendarViewModelTest`: all 12 mode pairs keep the same loaded data (fail on the old view model), and the window covers every pager for months of 4, 5 and 6 weeks and at both ends |
| C2 | Coming back to Month or Week from Day or Agenda, the morph still had the value it was left at, then animated to the new mode while the content was already fading in: a week row that unfolds into a month (or the reverse) under the fade. The renders also flickered (`0.24 0.05 0.24 0.05`). | The morph jumps to the mode's layout when the Month/Week content is not on screen (`MorphState.offscreen`, `snapTo`), and animates as before when it is. | `CalendarModeSwitchTest.comingBackFromDayOrAgenda…` fails without it: cell counts `[7, 42]` |
| C3 | Between views the old content faded out in 150 ms while the new one, which has no background, faded in over 250 ms: two layouts translucent on top of each other with the page showing through (ghosting, and a lighter dip). | The new view still fades in over 250 ms; the old one stays drawn until it is covered (`fadeOut` to 99.9 %, since AnimatedContent drops an exiting child when its own exit animation ends and 1 to 1 ends at once) and the new one has an opaque background. | Render: mean luminance of the content band, Month to Day: the old transition lightened to 0.981 (rest 0.974 / 0.960) and then darkened; now it moves from 0.974 to 0.960 without overshoot. Day to Month: 0.982 before, none now |
| C4 | The time grid started at midnight and scrolled to 08:00 or "now" after its first frame (a visible jump each time Week or Day appeared or a page was paged in), and each page had its own scroll. | One `ScrollState` created at that offset and shared by Week, Day and their pages (saved with the screen), so the first frame is right and the hour you were looking at stays. | Code path |
| C5 | The Week time grid and the day list were composed in the first animated frame of the morph, inside the animation. | They are composed when the mode (or a drag) heads toward them, still invisible, so the animation starts after the composition. The day list no longer fades in again on its own when it is composed by the morph. | Code path |
| C6 | Month rows folded away by the morph stayed in the accessibility tree. | Hidden from semantics once fully folded. | `CalendarModeSwitchTest` counts 7 cells in Week |

Not changed: the Month/Week morph, the title slide, the segmented control, the fade-in length and curve, the day list and time grid cross-fade at the middle of the morph (a few frames where both are near zero, by design), and the page transitions.

Verification: `assembleDebug assembleRelease testDebugUnitTest lintDebug` and `:macrobenchmark:compileBenchmarkKotlin` pass (464 tests, 54 skipped, 0 failures); detekt shows no new findings; the APK audit passes. New: `CalendarModeSwitchTest` (all 12 directions with a no-blank-frame check, Month to Week only removing and Week to Month only adding cells, entry in the final layout, rapid tap sequences), three `CalendarViewModelTest` cases, `MorphState.snapTo` in `MotionStateTest`, and the `calendarModeSwitching` macrobenchmark (compiled, not run).

Not verified: frame timing and recomposition counts on a device (use `calendarModeSwitching` and `monthWeekDrag` with a Perfetto trace), and how the shared time-grid scroll feels when paging.

## 7. Performance pass: data pipelines, Tasks drag, resources (November 2026, uncommitted)

No device, so no frame timings. What was measured is stated per row; the rest is read from the code.

| # | Cause | Change | Evidence |
|---|---|---|---|
| D1 | **Today restarted every query on every day selection** (`flatMapLatest` on the date): events, the whole task table, task series, settings, the sleep log and the week-strip query, and the day (and the day swipe) arrived only after all of them. | Events load once for the weeks of the selected month and everything else is observed once (`shareIn`); a day inside the month is built from memory. The week strip's dots come from the same data. Another month, zone or first weekday loads a new window and the previous day stays until it arrives. | `TodayWindowTest` counts Room SELECT statements: four day selections added 46 on the old view model (31 to 77, about 11 each) and 0 now. The test fails on the old view model |
| D2 | The Tasks auto-scroll loop asked for a frame on every vsync for the whole drag (`while (true) withFrameNanos`), so Compose never went idle and it walked the visible items 60-120 times a second, even far from the edges. | The loop runs only while the held row is in an edge zone (found through a `snapshotFlow` on the state it reads). | The reorder test hung ("Compose did not get idle") on the old screen and passes now |
| D3 | A data update while a row was held rebuilt the manual order (`remember(state.sections)`), putting the row back. | The order follows the data only when no row is held or settling; the update is applied on release. | `TaskReorderTest` |
| D4 | Reorder rows: `manual.indexOf(task)` per row, `manual.any { }` per visible row on every swap, no content types. | `itemsIndexed`, a set of ids, content types for rows, headers and states. | Code path |
| D5 | `animateStateColor` created an `Animatable` and launched a coroutine for every state colour at composition (about 130 for the month grid). | Nothing is created until the target first changes; the theme still switches colours at once. | `StateColorTest` (rest, change, fast flips, theme change). Counts from the code, not timed |
| D6 | `observeTask` also observed every list and every series (and `lists` was unused). | It follows its own row and its own series. | Existing detail and editor tests |
| D7 | Two copies of the QR encoder and PNG writer (`UpiQr`, `UpiQrCode`, `SupportIntents.writeQrPng`). | One (`UpiQr`); `UpiQrCode` deleted. | `UpiQrTest` |
| D8 | New subtasks in the editor expanded while the column that holds them animated its size to follow. | The column keeps the one size animation; a new row fades in. | Code path |
| D9 | Benchmarks skipped a missing element (`?.click()`, ignored `wait`) and timed an idle screen. | `require` / `click` fail the run; added start-up, theme, Support, task drag and Calendar-mode journeys. | `:macrobenchmark:compileBenchmarkKotlin` (not run) |
| D10 | Dead code: `ItemsAppearInstantly`, `ErrorState`, `OfflineState`, `DsCheckRow`, `SearchViewModel.rawQuery`, `DsMotion.spinnerTurn`, `TasksViewModel.purgeTombstones`, `TaskRules.isHighPriority` and `relativeDayLabel`, `ReminderScheduler.cancelOwnerAlarms` and `scheduledFor`, `TaskRepository.localTaskCount`, `TaskDetailViewModel.focusedId` and `isSeries`, `Countdown.totalIsZero`. | Deleted (nothing referenced them, tests included). | Build and tests |

Looked at and left alone: the theme cross-fade (`GraphicsLayer.record` plus one `toImageBitmap()` per theme change: the alternatives, per-colour animation and a window-level snapshot, were what it replaced or cost more; the capture is released after the fade; two quick theme taps still pop once), the page layers and `pagesLeaving` (balanced by `DisposableEffect`), date formatting in the grids (measured on the JVM: `fullDate` about 4 us and `hourShort` about 1 us per call, warm), the 512 px logos and 1152 px splash (decoded 1 and 5.3 MB, at the sizes they are shown), the QR bitmap (41 modules, encoded once per visit).

Not verified: frame timing and jank (Perfetto: `InteractionBenchmark`), the per-frame remeasure of three month pages by `morphInset` while Month/Week morphs, the cost of the always-composed hidden overview on Today, whole-table task observation by Tasks, Today and Search, and a device-recorded Baseline Profile (the file is still the starter).

## 8. Follow-up on PR #49, tabs, navigation, theme (November 2026, uncommitted)

Builds on `perf-data-pipelines-tasks-drag` (PR #49). No device, so no frame timings; each row says what was measured.

| # | Cause | Change | Evidence |
|---|---|---|---|
| E1 | A third tab tapped while two were cross-fading jumped to the tab that was arriving, and going back to a tab underneath swapped roles only for two layers. | `TabFade` keeps a stack of drawn tabs, each with its own alpha. A new tab is pushed at 0 and the others stay exactly as they are; going back to a tab in the stack fades the ones above it out. A tab that is only fading out takes no touches. | `MotionStateTest` (picture identical before and after the third tap and the reversal; ends on the last tab), `TransitionFramesTest.rapidTabTaps…` (4 tap sequences, no frame under the floor) |
| E2 | Back and Cancel on a page that had just opened did nothing until the opening transition ended: the page *under* it (covered, not leaving) also reaches its exit state and was counted by the process-wide `pagesLeaving`. | `PageGuard` belongs to the NavHost; a page counts only when Back has popped it. | `PageBackTest.backOnAPageThatJustOpened_closesIt` fails on the old root (the page stays) and passes now |
| E3 | Two quick theme changes: the second dropped the fading picture and popped to the new theme (and the app was recorded into a layer on every draw). | The capture includes the fade still running, so the second change continues from what is on screen; the app is drawn into the layer only for the frames of a capture. | `TransitionFramesTest.themeToggledBackMidFade…`: the picture stepped 0.52 to 0.16 in one frame on the old code, and by at most the first fade's own step now |
| E4 | PR #49: the dragged order was replaced by the data again right after the drop, when the data still had the old order (the save had not arrived), and for a frame after every data change (a `LaunchedEffect` did the sync). | The order is built in the same composition as the data and is not replaced while a row is held, nor by data that has the same tasks in the old order until the saved one arrives. | `TaskReorderTest.staleDataAfterTheDrop…` fails on PR #49, passes now; a 1,500-task drag saves every id once |
| E5 | PR #49: `taskDragging` used `UiDevice.drag`, which does not long-press, so the handle never reacted and an idle list was timed. | `UiObject2.drag` (long press, then drag), and the run fails unless the order changed. `canceledAndCompletedBackGesture` fails unless Settings stays after the cancelled gesture and goes after the completed one. | Compiles; not run (no device) |

Measured on the JVM (warm, Robolectric): loading the Today window for a calendar with 200 daily recurring events took 35 ms (9,800 occurrences) against 17 ms for two days (400); it happens once per month change, off the main thread, and a day inside the month no longer queries at all (`TodayWindowTest`). A scan for unused declarations found nothing further.

Rejected, with the reason:
- `morphInset` as a placement instead of a measure: the cells would be measured at the Month width and placed at interpolated x, so their touch areas would no longer match the visible columns, and nothing here shows the remeasure to be a cost.
- `CompositingStrategy.ModulateAlpha` on the folding rows: it avoids an offscreen layer but changes how a disc and its number blend during the fold; unmeasured.
- Replacing `GraphicsLayer.toImageBitmap()`: on API 28+ Compose rasterizes through `Bitmap.createBitmap(Picture)` on the calling thread, which `withTimeoutOrNull` cannot interrupt; the alternatives (a frozen display list, per-colour animation) are worse or were the original problem. Needs a Perfetto trace on an API 28-31 device before changing.
- Targeted task queries in place of the whole-table observation, a Today overview that composes only when expanded, and removing tab preloading: none can be judged without a device.
- Predictive back in a unit test: the activity's back callback is not registered with the window dispatcher under Robolectric, so the gestures are covered by the macrobenchmark only.

