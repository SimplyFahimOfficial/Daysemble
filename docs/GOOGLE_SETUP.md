# Google Calendar & Google Tasks setup

Daysemble talks to Google directly from the device. It uses **Google Identity Services
`AuthorizationClient`** for consent and short-lived access tokens. The app has no backend
and no client secret, and it never stores tokens.

Google identifies the app by its **package name + signing-certificate SHA-1**. Until both are
registered in your Google Cloud project, *Connect Google account* shows
**"Google sign-in isn't set up for this build"**. The app doesn't pretend to be connected.

## 1. Create a project and enable the APIs
1. Open [Google Cloud Console](https://console.cloud.google.com/) and create (or pick) a project.
2. Go to **APIs & Services → Library** and enable:
   - **Google Calendar API**
   - **Google Tasks API**

## 2. Configure the OAuth consent screen (Google Auth Platform)
1. Go to **Google Auth Platform → Branding**. Set the app name *Daysemble*, a support email and a developer contact.
2. Under **Audience**, choose *External*. While the app is in **Testing**, add every Google
   account that will sign in as a **test user** (up to 100).
3. Under **Data access**, add these scopes:
   - `https://www.googleapis.com/auth/calendar` (read/write events and calendars)
   - `https://www.googleapis.com/auth/tasks` (read/write tasks)
   - `email`

   Calendar and Tasks are *sensitive* scopes. Publishing to production requires Google's
   verification. In Testing mode, consent expires after 7 days, so test users may be asked to
   reconnect (Daysemble shows *Sign in needed* and keeps unsynced changes).

## 3. Create Android OAuth clients
Create one **OAuth client ID** of type **Android** per build you want to sign in with.

| Build | Package name | Certificate |
|---|---|---|
| Debug (`./gradlew assembleDebug`) | `app.daysemble.debug` | your debug keystore |
| Release | `app.daysemble` | your release/upload key (and the **Play App Signing** key if distributed via Play) |

Get the SHA-1:

```bash
# Debug keystore (created by Android Studio / the Android Gradle plugin)
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey \
        -storepass android -keypass android | grep SHA1

# Or, from the project:
./gradlew signingReport
```

You don't need to put a client ID in the code or ship a `google-services.json`. Play services
matches the installed app by package + SHA-1.

## 4. Try it
1. Install the build on a device or emulator **with Google Play services**.
2. Go to **Settings → Google Calendar & Tasks → Connect Google account**, pick a test-user account and approve.
3. The first sync runs right away. Choose which calendars and task lists to sync on the same screen.

## What syncs, and how
- **Calendar:** two-way. The app does an incremental sync with `syncToken`, and a full resync on HTTP 410.
  Recurring series are stored as masters plus exceptions and expanded on device. Creates use a
  client-supplied id, so a retried request can't make a duplicate.
- **Tasks:** two-way. The app does an incremental sync with `updatedMin` (5-minute overlap). Subtasks map to `parent`.
  Google Tasks has no priority or due time, so these stay on the device. The due *date* syncs.
- **Offline:** edits are queued and sent later. Retries use exponential backoff (WorkManager).
- **Conflicts:** when an item was edited in both places, the most recent edit wins. The other version is
  listed under *Sync conflicts*. A remote deletion never discards unsynced local edits.
- **Disconnect:** revokes access and removes the Google copies from the device. Nothing is deleted from Google.

## Known limitations
- You can't edit a *single occurrence* of a repeating Google event yet. You can edit the whole series
  or delete one occurrence (EXDATE).
- Repeat rules the app can't expand (e.g. `BYSETPOS`) are displayed from Google's own instance list (−30 days to +1 year) and are read-only in Daysemble.
- Attendees, conferencing and attachments aren't shown. They're preserved because the app only
  PATCHes the fields it manages.
- Per-event colours (`colorId`) are shown, mapped to the nearest Daysemble swatch. Changing an event's colour isn't supported yet.
