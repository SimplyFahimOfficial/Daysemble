# Daysemble — Technical Design

## 1. Key decisions

| Decision | Choice | Why |
|---|---|---|
| UI | Jetpack Compose + Material 3 (heavily themed) | Native, modern, testable. M3 provides accessibility and theming plumbing, and our tokens supply the look. |
| Build | AGP 9.4 (built-in Kotlin), Gradle 9.x, Kotlin 2.x via AGP, version catalog | Current stable toolchain (verified against Google Maven, Oct 2026). |
| DI | **Manual `AppContainer`** | One module, about 15 singletons. Hilt adds kapt/KSP + plugin-compat risk for little gain. Constructor injection keeps everything testable. |
| Persistence | Room (KSP) + Preferences DataStore | Room for domain data + sync metadata. DataStore for settings. |
| Async | Coroutines + Flow, `StateFlow` UI state per screen | Standard, lifecycle-aware via `collectAsStateWithLifecycle`. |
| Navigation | Navigation Compose with type-safe `@Serializable` routes | Back stack + process-death restoration for free. |
| Google auth | Identity Services `AuthorizationClient` (play-services-auth) | Google's current Android API for OAuth scopes. It returns short-lived access tokens, and the app stores no secret or refresh token. |
| Google APIs | Thin REST clients over OkHttp + kotlinx.serialization | Google's Java client libs are large and reflection-heavy. We need about 10 endpoints, and these clients are simple to test with MockWebServer. |
| Background | WorkManager (sync), AlarmManager (reminders) | WorkManager for deferrable network work. AlarmManager is the only option for user-visible time-precise reminders. |
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
│   └── google/
│       ├── auth/              GoogleAuthManager (AuthorizationClient), TokenProvider
│       ├── calendar/          CalendarApi (REST), DTOs, CalendarMapper, CalendarSyncer
│       ├── tasks/             TasksApi (REST), DTOs, TasksMapper, TasksSyncer
│       └── sync/              SyncWorker, SyncScheduler, SyncStatusStore, Outbox
├── domain/model/              Event, EventOccurrence, Task, Subtask, Priority, Reminder, CalendarSource…
├── reminders/                 ReminderScheduler, AlarmReceiver, BootReceiver, NotificationFactory
└── ui/
    ├── theme/                 Color, Type, Shape, Spacing, Motion, DaysembleTheme
    ├── brand/                 LogoVariant, BrandLogo composable
    ├── components/            DsButton, DsCard, DsCheck, DsChip, SectionHeader, StateViews, SyncBadge, DateStrip…
    ├── navigation/            Routes, DaysembleNavHost, bottom bar
    ├── today/  calendar/  tasks/  event/  task/  search/  settings/  onboarding/
```

## 3. Data model (Room)

All primary keys are local UUID strings. Remote ids are separate columns, so the mapping stays stable
even before the first push.

**calendar_source** `id, kind(LOCAL|GOOGLE), remoteId?, name, colorArgb, isVisible, isSyncEnabled, syncToken?, accessRole`

**event** `id, calendarId(FK), remoteId?, etag?, title, description, location, isAllDay,
startUtc(Long epoch ms, timed), endUtc, startDate(epochDay, all-day), endDateExclusive(epochDay),
timeZone(IANA), rrule?, exdates(String CSV of original-start keys), recurringParentId?, originalStartKey?,
isCancelled, colorArgb?, createdAt, updatedAt, remoteUpdatedAt?, syncState(SYNCED|PENDING_CREATE|PENDING_UPDATE|PENDING_DELETE|CONFLICT), deleted(Boolean)`
  Index: `(calendarId)`, `(startUtc,endUtc)`, `(startDate,endDateExclusive)`, `(remoteId)` unique per calendar, `(recurringParentId)`.

**task_list** `id, kind, remoteId?, title, sortOrder, isSyncEnabled, lastSyncedAt?`

**task** `id, listId(FK), parentId?(subtask), remoteId?, etag?, title, notes, dueDate(epochDay?),
dueTime(minuteOfDay?, local only), priority(0-3, local only), isCompleted, completedAt?, position,
createdAt, updatedAt, remoteUpdatedAt?, syncState, deleted`
  Index: `(listId)`, `(dueDate)`, `(parentId)`, `(isCompleted)`, `(remoteId)`.

**reminder** `id, ownerType(EVENT|TASK), ownerId, offsetMinutes (events) | null, absoluteAt? (tasks w/o time use default time), method(NOTIFY)`

**scheduled_alarm** `requestCode(Int PK), reminderId, ownerId, occurrenceKey, triggerAt, isExact` is the
source of truth for cancel/reschedule and dedup.

**delivered_reminder** `key(reminderId+occurrenceKey) PK, deliveredAt` prevents duplicate notifications.

**outbox** `id, entityType(EVENT|TASK), entityId, op(CREATE|UPDATE|DELETE|MOVE), createdAt, attempts, lastError?`

**conflict_log** `id, entityType, entityId, localJson, remoteJson, resolvedWith(LOCAL|REMOTE), at`

**sync_state** `service(CALENDAR|TASKS) PK, lastSuccessAt?, lastAttemptAt?, lastError?, isRunning`

Soft delete (`deleted=1`) keeps a tombstone until the remote delete succeeds. Local-only items are
hard-deleted after the undo window (5 s).

### Time rules
- Timed events: `startUtc/endUtc` instants + `timeZone`. Rendering converts to the device zone, and
  recurrence expands in the **event's** zone (so "9:00 every Monday in Berlin" stays 9:00 Berlin across DST).
- All-day: `startDate`/`endDateExclusive` as epoch days. These are never converted between zones.
- Tasks: `dueDate` is a local date. Optional `dueTime` is a local wall time in the device zone.
- Occurrence key: ISO original start (`2026-10-22T09:00` local-in-event-zone, or `2026-10-22`). It
  matches Google's `originalStartTime` for overrides.

## 4. Recurrence

`RRule` supports FREQ=DAILY|WEEKLY|MONTHLY|YEARLY, INTERVAL, COUNT, UNTIL, BYDAY (weekly, and
monthly with ordinal like `2TU`/`-1FR`), BYMONTHDAY. Unsupported parts trigger a *read-only* flag: the
event is displayed through Google's own instance list fallback (`events.instances` for the visible window),
and editing the rule is disabled with an explanation. `RecurrenceExpander.expand(event, window, overrides)`
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
       └─> Settings ─> Appearance | Logo | Notifications | Accounts&Sync ─> Calendars/Lists picker
                       | DateTime | Tasks | Privacy&Data | About ─> Troubleshooting (sync log, conflicts)
Calendar (Month|Week|Day|Agenda segmented) ─> EventDetail / EventEditor(prefilled time)
Tasks (filters chips, sort menu, lists) ─> TaskDetail
First launch ─> Onboarding (2 pages: welcome + optional permissions/Google connect, skippable)
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

## 7. Google sync

### Auth
`GoogleAuthManager.authorize(activity)` calls `Identity.getAuthorizationClient(ctx).authorize(request)` with
scopes `https://www.googleapis.com/auth/calendar`, `.../auth/tasks`, plus `email`. If a `PendingIntent` is
returned, it's launched with `ActivityResultLauncher`. Background workers call `authorize` silently. If it
needs resolution, sync status becomes `NeedsReauth` and the user sees a "Reconnect" chip. Access tokens are
cached in memory only. On 401 we call `clearToken` and retry once. Disconnect calls `revokeAccess` and removes
all `GOOGLE`-kind sources + their items locally.

**Misconfiguration detection:** `ApiException` status `DEVELOPER_ERROR (10)` or missing OAuth client → `SetupRequired` state with link to `docs/GOOGLE_SETUP.md`.

### Ownership
- A record created in Daysemble in a Google calendar/list is owned by Google after first push (`remoteId` set).
- Local-only calendars/lists ("On this device") never sync.
- Remote is the source of truth for *existence*. A local delete pushes a remote delete, and a remote
  delete (`status=cancelled` / `deleted=true`) removes local, **unless** the local copy has unpushed edits,
  in which case it becomes a CONFLICT and is kept locally (no silent loss).

### Algorithm (per service, inside `SyncWorker`, mutex-guarded)
1. **Push outbox** in FIFO order.
   - CREATE (Calendar): POST with a client-supplied event `id` (base32hex of our UUID). A retried create
     therefore returns 409 "duplicate" instead of producing a second event, and 409 is treated as success + GET.
   - CREATE (Tasks): the API has no client ids. The returned id is persisted immediately. If a previous attempt
     had an unknown outcome (attempts > 0, timeout), we first list the target list (`updatedMin` = attempt time − 2 min)
     and adopt a task with identical title + due + notes before POSTing again (documented heuristic).
   - UPDATE: PATCH (Calendar sends `If-Match: etag`, and 412 → conflict path). Tasks has no etag preconditions, so we
     compare remote `updated` against our `remoteUpdatedAt` before PATCH.
   - DELETE: 404/410 count as success.
2. **Pull**: Calendar `events.list(calendarId, syncToken | full, showDeleted=true, singleEvents=false,
   maxResults=250)`, paging until `nextSyncToken`. 410 → drop token, full resync, reconciling by `remoteId`.
   Tasks `tasks.list(updatedMin=lastSuccess-5min, showDeleted, showHidden, showCompleted, maxResults=100)` paged.
3. **Merge** each remote item by `remoteId`:
   - local SYNCED → overwrite with remote.
   - local PENDING_* and remote `updated` > local `remoteUpdatedAt` → **conflict**: field-level last-writer-wins
     using local `updatedAt` vs remote `updated`. The losing version is written to `conflict_log`, and the item
     is re-queued if local wins.
4. Save `sync_state.lastSuccessAt`, clear error. Errors are classified: `Network` (retry), `RateLimited`/`Server`
   (backoff 30 s → 6 h), `Auth` (NeedsReauth), `NotFound` (drop op), `Client` (log + mark op failed after 5 attempts).

**Triggers:** periodic 1 h (`NetworkType.CONNECTED`), one-off expedited after local edits (debounced 5 s
via `ExistingWorkPolicy.REPLACE` + initial delay), app start/foreground if last success > 15 min, manual pull-to-refresh.

**Calendar specifics:** recurring masters are stored with `rrule`, and overrides with `recurringParentId +
originalStartKey`. Editing a recurring Google series PATCHes the master. Time zones use `start.timeZone`.
**Tasks specifics:** `due` is date-only (RFC3339 midnight UTC → parse the date part only), subtasks use
`parent`, and moves use `tasks.move`. Completion = `status=completed`. Priority/dueTime stay local.

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
Network security config: cleartext off. No analytics. Logging via `Logger` wrapper that strips content in release.
`dataExtractionRules`/`fullBackupContent` exclude the database and include only DataStore settings. The Google token
never touches disk. The disconnect + "Delete local data" options are in Settings.

## 11. Testing strategy
- **JVM unit:** RRule parsing/expansion (DST, month-end, COUNT/UNTIL, EXDATE), RelativeTime, overlap layout,
  overdue/today logic, reminder trigger computation, conflict resolver, DTO↔entity mappers, LogoVariant default.
- **Robolectric:** Room DAOs (in-memory), SettingsRepository persistence, ReminderScheduler with ShadowAlarmManager,
  sync engine against MockWebServer (pagination, 410 resync, 412 conflict, idempotent create, partial failure).
- **Compose UI (Robolectric):** Today renders data, task completion, settings logo selection.
- **Screenshots:** real APK on an emulator. This sandbox has no KVM, so README screenshots must be captured
  on the owner's device/emulator (script provided) unless a hardware-accelerated environment is available.

## 12. Risks
| Risk | Mitigation |
|---|---|
| No emulator (no KVM) in build sandbox | Robolectric UI tests; owner captures screenshots with `scripts/capture_screenshots.sh`. |
| OAuth requires owner's Cloud project + SHA-1 | `docs/GOOGLE_SETUP.md`, graceful SetupRequired state. |
| Google Tasks API lacks priority/time/client ids | Kept local, documented. Create-retry heuristic. |
| Exact alarm permission denied (API 33+ default for non-alarm apps) | Inexact fallback with visible hint + link to settings. |
| Logo files not in repo yet | Placeholders + explicit request to owner. |
