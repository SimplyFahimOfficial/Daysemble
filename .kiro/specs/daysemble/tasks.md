# Daysemble — Implementation Plan

Each milestone ends with `./gradlew assembleDebug testDebugUnitTest lintDebug` passing and a short report.
`[x]` = done and verified by build + automated tests (JVM/Robolectric, incl. screen smoke tests and off-device renders). `[d]` = additionally needs a physical device/emulator or live Google account to sign off. `[ ]` = blocked on the owner.

## M1 Project initialization & architecture
- [x] 1.1 Gradle 9.6 wrapper, AGP 9.4.1, compileSdk 37 (required by Compose BOM 2026.09) / targetSdk 36, version catalog, AGP 9 / Compose / Room / KSP / WorkManager / Navigation / DataStore / serialization (R1.1, R1.2)
- [x] 1.2 Manifest, permissions, network security config, backup rules (notification channel + receivers land in M6) (R14)
- [x] 1.3 `DaysembleApp`, `AppContainer`, `MainActivity` (edge-to-edge, splash)
- [x] 1.4 Test infrastructure: JUnit4, Truth, Turbine, Robolectric, coroutines-test, MockWebServer
- [x] 1.5 Verify: `assembleDebug`, `testDebugUnitTest` (14 tests), `lintDebug` (0 errors) pass

## M2 Design system, branding, themes, logo selection
- [x] 2.1 Tokens: Color (light/dark), Type, Shape, Spacing, Motion, `DaysembleTheme` (R3)
- [x] 2.2 Contrast test for every documented pairing (R3.2)
- [x] 2.3 `LogoVariant` (explicit CLASSIC default), `BrandLogo`, placeholder assets, launcher activity-aliases, `scripts/import_logos.py`, Appearance screen with logo picker (R2)
- [x] 2.4 `SettingsRepository` (DataStore): theme mode, logo, reduced motion + tests (R2.2, R3.1)
- [x] 2.5 Shared components: buttons, rows, check, chips, section header, state views (loading/empty/error/offline)
- [x] 2.6 Supplied logos imported (Logo1 = Classic default, Logo2 = Midnight, Logo3 = Outline, Wordlogo); in-app 512 px, launcher icons in the 66dp safe zone, checked by eye

## M3 Navigation, Today, local tasks
- [x] 3.1 Room DB v1: all entities from design §3, DAOs, converters, schema export
- [x] 3.2 TaskRepository (CRUD, complete/undo, subtasks, filters, sort, overdue) + DAO tests (R7)
- [x] 3.3 (Robolectric screen smoke tests + off-device render reviewed) Nav host, bottom bar, shared-axis transitions (R12)
- [x] 3.4 (Robolectric screen smoke tests + off-device render reviewed) Tasks screen (filters, sort, search field, swipe actions, completion animation)
- [x] 3.5 (Robolectric screen smoke tests + off-device render reviewed) Task quick-add sheet, Task detail/editor (SavedStateHandle drafts)
- [x] 3.6 (Robolectric screen smoke tests + off-device render reviewed) Today screen with summary sentence, timeline, overdue, completed (R4)

## M4 Calendar & events
- [x] 4.1 RRule parser + expander + tests (DST, month end, EXDATE, COUNT/UNTIL) (R5.5)
- [x] 4.2 EventRepository: occurrences in window, CRUD, delete single occurrence (EXDATE) (R6)
- [x] 4.3 Overlap layout algorithm + tests (R5.3)
- [x] 4.4 (Robolectric screen smoke tests + off-device render reviewed) Month, Week, Day, Agenda views + view switcher animation (R5)
- [x] 4.5 (Robolectric screen smoke tests + off-device render reviewed) Event editor (date/time pickers, all-day, zone, recurrence, reminders, calendar) + Event detail with live countdown (R6.3)
- [x] 4.6 RelativeTime + midnight/zone boundary tests (R4.3)

## M5 Search & time behaviour
- [x] 5.1 (Robolectric screen smoke tests + off-device render reviewed) Search screen across events & tasks with filters (R8)
- [x] 5.2 (Robolectric screen smoke tests + off-device render reviewed) Date & time preferences wired (first day of week, 24h, default duration)

## M6 Reminders & notifications
- [x] 6.1 (Robolectric: real AlarmManager registration, receiver, single notification, actions) ReminderScheduler (exact/inexact), AlarmReceiver, dedup table, NotificationFactory with actions (R9)
- [x] 6.2 (Robolectric: BOOT_COMPLETED broadcast and refresh worker reschedule alarms) BootReceiver/time-change/permission-change rescheduling + daily refresh worker
- [d] 6.3 (in-context permission + honest status; exact-alarm flow needs device) Permission UX (POST_NOTIFICATIONS, exact alarm), honest reminder status in editors
- [x] 6.4 Robolectric tests (schedule / cancel / reschedule / dedup)

## M7-M9 Google Calendar, Google Tasks, sync reliability (REMOVED)
Built and later removed: the app no longer talks to Google. The phone's calendars are read-only through the
Calendar Provider instead (M12), and Google Tasks are not synced.

## M10 Settings, accessibility, motion, polish
- [x] 10.1 (Robolectric screen smoke tests + off-device render reviewed) Complete Settings (R11) incl. Troubleshooting (reschedule reminders)
- [x] 10.2 (Robolectric screen smoke tests + off-device render reviewed) Onboarding (welcome, permissions, optional calendar access)
- [d] 10.3 (semantics, 48dp targets, AA contrast, 2x font @320dp smoke test; TalkBack on device pending) Accessibility pass (semantics, 200% font, touch targets, TalkBack labels) (R13)
- [x] 10.4 (reduced motion everywhere incl. list items, pager, system setting re-read) Motion pass + reduced motion (R12.2)
- [d] 10.5 (off-device renders of Today/Calendar/Tasks/Appearance in light+dark reviewed; placeholder label fix applied; final on-device pass pending) Visual audit of every component in light & dark

## M10b Gaps found in review (owner request)
- [x] 10b.1 Event time-zone picker (searchable, keeps wall-clock time) + tests
- [x] 10b.2 Move task (with subtasks) between lists
- [x] 10b.3 Lists & calendars management: create/rename/delete on-device, show/hide (hidden calendars don't remind), local colour + tests
- [x] 10b.4 Drag to reorder in List order, with Move up/down accessibility actions (order is local) + tests
- [x] 10b.5 Series with unsupported RRULEs are shown read-only
- [x] 10b.6 GitHub Actions CI (build, tests, lint, R8) + env-var release signing (verified: signed APK with a throwaway key)

## M11 Testing, performance, docs, screenshots
- [x] 11.1 (lint 0 errors; release R8 build passes) Lint/static analysis clean, Compose stability check
- [x] 11.2 README (real features only), ARCHITECTURE.md, limitations
- [x] 11.3 `scripts/capture_screenshots.sh` (adb) + demo-data seeding (debug only, clearly labelled)
- [ ] 11.4 Moved to "Later" below
- [ ] 11.5 Commit/push **only after owner approval**

## Later (deferred by the owner)
- [ ] L.1 Capture genuine screenshots on a device or emulator with `scripts/capture_screenshots.sh` → `docs/screenshots/`, then add them to the README. Deferred by the owner on 2026-10-22. Needs an owner device, or an environment with KVM (this sandbox has none). Never use mock-ups or renders in place of real captures.

## M12 Native calendars only
- [x] 12.1 Google Calendar & Tasks removed: API clients, OAuth, sync engine, Accounts page, header sync pill, dependencies, `INTERNET` permission
- [x] 12.2 Room v4 migration: sync tables dropped, Google events removed, Google task lists kept as on-device lists (R10.8)
- [x] 12.3 `DeviceCalendarSync`: the phone's calendars mirrored read-only into Today/Calendar/search/Lists & calendars (R10.3-R10.6)
- [x] 12.4 Calendar access in onboarding: asked once on tap, refusal handled quietly (R10.2)
- [x] 12.5 Help & Support first in Settings; no Accounts section
