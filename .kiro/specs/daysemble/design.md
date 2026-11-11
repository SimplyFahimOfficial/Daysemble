# Daysemble — Technical Design

## 1. Key decisions

| Decision | Choice | Why |
|---|---|---|
| UI | Jetpack Compose + Material 3 (heavily themed) | Native, modern, testable. M3 provides accessibility and theming plumbing, and our tokens supply the look. |
| Build | AGP 9.4 (built-in Kotlin), Gradle 9.x, Kotlin 2.x via AGP, version catalog | Current stable toolchain (verified against Google Maven, Oct 2026). |
| DI | **Manual `AppContainer`** | One module, about 15 singletons. Hilt adds kapt/KSP + plugin-compat risk for little gain. Constructor injection keeps everything testable. |
| Persistence | Room (KSP) + Preferences DataStore | Room for domain data and a read-only copy of the phone's calendars. DataStore for settings. |
| Async | Coroutines + Flow, `StateFlow` UI state per screen | Standard, lifecycle-aware via `collectAsStateWithLifecycle`. |
| Navigation | Navigation Compose with type-safe `@Serializable` routes | Back stack + process-death restoration for free. |
| Phone calendars | Android Calendar Provider (`READ_CALENDAR`), mirrored into Room | Shows what calendar apps and synced accounts already hold, with no account, token or network. Read-only; Google Tasks are not in the provider. |
| Background | AlarmManager (reminders), WorkManager (daily reminder refresh) | AlarmManager is the only option for user-visible time-precise reminders. |
| Time | `java.time` (core library desugaring not needed at minSdk 26) | Correct zones/DST. |

## 2. Package structure (single `:app` module)

```
app.daysemble
├── DaysembleApp.kt            Application, builds AppContainer
├── AppContainer.kt            manual DI graph
├── MainActivity.kt            edge-to-edge, theme, nav host
├── core/
│   ├── time/                  Clock abstraction, RelativeTime, DayRange, formatting
│   ├── recurrence/            RRule model, parser, expander
│   └── result/                AppResult, error types
├── data/
│   ├── db/                    Room DB, entities, DAOs, converters, migrations
│   ├── prefs/                 SettingsRepository (DataStore)
│   ├── repo/                  EventRepository, TaskRepository, SearchRepository
│   └── device/                DeviceCalendarSync (Calendar Provider -> read-only calendars and events in Room)
├── domain/model/              Event, EventOccurrence, Task, Subtask, Priority, Reminder, CalendarSource…
├── reminders/                 ReminderScheduler, AlarmReceiver, BootReceiver, NotificationFactory
└── ui/
    ├── theme/                 Color, Type, Shape, Spacing, Motion, DaysembleTheme
    ├── brand/                 LogoVariant, BrandLogo composable
    ├── components/            DsButton, DsCard, DsCheck, DsChip, SectionHeader, StateViews, WeekStrip…
    ├── navigation/            Routes, DaysembleNavHost, bottom bar
    ├── today/  calendar/  tasks/  event/  task/  search/  settings/  onboarding/
```

## 3. Data model (Room)

All primary keys are local UUID strings. Remote ids are separate columns, so the mapping stays stable
even before the first push.

**calendar_source** `id, kind(LOCAL|DEVICE), remoteId?(provider id), accountEmail?(account name), name, colorArgb, isVisible, isPrimary, accessRole(owner|reader)`. DEVICE rows are the phone's calendars, id `device:<providerId>`, always `reader`.

**event** `id, calendarId(FK), remoteId?, etag?, title, description, location, isAllDay,
startUtc(Long epoch ms, timed), endUtc, startDate(epochDay, all-day), endDateExclusive(epochDay),
timeZone(IANA), rrule?, exdates(String CSV of original-start keys), recurringParentId?, originalStartKey?,
isCancelled, colorArgb?, createdAt, updatedAt, deleted(Boolean)`. DEVICE events are one row per provider instance, id `device:<eventId>:<begin>`.
  Index: `(calendarId)`, `(startUtc,endUtc)`, `(startDate,endDateExclusive)`, `(recurringParentId)`.

**task_list** `id, title, sortOrder`

**task** `id, listId(FK), parentId?(subtask), title, notes, dueDate(epochDay?),
dueTime(minuteOfDay?), priority(0-3), isCompleted, completedAt?, sortKey,
createdAt, updatedAt, deleted`
  Index: `(listId)`, `(dueDate)`, `(parentId)`, `(isCompleted)`.

**reminder** `id, ownerType(EVENT|TASK), ownerId, offsetMinutes (events) | null, absoluteAt? (tasks w/o time use default time), method(NOTIFY)`

**scheduled_alarm** `requestCode(Int PK), reminderId, ownerId, occurrenceKey, triggerAt, isExact` is the
source of truth for cancel/reschedule and dedup.

**delivered_reminder** `key(reminderId+occurrenceKey) PK, deliveredAt` prevents duplicate notifications.

The Google sync columns (`remoteId`, `etag`, `syncState`, `syncToken`, …) are left over from the removed
Google sync. SQLite on API 26 can't drop a column, so they stay, unused and always null/LOCAL (migration 3 to 4
dropped the `outbox`, `conflict_log` and `sync_state` tables and converted the data).

Soft delete (`deleted=1`) keeps a task tombstone for the undo window, then it is purged.

### Time rules
- Timed events: `startUtc/endUtc` instants + `timeZone`. Rendering converts to the device zone, and
  recurrence expands in the **event's** zone (so "9:00 every Monday in Berlin" stays 9:00 Berlin across DST).
- All-day: `startDate`/`endDateExclusive` as epoch days. These are never converted between zones.
- Tasks: `dueDate` is a local date. Optional `dueTime` is a local wall time in the device zone.
- Occurrence key: ISO original start (`2026-10-22T09:00` local-in-event-zone, or `2026-10-22`), used by changed occurrences.

## 4. Recurrence

`RRule` supports FREQ=DAILY|WEEKLY|MONTHLY|YEARLY, INTERVAL, COUNT, UNTIL, BYDAY (weekly, and
monthly with ordinal like `2TU`/`-1FR`), BYMONTHDAY. Unsupported parts trigger a *read-only* flag: the
event shows its first occurrence only, and editing the rule is disabled with an explanation. `RecurrenceExpander.expand(event, window, overrides)`
returns `EventOccurrence`s, drops EXDATEs and cancelled overrides, and substitutes modified overrides.
Expansion is capped at 1,000 occurrences per window.

## 5. UI architecture

- One `ViewModel` per screen exposes `StateFlow<XUiState>` (immutable data classes, `@Immutable`), and events
  come in as functions. No business logic in composables.
- `ViewModelFactory` pulls dependencies from `AppContainer` (via `CreationExtras`).
- Lists use `LazyColumn` with stable `key`s and `animateItem()`.
- Editors keep the draft in `SavedStateHandle`, so it survives process death.

### Screens & navigation
Bottom bar (3 destinations + center FAB-style "Add"): **Today**, **Calendar**, **Tasks**. Search and
Settings are reached from the top bar.

```
Today ─┬─> EventDetail(id, occurrenceKey) ─> EventEditor(id?)
       ├─> TaskDetail(id) ─> TaskEditor(id?)  (quick-add is a bottom sheet)
       ├─> Search
       └─> Settings (Help & Support first) ─> Appearance | Logo | Notifications | Lists & calendars
                       | DateTime | Tasks | Privacy&Data | About ─> Troubleshooting
Calendar (Month|Week|Day|Agenda segmented) ─> EventDetail / EventEditor(prefilled time)
Tasks (filters chips, sort menu, lists) ─> TaskDetail
First launch ─> Onboarding (welcome, name, optional reminders + calendar access, skippable)
```

## 6. Design system

**Direction: "Calm editorial."** This draws on references 2 and 5 (large weekday headline with a coral dot,
plain-language summary, quiet dashed separators, generous whitespace) and borrows from the logo the warm
cream/ink base with coral and violet as the only two accents. Restrained depth: one tonal elevation
step, no gradients except inside the logo, no glassmorphism outside the bottom bar's subtle scrim.

| Token | Light | Dark |
|---|---|---|
| background | Cream `#FBF8F3` | Ink `#0E0E10` |
| surface | `#FFFFFF` | `#17171A` |
| surfaceVariant (chips, cells) | `#F2EEE7` | `#222226` |
| outline (hairlines, dashed separators) | `#E4DED4` | `#2E2E33` |
| outlineStrong (checkbox rings, ≥3:1) | `#8A847A` | `#74747C` |
| onBackground / primary text | Ink `#16161A` | Cream `#F5F1EA` |
| secondary text | `#6B6760` (5.3:1 on cream) | `#A19D96` (7.4:1 on ink) |
| primary (Coral) | `#B8381F` text/icons, `#D9442A` fills | `#FF7A5C` |
| secondary (Violet) | `#5B3FE0` | `#9B87FF` |
| success / warning / error | `#1F8A5B` / `#B26A00` / `#C62828` | `#5BD69B` / `#F2B44B` / `#FF8A80` |

Contrast values are verified in `ColorContrastTest`.
- **Type:** Inter-like geometric sans. We use the platform `FontFamily.Default` (Roboto/Google Sans on device) to avoid
  bundling fonts without a licence decision, with a tight scale: Display 44/48 Bold (weekday), Headline 28,
  Title 20/17 SemiBold, Body 16/14, Label 13/11 Medium with +0.4 tracking for caps.
- **Spacing:** 4-pt grid (4, 8, 12, 16, 20, 24, 32, 48). Screen gutters are 20dp.
- **Radii:** 8 (chips), 14 (cards/rows), 22 (sheets), full (pills, FAB). Hairline 1dp; dashed separators for timeline rows.
- **Elevation:** 0 (flat), 1 tonal (cards), and 3 (sheets/menus), with dark mode using tonal surfaces and no shadows.
- **Icons:** Material Symbols *Rounded* (androidx material-icons-extended) only, 20/24dp.
- **Event colours:** limited 8-swatch palette harmonised to the brand (Coral, Violet, Ink-blue, Teal, Amber,
  Rose, Sage, Graphite), each with light/dark tone pairs.

### Motion (`ui/theme/Motion.kt`)
`short=120ms` (press, checkbox), `medium=220ms` (list item, chip, fades), `long=320ms` (screen, sheet, view switch);
easing `EmphasizedDecelerate` for enter, `EmphasizedAccelerate` for exit, `spring(dampingRatio=0.85, stiffness=Medium)` for
selection indicators (no bounce). Navigation uses shared-axis X for peer tabs, fade-through for detail, and slide-up for editors.
Task completion: check fills (120ms), title strikes through + fades to secondary (220ms), row animates to
completed group via `animateItem`. Reduced motion is read from `Settings.Global.ANIMATOR_DURATION_SCALE == 0`
OR the in-app setting, and every spec then collapses to a 120ms fade.

## 7. Phone calendars

`DeviceCalendarSync` (in `data/device`) mirrors the Android Calendar Provider into Room, so the existing
Today, Calendar, search and calendar-list code shows the phone's events with no special cases.

- **Read.** Calendars with `SYNC_EVENTS=1`, and their instances for 60 days back and 400 days ahead through
  `Instances` (the provider expands repeats and exceptions; cancelled instances are skipped). The convenience
  `Instances.query()` is not used because it adds `visible = 1`; hidden calendars are mirrored too, so they can be
  switched on. A calendar starts with the provider's visibility.
- **Write (to Room only).** One row per instance, id `device:<eventId>:<begin>`. A refresh upserts only rows that
  differ and deletes the ones that are gone, in one transaction, under a mutex, so concurrent or repeated refreshes
  cannot duplicate. A calendar keeps the visibility and colour chosen in Daysemble; its name and account follow the phone.
- **Read-only.** DEVICE calendars have `accessRole = reader`. Create, update, move and delete refuse them, and the
  UI hides those actions. Mirrored rows are left out of exports (`BackupDao` reads LOCAL calendars only) and backups.
- **When.** On every app resume, and through a `ContentObserver` while the app is started. Bursts are debounced.
- **Failure and permission.** A read that fails keeps the last mirror and sets `failed` (shown in Lists & calendars).
  No permission, or a `SecurityException`, removes the mirror.
- **Not covered.** Google Tasks are not in the Calendar Provider, so they are never shown and the app never says
  they are synced. Reminders of phone events stay with the calendar app. Events beyond the window are not shown.

The permission is asked in onboarding (tap Allow, once; a refusal disables that button so it cannot re-prompt) and
in Settings > Lists & calendars, which also deep-links to Android settings after a permanent refusal.

## 8. Reminders

`ReminderScheduler.rescheduleFor(ownerId)` computes the next trigger(s) within the next 7 days per reminder
(for recurring events, the next occurrence only), cancels old `scheduled_alarm` rows, and calls
`setExactAndAllowWhileIdle` if `canScheduleExactAlarms()`, otherwise `setAndAllowWhileIdle` (and the row
is marked `isExact=false` → UI hint). A daily `ReminderRefreshWorker` and `BootReceiver`
(`BOOT_COMPLETED`, `TIME_SET`, `TIMEZONE_CHANGED`, `MY_PACKAGE_REPLACED`,
`SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`) call `rescheduleAll()`. `AlarmReceiver` checks
`delivered_reminder` before posting, so it posts at most once. It reloads the item (skipping if deleted/completed/moved) and
posts on channel `reminders` (high importance). Actions: Complete (task), Snooze 10 min. Late alarms (>
1 h past) for events that already ended are dropped.

## 9. Branding implementation

- `ui/brand/LogoVariant.kt`: `enum class LogoVariant(val key, @DrawableRes art, @StringRes label) { CLASSIC /*default, logo #1*/, MIDNIGHT, OUTLINE }`.
  The default comes from `LogoVariant.DEFAULT = CLASSIC`, explicitly, never from `entries.first()` alone (enforced by a test).
- Raw supplied PNGs → `res/drawable-nodpi/logo_classic.png`, `logo_midnight.png`, `logo_outline.png`,
  `wordmark_light.png`. Adaptive launcher icons use each tile's inner artwork as foreground
  on its own background colour (cream / ink), with the supplied images only scaled/cropped.
- Launcher switching: three `<activity-alias>` entries (`.LauncherClassic` enabled by default) toggled
  via `PackageManager.setComponentEnabledSetting(..., DONT_KILL_APP)`.
- **Until the real PNGs are supplied**, the drawables are neutral labelled placeholders, and `BrandLogo` shows
  "Logo asset missing". No generated artwork.

## 10. Security & privacy
No `INTERNET` permission; network security config: cleartext off. No analytics. Logging via `Logger` wrapper that strips content in release.
`dataExtractionRules`/`fullBackupContent` exclude the database and include only DataStore settings. "Delete local data" is in Settings.

## 11. Testing strategy
- **JVM unit:** RRule parsing/expansion (DST, month-end, COUNT/UNTIL, EXDATE), RelativeTime, overlap layout,
  overdue/today logic, reminder trigger computation, LogoVariant default.
- **Robolectric:** Room DAOs (in-memory), database migrations, SettingsRepository persistence, ReminderScheduler with
  ShadowAlarmManager, `DeviceCalendarSync` against a fake Calendar Provider (mapping, no duplicates, permission, failure).
- **Compose UI (Robolectric):** Today renders data, task completion, settings logo selection.
- **Screenshots:** real APK on an emulator. This sandbox has no KVM, so README screenshots must be captured
  on the owner's device/emulator (script provided) unless a hardware-accelerated environment is available.

## 12. Risks
| Risk | Mitigation |
|---|---|
| No emulator (no KVM) in build sandbox | Robolectric UI tests; owner captures screenshots with `scripts/capture_screenshots.sh`. |
| Google Tasks cannot be read from the phone | Not synced, and the UI says so. |
| Provider columns differ between devices | Only documented columns are read; a failing read keeps the last mirror. |
| Exact alarm permission denied (API 33+ default for non-alarm apps) | Inexact fallback with visible hint + link to settings. |
| Logo files not in repo yet | Placeholders + explicit request to owner. |
