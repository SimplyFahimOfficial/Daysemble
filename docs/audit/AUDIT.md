# Daysemble: repository audit, motion rework and roadmap

Branch: `feature/motion-rework-audit`, from `main` at `0d6b2d5` (PR #14 merged). Not committed or pushed.
"Confirmed" means the code path was read end to end or a test shows it. "Suspected" means the risk is plausible but wasn't traced to the end. Line numbers refer to `main` at `0d6b2d5`.

---

## A. Repository audit

### A1. Architecture (confirmed)

- **Layers:** a single module with a manual DI container (`AppContainer`, everything lazy).
- **Data path:** Room → repositories → screen ViewModels (`stateIn`, `WhileSubscribed(5s)`, `flowOn(Default)`) → Compose.
- **Navigation:** the main screen (header, tabs, bottom bar) is always composed. The NavHost hosts only the pages opened over it; its `MainRoute` is a placeholder that draws the main screen's recorded picture. This is a sound design and I kept it.
- **No god objects that block work.** `TaskRepository` is large (about 670 lines) but cohesive. Read-only series views already moved to `TaskSeriesReader`.
- **Duplicate sources of truth:**
  - `TimeContext` starts at the locale's first weekday until `DaysembleRoot` sets it, while the calendar header reads settings. Suspected one-frame mismatch, P3.
  - Each screen creates its own `rememberMinuteTicker`. Harmless now that hidden screens pause (B2).

### A2. Functional defects

| # | Finding | Evidence | Status |
|---|---|---|---|
| F1 | **Tasks search field drops characters.** It showed `state.query`, which is rebuilt with the whole list on `Dispatchers.Default`. | `TasksScreen.kt:142`, `TasksViewModel.kt:67` | **Fixed**; test fails on the old code |
| F2 | **A change in a read-only Google calendar was retried and failed on every sync, forever.** It used `markFailed`, which never parks. | `CalendarSyncer.kt:108` | **Fixed**; test |
| F3 | **Unexpected exceptions in sync escaped `runService`.** No error was recorded and the worker crashed. | `SyncManager.kt:104-115` | **Fixed** (records `internal`) |
| F4 | **Event detail kept the old repeat summary after the series was edited.** It used a one-shot `produceState`. | `EventDetailScreen.kt:89` | **Fixed** |
| F5 | **Item menu vanished without an exit animation** after Complete, Skip or Delete. The state removed the sheet at once. | `ItemActions.kt` | **Fixed** |
| F6 | **Month grid height jumped between 5- and 6-week months.** Everything below it moved when a swipe settled. | `MonthGrid` | **Fixed** (always 6 rows); test fails on the old code |
| F7 | Editing a recurring **event** always edits the whole series. Delete offers a scope choice; save doesn't. | `EventEditorViewModel.kt:88` | Roadmap P1 |
| F8 | All-day events beyond 3 per day in Week/Day view can't be opened. "+N" is plain text and a hard-coded string. | `TimeGrid.kt:87-88` | Roadmap P2 |
| F9 | Drag-to-reorder can't move past the visible rows (no auto-scroll). The order can also reset mid-drag when data changes. | `TasksScreen.kt:115, 228` | Roadmap P2 |
| F10 | A reminder is marked delivered before it is posted, and a failed post is ignored. With notifications off, it is lost for good. | `Receivers.kt:55, 91` | Roadmap P2 |
| F11 | The time grid has no accessible way to create an event at a slot. | `TimeGrid.kt:146` | Roadmap P2 |
| F12 | An event at 23:50 is drawn past the end of the grid (20-minute minimum length). | `DayLayout.kt:22` | Roadmap P3 |
| F13 | Google Tasks creates may be duplicated. Tasks has no client ids, and OkHttp `retryOnConnectionFailure` can resend a POST. | `GoogleHttp.kt:98`, `TasksSyncer.kt:99` | Suspected, roadmap P2 |
| F14 | Completing a recurring task in a **Google** list creates no next occurrence. It is by design (Google stores no rule), but the UI doesn't say so. | `TaskRepository.kt:253` | Confirm the intent |

**Verified correct (not re-checked further):**

- Task series: completion, duplicate prevention, undo, skips, this/future/all scopes (tests).
- Sleep records and free-time interval arithmetic (tests from PR #14).
- Room migrations v1→v3 (tests).
- Reboot, time and time-zone rescheduling.
- `goAsync` receivers.
- PendingIntents are immutable.
- 401 refresh, 410 full resync, 409 adopt, 412 conflict log.

### A3. Performance

These are code-based findings. No device profiling was possible (see B6).

- **Confirmed, fixed:** whole-app recomposition on every settings change; hidden tabs and covered screens doing work; the Today panel re-measured every frame. Details in B1.
- **Suspected, P2:** `TaskDao.observeAll` returns every non-deleted task, completed ones included, forever. Every task write re-emits the whole table to Today, Calendar, Tasks and Search. It needs scoped queries (open tasks, a date range).
- **P3:**
  - `activeAfter` has no index on `rangeEndUtc`.
  - `rescheduleAll` runs one `overridesFor` query per recurring event.
  - `briefing()` rebuilds its AnnotatedString on every recomposition.
  - The occurrence grid grows by 8 weeks per "Show earlier" without a cap, in a non-lazy Column.
  - No `contentType` in lazy lists.

### A4. Security and privacy (confirmed clean unless listed)

**Clean:**

- MainActivity and all receivers are `exported=false`. Only the launcher aliases are exported.
- PendingIntents use `FLAG_IMMUTABLE`.
- No cleartext traffic; only system CAs are trusted.
- Backup includes only `settings.preferences_pb`. The database and account store are excluded.
- OAuth tokens are held in memory only. AccountStore keeps the email and flags.
- The one `Log` call logs only an exception class name.
- CI: `contents: read`, `persist-credentials: false`, actions pinned to SHAs, signing secrets only in the `release` environment on `main`.

**Open items:**

- Notification action PendingIntents differ only by request code (`Notifications.kt:50`). A hash collision could act on the wrong task. Add a data URI. P3.
- R8 release builds are assembled in CI but never run. A stripped serializer would only show up at runtime. Add a minified smoke test. P3.
- `WorkManager` is initialised on the main thread in `Application.onCreate`. P3.

### A5. UI/UX

- **Consistency:**
  - 11 literal `RoundedCornerShape(n.dp)` instead of `Radii`.
  - 6 ad-hoc shadow elevations with no elevation token. The Tasks drag shadow is `8f` px, not dp.
  - About 125 literal `.dp` values outside `Space`.
  - Hard-coded separators (`" · "`, `" – "`, `"+N"`, `" ’yy"`) don't localise.
- **Accessibility:**
  - Hour labels offset by −7dp, and the fixed 16dp weekday header in the history grid, clip at large font sizes.
  - History cells are about 44dp. Unscheduled cells are focusable and announced as "not scheduled".
  - Time-grid chips are 22–28dp high.
- **Clean:** every settings field has a reader; there are no empty handlers or TODOs in `ui/`; there are no hard-coded `Color(0x…)` outside the theme; every clickable has a role.

### A6. Missing tests

Untested behaviours:

- The notification Complete/Snooze actions.
- A 412 conflict end to end.
- Disconnect (cascade delete, outbox cleared).
- Reminder delivery with notifications denied (F10).
- SyncWorker retry limits.
- The GoogleHttp 401/403 classification.
- TIME_SET and TIMEZONE_CHANGED rescheduling (only BOOT is tested).
- Tasks duplicate-create adoption.
- A minified release run.

---

## B. Motion and navigation rework (implemented)

### B1. Root causes found in the code

1. **Every settings change recomposed the entire app.**
   - `LocalAppSettings` was a `staticCompositionLocalOf`, provided at the root with the whole `AppSettings`. Any change invalidates the whole subtree, and settings change on every switch in Settings.
   - Each toggle therefore recomposed the Settings page, the NavHost, and the three tabs kept composed under it, on the main thread, while the switch was animating.
   - Fix: `compositionLocalOf`. Only its 5 readers recompose now.
2. **Hidden content never stopped working.**
   - The three tabs (always composed for instant switching) and the main screen under every page used `collectAsStateWithLifecycle` and minute tickers bound to the *activity* lifecycle.
   - Every database write and every minute therefore rebuilt and recomposed all of them, including during another screen's transition.
   - Fix: a new `VisibilityLifecycle` gives that content its own lifecycle, capped at CREATED while hidden. Collection and tickers pause; the last frame stays on screen (no blank); coming back resumes and catches up. A tab that was composed in the background but never shown keeps loading, so its first appearance has data.
3. **The Today panel was re-measured on every drag and settle frame.**
   - The agenda was measured to `h − offset` inside the measure block, so its week pager and LazyColumn re-measured 60 times a second, and the list composed new rows mid-gesture.
   - Fix: measure once at the collapsed height and read the offset in placement only, so a moving panel only re-places.
   - Test: the panel height stays the same throughout a drag. It fails on the old code.
4. **Changing the day showed two days at once.**
   - Every agenda row had `animateItem` fade-in and fade-out. A date change removed every row (fading out) and added new ones (fading in) at the same time, so the two days' cards overlapped. Frames rendered off-device at 64–160 ms show it: `swap-compare.png` (same folder), top row old, bottom row new.
   - Fix: `ContentSwap` replaces the list in one piece. New rows appear at once, with a 16dp drawn shift for direction, and the list starts at the top. Row motion is kept for in-place changes such as completing, deleting, adding or reordering.
   - The month view's day list uses the same rule: keys include the date, no fade-out, appearance suppressed for the swap.
5. **The month grid height jumped** between 5- and 6-week months. Fixed with an always-6-row grid; test fails on the old code.
6. **Completed tasks expanded as one growing item** holding all rows, which re-measured the list every frame. They are now individual lazy rows that fade in place.
7. **The item menu sheet vanished** instead of sliding away after an in-place action. Now the action runs at once, the sheet hides, and only then leaves the composition. Actions that open a page still close it at once, since the page arrives over it.

Checked and left unchanged because they are sound:

- **TabHost:** cross-fade over the still old tab, draw-phase alpha, interruptible Animatables; the rapid-switch test passes.
- **Bottom-bar indicator:** spring read in the draw phase.
- **NavHost transitions:** one container, page over a still page, predictive back that matches.
- **Duplicate-navigation guards:** `openFromMain`, `openFrom`, `isShowing`.
- **Back handling:** Page touch-blocking while leaving; system and predictive back order.
- **Theme cross-fade:** a captured layer.
- **Reduced-motion support.**

### B2. Motion system

- `DsMotion` (theme/Motion.kt) remains the single place for durations and curves.
- Added `swapShift` and documented five rules in the role table:
  - Animated values are read in draw or placement.
  - A moving container is never re-measured per frame.
  - Only on-screen content does work (VisibilityLifecycle).
  - One container animates a given screen.
  - A replaced list is swapped in one piece; per-row motion is only for rows changing within the same list.
- `itemMotion(fadeOut)` and `ContentSwap` / `ItemsAppearInstantly` live in components/Motion.kt.

### B3. Files changed

**Main:**

- `ui/components/VisibilityLifecycle.kt` (new)
- `ui/components/Motion.kt`
- `ui/theme/Motion.kt`
- `ui/DaysembleRoot.kt`
- `ui/today/TodayScreen.kt`
- `ui/calendar/CalendarScreen.kt`
- `ui/tasks/TasksScreen.kt`
- `ui/tasks/TasksViewModel.kt`
- `ui/actions/ItemActions.kt`
- `ui/event/EventDetailScreen.kt`
- `data/google/calendar/CalendarSyncer.kt`
- `data/google/sync/SyncManager.kt`

**Tests:**

- `ui/VisibilityLifecycleTest.kt` (new)
- `ui/MotionContinuityTest.kt` (new)
- `data/google/CalendarSyncTest.kt` (+1)
- `ui/FirstUseAndNavigationTest.kt`: the month-cell helper now picks the on-screen page, because six rows show boundary dates on both pages.

### B4. Verification actually run

- `./gradlew assembleDebug testDebugUnitTest lintDebug --stacktrace` passes: 382 tests, 348 passed, 34 skipped, 0 failed. Lint shows the same 14 warnings as before, none new.
- `./gradlew assembleRelease` passes.
- To show the new tests detect the defects, I ran them against the old code: the search, panel-height and month-height tests fail on it and pass with the fixes.
- The day-swap semantics test passes on both. Fading rows aren't in the semantics tree, so it guards the swap but can't detect the old overlap. The rendered frames are the evidence for that one.
- The existing blank-frame tests (tabs, Settings open/close/back, theme), the gesture tests, rapid tab switches and the Settings double-back test all still pass.

### B5. Not done in this pass

- No shared-element transition from a list item into its detail screen. It is possible with Compose 1.9 `SharedTransitionLayout`, but the main screen is drawn as a recorded layer under the NavHost, so the shared bounds would need their own design. Roadmap P2.
- Tab composition in the background still composes a whole tab in one idle frame. Pausable composition is experimental; this is left as it is.

### B6. Not tested

No emulator or device is available in this sandbox. **Nothing in the manual matrix was run**: rapid tabs on hardware, predictive-back feel, background and resume mid-transition, large text, real frame timings. No Macrobenchmark or Perfetto data exists for this project, so I report no frame timings. The Robolectric tests prove behaviour and layout, not smoothness.

---

## C. Roadmap (ranked)

| Rank | Item | User value | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| 1 | Scope choice when saving recurring events (F7), reusing `SeriesScopeDialog` | Avoids unintended series-wide edits | M | M (override rows, sync) | EventRepository |
| 2 | Scoped task queries instead of `observeAll` (A3) | Faster screens with large histories | M | M | DAO + repos; no migration |
| 3 | Device verification: Macrobenchmark + JankStats on tab switch, Settings and the Today drag | Real numbers for the motion work | M | L | new `:benchmark` module |
| 4 | Reminder delivery when notifications are blocked (F10) | No silently lost reminders | S | L | Receivers |
| 5 | Reachable "+N" all-day events and an a11y slot-create action (F8, F11) | Access to all events | S | L | TimeGrid |
| 6 | Reorder auto-scroll, and keep the order stable while dragging (F9) | Usable manual ordering | M | L | TasksScreen |
| 7 | Shared element list→detail (B5) | Visual continuity | M | M | NavHost/main-layer design |
| 8 | Local backup/export (JSON) and restore | The database isn't backed up today (by design, for privacy) | M | M | Room; Storage Access Framework; no server |
| 9 | Home-screen widget: today's agenda and free time | Glanceable day | M | L | Glance dependency |
| 10 | Weekly view of sleep and free time from existing records | Useful insight from data already stored | S–M | L | No schema change |
| 11 | Shape/elevation tokens and localised separators (A5) | Consistency, RTL | S | L | — |
| 12 | Minified release smoke test in CI (A4) | Catches R8 issues | S | L | CI |

Not recommended now: AI features, cloud or social features, extra external APIs. They add privacy and maintenance cost without solving a problem found in the audit.
