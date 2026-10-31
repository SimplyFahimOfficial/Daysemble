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
