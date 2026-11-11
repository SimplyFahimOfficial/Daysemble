# Contributing to Daysemble

Thanks for your interest. Daysemble is maintained by one developer, so small, focused changes are the
easiest to review.

## Before you start

- **Bugs:** open an issue with the bug report template. Include the app version (Settings → About),
  your device and Android version, and steps to reproduce.
- **Features:** open an issue first so we can agree on the scope before you write code. The ranked
  roadmap is in [docs/audit/AUDIT.md](docs/audit/AUDIT.md#c-roadmap-ranked).
- **Security problems:** don't open a public issue. Follow [SECURITY.md](SECURITY.md).

## Development setup

You need JDK 17 or newer and the Android SDK with platform 37. Then:

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

No account or API key is needed. Debug builds have *Load sample data* under Settings → About.

## Making a change

1. Branch from `main` and open a pull request into `main`.
2. Keep the change to one topic, and follow the existing code style (Kotlin official style, enforced
   by detekt with `detekt.yml`).
3. Add or update tests for behaviour you change. Tests are JVM and Robolectric tests under
   `app/src/test`; they don't need a device.
4. If you change the Room schema, bump the database version, add a migration in
   `data/db/Migrations.kt`, commit the exported schema in `app/schemas/`, and extend `MigrationTest`.
5. Update the README or `docs/` when you change behaviour they describe. Only add screenshots that
   are genuine captures of the app (`scripts/capture_screenshots.sh`).
6. Write commit messages as a short summary of what changed, for example
   `Backup import: on-device references only, valid dates, off the main thread`.

## What CI checks

Every pull request runs the debug and R8 release builds, unit tests, lint (security checks are
errors), detekt (new findings only), an audit of the release APK, an emulator launch, secret
scanning, CodeQL, dependency vulnerability and licence checks, and an audit of the workflows. Details
and the commands to run them locally are in [docs/CI_CD.md](docs/CI_CD.md).

A new dependency must have an allowed licence (`scripts/ci/deps_check.py`) and must not add a
permission outside the reviewed list in [docs/SECURITY.md](docs/SECURITY.md#permissions).

Never commit keystores, passwords, tokens or `google-services.json`.

## Licence

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE), the same licence as the project.
