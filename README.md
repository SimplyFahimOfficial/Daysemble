<div align="center">

<img src="docs/assets/brand/banner.png" width="520" alt="Daysemble">

**Your events, tasks and reminders on one calm timeline.**<br>
An offline-first Android planner with optional two-way sync for Google Calendar and Google Tasks.

[![Platform: Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#-download)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-blue?style=for-the-badge)](LICENSE)
[![Version 0.1.0](https://img.shields.io/badge/Release-v0.1.0_early_development-f97316?style=for-the-badge)](#-download)
[![Kotlin + Compose](https://img.shields.io/badge/Kotlin-Jetpack_Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#-tech-stack)

[Features](#-features) · [Download](#-download) · [Build](#-build-from-source) · [Privacy](#-privacy) · [Credits](#-credits) · [Contact](#-contact) · [Support](#-support-the-project)

</div>

---

## ✨ Features

- ☀️ **Today.** One timeline of events and tasks with a live countdown to what's next, overdue tasks and a free-time summary for your waking hours.
- 🗓️ **Calendar.** Month, Week, Day and Agenda views. Tap an empty slot to create an event there.
- 📱 **Optional device calendar preview.** Grant read-only calendar permission in Lists & calendars to see upcoming device events and open them in the phone’s calendar app. Previewed events are not imported or shown on Daysemble’s main timeline.
- 🌍 **Events that respect time zones.** Per-event time zones, all-day events, repeat rules (RRULE with exceptions) and edits to one occurrence or the whole series.
- ✅ **Tasks.** Due dates and times, durations, priorities, subtasks, lists, filters, repeats with skip and history, and optional live countdowns.
- 🔔 **Reliable reminders.** Exact alarms where Android allows them, re-planned after reboots and time-zone changes, with *Complete* and *Snooze* actions.
- 🔄 **Optional Google sync.** Two-way sync with Google Calendar and Google Tasks, narrow permissions, an offline queue and listed conflicts.
- 😴 **Daily schedule and sleep log.** Set your wake-up time and bedtime and log sleep by hand.
- ⚡ **Quick add and shortcuts.** Quick Add, long-press item menus with undo, search, and launcher shortcuts for *New task*, *New event* and *Search*.
- 💾 **Export and import.** Save on-device data to a JSON file and merge it back later.
- 🎨 **Appearance.** Light, Dark or System theme, reduced-motion settings and three launcher logos.

## 📸 Screenshots

There are no screenshots yet. Only real captures from [`scripts/capture_screenshots.sh`](scripts/capture_screenshots.sh) will go here. Until then, these are the three logos you can pick in the app:

<div align="center">
  <img src="app/src/main/res/drawable-nodpi/logo_classic.png" width="110" alt="Classic logo, the default">
  <img src="app/src/main/res/drawable-nodpi/logo_midnight.png" width="110" alt="Midnight logo">
  <img src="app/src/main/res/drawable-nodpi/logo_outline.png" width="110" alt="Outline logo">
  <br>
  <sub><b>Classic</b> (default) · <b>Midnight</b> · <b>Outline</b></sub>
</div>

## 📥 Download

Daysemble is in **early development (v0.1.0)** and has no published release yet. Android 8.0 (API 26) or newer is required. Signed releases will be published on [GitHub Releases](https://github.com/Fahimofficial/Daysemble/releases) from `v*` tags; see [docs/RELEASE.md](docs/RELEASE.md). Until then, build it from source.

## 🔨 Build from source

**Requirements:** JDK 17 or newer (CI uses 21) and Android SDK platform 37. The Gradle wrapper downloads Gradle 9.8.2.

```bash
git clone https://github.com/Fahimofficial/Daysemble.git
cd Daysemble

./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew installDebug         # install on a connected device or emulator
./gradlew testDebugUnitTest    # JVM and Robolectric tests
./gradlew lintDebug            # Android lint
```

- The debug build installs as `app.daysemble.debug`, next to a release build.
- **Google sync** needs a one-time Google Cloud setup ([docs/GOOGLE_SETUP.md](docs/GOOGLE_SETUP.md)). Everything else works without it.
- **Release signing** uses the `DAYSEMBLE_KEYSTORE_PATH`, `DAYSEMBLE_KEYSTORE_PASSWORD`, `DAYSEMBLE_KEY_ALIAS` and `DAYSEMBLE_KEY_PASSWORD` environment variables. Without them, `assembleRelease` builds an unsigned R8 APK.

More: [CONTRIBUTING.md](CONTRIBUTING.md), [docs/CI_CD.md](docs/CI_CD.md) and the audit and roadmap in [docs/audit/AUDIT.md](docs/audit/AUDIT.md).

## 🧰 Tech stack

| Area | Technology |
|---|---|
| Language | Kotlin 2.4 (JVM 17), Coroutines and `StateFlow` |
| UI | Jetpack Compose, Material 3, Navigation Compose with typed routes |
| Data | Room, DataStore, kotlinx.serialization |
| Background | WorkManager for sync, `AlarmManager` for reminders |
| Sync | OkHttp against the Google Calendar v3 and Tasks v1 REST APIs, Google Identity Services |
| DI | Manual (`AppContainer`) |
| Other | ZXing (on-device UPI QR) |
| Build | Gradle 9.8, AGP 9.5, KSP, CycloneDX SBOM, detekt |

## 🔐 Privacy

- Data lives on your device. There are no analytics, ads, crash reporting or Daysemble servers.
- Network traffic goes only to Google's APIs over HTTPS, and only after you connect an account.
- OAuth tokens stay in memory and are never written to disk or logs. Disconnecting never deletes anything from Google.
- Permissions and their reasons are listed in [docs/SECURITY.md](docs/SECURITY.md#permissions). To report a vulnerability, see [SECURITY.md](SECURITY.md).

## 🙏 Credits

Daysemble is built with [Android Jetpack](https://developer.android.com/jetpack), [Kotlin](https://kotlinlang.org/) and [kotlinx](https://github.com/Kotlin), [OkHttp](https://github.com/square/okhttp), [Google Play services auth](https://developers.google.com/android/guides/setup), [ZXing](https://github.com/zxing/zxing) and [Material Symbols](https://github.com/google/material-design-icons). Tests use JUnit 4, [Robolectric](https://github.com/robolectric/robolectric), [Truth](https://github.com/google/truth), [Turbine](https://github.com/cashapp/turbine) and MockWebServer. Each project keeps its own license.

Google Calendar and Google Tasks are trademarks of Google LLC. Daysemble is an independent project and is not affiliated with or endorsed by Google. The logos and wordmark were made for this project; see [brand/README.md](brand/README.md).

## 📄 License

Daysemble is licensed under the [Apache License 2.0](LICENSE).

## 📬 Contact

<div align="center">

<a href="https://instagram.com/SimplyFahimofficial"><img src="https://img.shields.io/badge/Instagram-@SimplyFahimofficial-E4405F?style=for-the-badge&logo=instagram&logoColor=white" alt="Instagram: @SimplyFahimofficial"></a>
<a href="mailto:simplyfahimofficial@gmail.com"><img src="https://img.shields.io/badge/Email-simplyfahimofficial@gmail.com-EA4335?style=for-the-badge&logo=gmail&logoColor=white" alt="Email: simplyfahimofficial@gmail.com"></a>
<br>
<a href="https://SimplyFahim.pages.dev"><img src="https://img.shields.io/badge/Website-SimplyFahim.pages.dev-111827?style=for-the-badge&logo=googlechrome&logoColor=white" alt="Website: SimplyFahim.pages.dev"></a>
<a href="https://github.com/Fahimofficial"><img src="https://img.shields.io/badge/GitHub-Fahimofficial-24292F?style=for-the-badge&logo=github&logoColor=white" alt="GitHub: Fahimofficial"></a>

</div>

---

<div align="center">

## 💚 Support the Project

Daysemble is free and built independently. If it helps you plan your days, a small donation keeps development going.

<a href="https://simplyfahim.pages.dev/pay/"><img src="docs/assets/support/upi-qr.png" width="220" alt="UPI QR code to pay Md Fahim, UPI ID SimplyFahim@sbi. Tap to open UPI payment options."></a>

### 👆 **Tap the QR to open UPI payment options**

<table>
<tr>
<td align="center">

**Payee:** Md Fahim<br>
**UPI ID**

```
SimplyFahim@sbi
```

</td>
</tr>
</table>

<a href="https://simplyfahim.pages.dev/pay/"><img src="https://img.shields.io/badge/Pay_with-UPI-009E3B?style=for-the-badge&logo=googlepay&logoColor=white" height="44" alt="Pay with UPI"></a>
&nbsp;
<a href="https://ko-fi.com/simplyfahim"><img src="https://img.shields.io/badge/Support_me_on-Ko--fi-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white" height="44" alt="Support me on Ko-fi"></a>

<sub>You choose the amount. Check that your UPI app shows <b>Md Fahim</b> before you pay.</sub>

</div>
