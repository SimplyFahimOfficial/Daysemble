# Daysemble

A calm Android calendar and task planner. Events, tasks and reminders live in one place, with
optional two-way sync with **Google Calendar** and **Google Tasks**.

Kotlin · Jetpack Compose · Material 3 · Room · WorkManager · minSdk 26

> **Status: early development (v0.1).** The app builds and has 116 passing automated tests,
> including Robolectric smoke tests that render every main screen with real data. It has
> **not yet been run on a physical device or emulator**, and live Google sign-in is unverified.
> See [Verification status](#verification-status).

## Screenshots

There are no screenshots yet. This README will only ever show genuine captures of the running app,
taken with [`scripts/capture_screenshots.sh`](scripts/capture_screenshots.sh) and stored in
`docs/screenshots/`.

## Features

**Today**
- Large weekday headline and a plain-language summary built from your data ("You have 3 events and 2 tasks. You're free after 13:30.").
- A timeline of all-day events, timed events and tasks, with a live countdown for the next event.
- Overdue tasks, and a collapsible list of tasks completed today.
- A swipeable day strip to look at other days.

**Calendar**
- Month, Week, Day and Agenda views with swipe navigation and a *Today* jump.
- Overlapping events sit side by side. Events crossing midnight and all-day events are handled.
- Tap an empty time slot to create an event there.

**Events**
- Title, notes, location, all-day, start/end, **time zone** (searchable picker; changing it keeps the wall-clock time), calendar and reminders.
- Repeat rules: daily, weekly on chosen days, monthly or yearly, with an interval and an end (never, after N, or until a date).
- Delete one occurrence or the whole series.
- The detail screen shows a live countdown ("in 25 min" / "ends in 10 min") and an *Open in Maps* button.
- Times are stored as UTC instants plus an IANA time zone. Repeats expand in the event's own zone, so they stay correct across DST.

**Tasks**
- Due date, optional time, priority, notes, reminders and one level of subtasks.
- Filters (All, Today, Upcoming, Overdue, Completed), sorting, search, and lists.
- Swipe to complete or delete, with undo.
- Move a task (with its subtasks) to another list, including between on-device and Google lists.
- *List order* sort with long-press drag to reorder (and *Move up/down* actions for screen readers). The order is kept on this device.

**Lists & calendars**
- Create, rename and delete on-device task lists and calendars. The last on-device one can't be deleted.
- Show or hide any calendar. Hidden calendars don't appear and don't remind; nothing is deleted.
- Pick a colour per calendar. For Google calendars this is a local display choice and isn't written back to Google.

**Search** across event and task titles, notes and locations.

**Reminders**
- Exact alarms where Android allows them, with an inexact fallback and an on-screen note when timing may be late.
- Rescheduled after reboot, time or time-zone changes and app updates. Never posted twice.
- Notification actions: *Complete* (tasks) and *Snooze 10 min*. An optional setting hides details on the lock screen.
- If notifications are blocked, the editors say so instead of implying reminders will arrive.

**Google Calendar & Google Tasks (two-way)**
- Sign-in via Google Identity Services `AuthorizationClient`. No client secret, no stored tokens.
- Calendar: incremental sync with `syncToken`, a full resync on HTTP 410, recurring series with exceptions, and duplicate-free creates using client-supplied ids.
- Tasks: incremental sync with `updatedMin` and subtasks. Priority and due time stay local, because Google Tasks has no such fields.
- Offline edits are queued and pushed later with exponential backoff.
- Conflicts are resolved by last-writer-wins and logged under *Sync conflicts*. A remote deletion never discards unsynced local edits.
- Choose which calendars and task lists to sync. Disconnecting revokes access and removes local copies; it never deletes anything from Google.

**Design**
- Warm editorial look derived from the logo: cream and ink, with coral and violet accents.
- Light, dark and system themes. One motion system with reduced-motion support.
- Three selectable app logos with matching launcher icons. *Classic* (supplied logo #1) is the default.

## Build

Requirements: **JDK 17+** (developed with 21) and the **Android SDK with platform 37** (compileSdk 37, targetSdk 36).

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM + Robolectric tests
./gradlew lintDebug
./gradlew installDebug           # on a connected device/emulator
```

Google sync works only after the one-time Cloud Console setup in
**[docs/GOOGLE_SETUP.md](docs/GOOGLE_SETUP.md)**. Without it, everything else works and *Connect*
explains that setup is required.

### Release signing

Release builds are signed only when these environment variables are set, so no keys live in the repo:
`DAYSEMBLE_KEYSTORE_PATH`, `DAYSEMBLE_KEYSTORE_PASSWORD`, `DAYSEMBLE_KEY_ALIAS`, `DAYSEMBLE_KEY_PASSWORD`.
Otherwise `assembleRelease` produces an unsigned APK.

### Continuous integration

`.github/workflows/android.yml` runs the debug build, unit tests, lint and an R8 release build on every
push to `main` and on pull requests. It uploads the reports and a debug APK as artifacts. To sign
CI release builds, add the repository secrets `DAYSEMBLE_KEYSTORE_BASE64` (the keystore, base64-encoded),
`DAYSEMBLE_KEYSTORE_PASSWORD`, `DAYSEMBLE_KEY_ALIAS` and `DAYSEMBLE_KEY_PASSWORD`.

### Logo assets

The three supplied logos and the wordmark are imported with an explicit mapping, so file order
doesn't matter:

```bash
pip install pillow
python3 scripts/import_logos.py --classic brand/logo1.png --midnight brand/logo2.png --outline brand/logo3.png --wordmark brand/wordmark.png
```

The script keeps the originals in `brand/`. It removes only the black square around each tile
(the artwork itself isn't recoloured or redrawn), then resizes the tile to 512 px for in-app use. For the
launcher icon, the tile is scaled to 86% of the adaptive canvas so the orbit ring isn't clipped
by round masks. The wordmark is trimmed and resized to 900 px wide. Until the files are imported,
the app shows neutral placeholders labelled *Asset missing*. **Status: the files haven't been imported yet.**

## Architecture

A single `:app` module with manual dependency injection (`AppContainer`) and MVVM + `StateFlow` per screen.

```
app/src/main/java/app/daysemble/
├── core/recurrence   RRULE parser/expander (DST-safe, EXDATE, COUNT/UNTIL)
├── core/time         clock, countdowns, overlap layout
├── data/db           Room entities/DAOs: events, tasks, reminders, alarms, outbox, conflicts
├── data/repo         EventRepository, TaskRepository (local-first, outbox for Google items)
├── data/google       auth, REST clients (OkHttp + kotlinx.serialization), Calendar/Tasks syncers, SyncManager/Worker
├── reminders         planner, AlarmManager scheduler, receivers, notifications
├── domain            models and pure task rules
└── ui                theme/tokens, components, screens (today, calendar, tasks, event, task, search, settings, onboarding)
```

The full spec is in [`.kiro/specs/daysemble/`](.kiro/specs/daysemble): requirements, design (data
model, sync algorithm, conflict rules, reminders) and the task plan.

## Privacy

- Data is stored on the device. There are no analytics and no Daysemble server.
- Network traffic goes only to Google's APIs, and only when you connect an account.
- Backups include settings only. The database and account state are excluded.
- Logs never contain event or task content, or tokens.

## Known limitations

- You can't edit a single occurrence of a repeating event yet (the whole series can be edited, and single occurrences deleted).
- Attendees and conferencing aren’t shown. They’re preserved because the app PATCHes only the fields it manages. Per-event Google colours are shown (mapped to the nearest Daysemble swatch) but can’t be changed in Daysemble yet.
- No travel time or distance, because that would need a Maps API key. *Open in Maps* is offered instead.
- Reminders are planned 8 days ahead and refreshed automatically. Force-stopping the app cancels alarms until it's opened again (an Android rule).
- Google Tasks has no cross-list move, so moving a Google task deletes it from the old list and re-creates it in the new one. Its Google id changes, and its manual order stays local.
- Google Tasks can't recognise a repeated create request, so a create that may have succeeded but timed out is matched by title, notes and due date before retrying.
- Screens are untested on tablets and foldables, though they should still be usable there.

## Verification status

| Area | Verified by |
|---|---|
| Recurrence, countdowns, overlap layout, task rules | unit tests |
| Room repositories, outbox coalescing | Robolectric tests |
| Reminder planning and scheduling (cancel/reschedule/dedupe/zone change) | Robolectric tests with a fake AlarmManager |
| Calendar and Tasks sync (pagination, 410, idempotent create, conflicts, deletes, 401 refresh) | MockWebServer tests, **not live Google** |
| Settings and logo persistence, WCAG contrast | unit tests |
| Every main screen renders with data and handles key input (both themes, 2× font at 320dp) | Robolectric Compose smoke tests |
| Alarm registration, reminder notification + actions, no duplicates, boot rescheduling, workers | Robolectric with the real Android framework shadows |
| Google series with rules the app can't expand (shown via Google's `instances`) | MockWebServer test |
| Real-device look and feel, TalkBack, exact-alarm permission flow, live Google sign-in | **not yet verified on a device** |

## License

See [LICENSE](LICENSE).
