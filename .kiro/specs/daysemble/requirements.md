# Daysemble — Requirements

Native Android calendar + task app (Kotlin, Jetpack Compose). Acceptance criteria use EARS notation
(WHEN / IF / WHILE … THE SYSTEM SHALL …).

## MVP scope

**In scope (v1.0):** Today dashboard, Calendar (month / week / day / agenda), local events and
tasks, subtasks, priorities, reminders, search and filters, a read-only view of the phone's calendars
(Google sync was removed; see R10), Settings (theme, logo, notifications, date/time,
data), light + dark themes, accessibility, motion system.

**Out of scope for v1.0 (documented as limitations):**
- Travel time / distance. There's no reliable source without a Maps/Routes API key and billing,
  so v1 shows the location text and an "Open in Maps" intent and never makes up a duration.
- Editing a *single occurrence* of a recurring event from the phone's calendars (they are read-only). Deleting
  one occurrence works locally through EXDATE.
- Attendees / invitations, conferencing links (shown read-only if present), home-screen widgets,
  Wear OS, tablets-specific two-pane layouts (layouts must still be usable on tablets).
- Weather, health metrics, "scores" (seen in the references). Out of scope, so we don't invent data.

## Personas
- **Planner**: checks Today several times a day, adds tasks quickly, relies on reminders.
- **Calendar-app user**: already lives in the phone's calendar app (for example a synced Google account) and wants Daysemble as a nicer front end
  without duplicates or data loss.

---

## R1 Project foundation
1.1 THE SYSTEM SHALL be a single-module Android app, package `app.daysemble`, minSdk 26, targetSdk 36, compileSdk 37 (required by the current Compose/AndroidX releases).
1.2 THE SYSTEM SHALL build with `./gradlew assembleDebug` and run unit tests with `./gradlew testDebugUnitTest` without any credentials.
1.3 THE SYSTEM SHALL keep secrets out of VCS. The app uses no account, API key or client secret.

## R2 Branding and logo selection
2.1 THE SYSTEM SHALL ship three logo variants mapped explicitly (not by file order):
   `CLASSIC` = logo #1 (cream tile, coloured glass squares, **default**), `MIDNIGHT` = logo #2 (black tile, filled),
   `OUTLINE` = logo #3 (black tile, outlined shapes). The horizontal wordmark is a separate brand asset.
2.2 WHEN the app is freshly installed THE SYSTEM SHALL select `CLASSIC`.
2.3 WHEN the user selects a logo in Settings THE SYSTEM SHALL persist it, update in-app branding immediately, and switch the launcher icon (activity-alias), with a note that some launchers take a moment to refresh.
2.4 IF a logo asset is missing THE SYSTEM SHALL show a neutral, clearly labelled placeholder and never a fabricated logo.
2.5 THE SYSTEM SHALL use only the supplied artwork. Cropping/padding for adaptive-icon safe zones is allowed, and recolouring or redrawing isn't.

## R3 Design system and themes
3.1 THE SYSTEM SHALL provide Light, Dark and System theme modes, persisted, applied without restart.
3.2 THE SYSTEM SHALL derive its palette from the brand: Cream, Ink, Coral, Violet. Every text/background pairing SHALL meet WCAG AA (4.5:1 body, 3:1 large text / UI glyphs).
3.3 THE SYSTEM SHALL define tokens for colour, type, spacing, radii, elevation, motion and use them in every screen (no ad-hoc values in feature code).
3.4 THE SYSTEM SHALL provide shared components for loading, empty, error and success states.

## R4 Today
4.1 THE SYSTEM SHALL show a headline (weekday + brand dot, date) and a one-sentence plain-language summary computed from real data (e.g. "2 events and 3 tasks today").
4.2 THE SYSTEM SHALL list today's timeline (all-day first, then events by start), due-today tasks, overdue tasks (highlighted), and recently completed tasks (collapsed).
4.3 WHEN an event is upcoming or in progress THE SYSTEM SHALL show relative time ("in 25 min", "ends in 10 min") that refreshes at least every minute while visible.
4.4 THE SYSTEM SHALL provide quick add for task and event from Today.
4.5 (Removed with Google sync.)

## R5 Calendar
5.1 THE SYSTEM SHALL offer Month, Week, Day and Agenda views with swipe/arrow navigation and a "Today" jump.
5.2 THE SYSTEM SHALL highlight the current day and render all-day events in a dedicated lane.
5.3 THE SYSTEM SHALL lay out overlapping timed events side-by-side without overlap or clipping of titles.
5.4 THE SYSTEM SHALL split events crossing midnight correctly across days, using the device zone at render time.
5.5 THE SYSTEM SHALL support recurrence: DAILY, WEEKLY (BYDAY), MONTHLY (by month-day), YEARLY, with INTERVAL, COUNT, UNTIL, EXDATE and changed occurrences.
5.6 WHEN the user changes date/view THE SYSTEM SHALL animate without flicker or layout jumps.

## R6 Events
6.1 THE SYSTEM SHALL create, edit and delete events with: title (required), description, start/end, all-day, time zone, location, reminders (0..5 offsets), recurrence, calendar.
6.2 IF end < start THE SYSTEM SHALL block save with an inline error.
6.3 THE SYSTEM SHALL show event details including countdown/relative time and an "Open in Maps" action when a location exists.
6.4 THE SYSTEM SHALL store timed events as UTC instants + IANA zone, and all-day events as local dates (no zone shift).

## R7 Tasks
7.1 THE SYSTEM SHALL create, edit, complete/uncomplete, delete (with undo) tasks: title, notes, due date, optional due time, priority (None/Low/Medium/High), reminders, list, subtasks (one level).
7.2 THE SYSTEM SHALL mark tasks overdue WHEN incomplete and due before now (date-only tasks: before today).
7.3 THE SYSTEM SHALL provide filters (All, Today, Upcoming, Overdue, Completed, by list, by priority), sorting (due, priority, created, manual), and text search.
7.4 THE SYSTEM SHALL persist all task state in Room so it survives restarts and process death. Editor drafts survive configuration change via SavedStateHandle.
7.5 Tasks are stored on the device only. THE SYSTEM SHALL NOT claim that tasks are synced with Google Tasks: the Android Calendar Provider carries no tasks, so Google Tasks cannot be read through it.

## R8 Search
8.1 THE SYSTEM SHALL search titles, notes/descriptions and locations across events and tasks, grouped by type, with filters for upcoming / overdue / completed.

## R9 Reminders and notifications
9.1 THE SYSTEM SHALL schedule reminders with AlarmManager. It uses exact alarms when `canScheduleExactAlarms()` returns true and otherwise falls back to inexact while-idle alarms, and the UI says the reminder may be late.
9.2 WHEN an item is edited, completed, deleted, or its reminders change THE SYSTEM SHALL cancel and reschedule its alarms.
9.3 WHEN the device reboots, the time / time zone changes, the app updates, or exact-alarm permission changes THE SYSTEM SHALL reschedule all future reminders.
9.4 THE SYSTEM SHALL request POST_NOTIFICATIONS (API 33+) in context. IF it is denied THE SYSTEM SHALL show reminders as "Notifications off", never as configured.
9.5 THE SYSTEM SHALL prevent duplicate notifications. Each reminder has a stable id, and delivered reminders are recorded.
9.6 Notifications SHALL offer "Complete" (tasks) and "Snooze 10 min" actions.
9.7 Notification content SHALL respect a "hide details on lock screen" setting.

## R10 Phone calendars (replaces the Google integration, which was removed)
10.1 THE SYSTEM SHALL read the phone's calendars through the Android Calendar Provider with `READ_CALENDAR` only (no write permission, no account, no network).
10.2 THE SYSTEM SHALL ask for calendar access at most once during first-launch onboarding, only when the user taps Allow. A refusal SHALL be handled quietly (no repeated prompt; Settings > Lists & calendars offers it later), and finishing onboarding without it SHALL never ask.
10.3 WHEN access is allowed THE SYSTEM SHALL mirror every calendar that is synced to the phone, with its events (repeats and exceptions expanded by the provider), as read-only calendars. They SHALL appear in Today, Calendar, search and Lists & calendars, where each can be shown or hidden and recoloured. Those choices SHALL survive refreshes.
10.4 The mirror SHALL refresh when the app comes to the front and while it is on screen when the provider reports a change. A refresh SHALL rewrite only rows that differ, with stable ids, so repeated refreshes never duplicate events.
10.5 IF the provider cannot be read THE SYSTEM SHALL keep the last mirror and say so in Lists & calendars. IF access is withdrawn THE SYSTEM SHALL remove the mirror.
10.6 Mirrored events SHALL be read-only and SHALL NOT be written to exports or backups.
10.7 THE SYSTEM SHALL NOT display Google Tasks as synced. Tasks stay on the device.
10.8 Upgrading from a version with Google sync SHALL keep every task (Google task lists become on-device lists) and remove only the copies of Google calendar events, which the phone's calendar now supplies. Nothing is deleted from Google.

## R11 Settings
11.1 Appearance: theme mode, logo, reduced motion (follows system by default).
11.2 Notifications: enable reminders, default event reminder, default task reminder time, lock-screen privacy, permission status + fix links (notifications, exact alarms, battery optimization info).
11.3 Lists & calendars: on-device calendars and lists, the phone's calendars (access, show/hide, colour). Help & Support leads the Settings page.
11.4 Date & time: first day of week, 24-hour clock (default: system), default event duration.
11.5 Tasks: default list, show completed on Today, default sort.
11.6 Privacy & data: what is stored, delete all local data, export (JSON) — optional/stretch.
11.7 About: version, licenses, troubleshooting (reschedule reminders).
11.8 Every control SHALL change real behaviour, with no decorative toggles.

## R12 Motion
12.1 THE SYSTEM SHALL use one motion spec (durations 120/220/320 ms, emphasized easing) for nav transitions, sheets, view switches, task completion and list changes.
12.2 WHEN reduced motion is on THE SYSTEM SHALL replace movement with short cross-fades.
12.3 Animations SHALL NOT change measured text size or cause layout jumps.

## R13 Accessibility and performance
13.1 Touch targets ≥ 48dp. Content descriptions on all icon-only controls. Calendar cells announce date + event count.
13.2 Layouts SHALL work at 200% font scale without clipping essential text (wrapping/ellipsis allowed).
13.3 DB/network work SHALL run off the main thread. Lists use stable keys, and queries are indexed by time range.

## R14 Security and privacy
14.1 Only necessary permissions are requested: POST_NOTIFICATIONS, SCHEDULE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED, READ_CALENDAR (optional). No INTERNET.
14.2 The app holds no account, token or secret.
14.3 Logs SHALL never include event/task content. Cleartext traffic disabled, and `allowBackup` limited to preferences.

## R15 Testing and docs
15.1 Unit tests: recurrence expansion, countdown, overlap layout, overdue logic, reminder scheduling, preference/logo persistence, the phone-calendar mirror and the database migrations, DAO tests (Robolectric).
15.2 README with features (only real ones), architecture, setup, limitations, and genuine screenshots.
15.3 No commits or pushes without the owner's explicit permission.
