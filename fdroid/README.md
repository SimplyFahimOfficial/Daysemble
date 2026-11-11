# F-Droid readiness: Daysemble

Application ID: `app.daysemble`

## Distribution boundary

This repository keeps its existing Google Play and GitHub release variants unchanged. F-Droid must use a dedicated FOSS-only source set/flavor and its own build recipe. Never remove a Play dependency globally to make an F-Droid build compile.

## Current blockers

The app no longer uses Google Play services or any Google API. Repository/source publication and licence review are still required.

## What the workflow does

`.github/workflows/fdroid-readiness.yml` runs a fail-closed static prerequisite audit. It flags known Google Play Services/Firebase/Cast dependencies, app-local prebuilt AAR/JAR/SO files, missing root licensing and missing F-Droid metadata. It does **not** claim to build or publish an F-Droid APK while those prerequisites remain unresolved.

## Required path to official F-Droid

1. Keep the project source public and provide a valid FOSS licence covering the app and redistributable assets.
2. Create a dedicated FOSS variant with a distinct application ID if required by F-Droid's fork policy. Keep Play/GitHub application IDs and variants untouched.
3. If a proprietary SDK is ever added again, keep it out of that FOSS variant, replacing it with a FOSS alternative or disabling it cleanly with clear UI behaviour.
4. Build every native dependency from licensed source; do not consume opaque prebuilt app binaries or download executable artifacts during the build.
5. Add a pinned, tested `fdroiddata` metadata recipe with exact version name/code, source commit, Gradle task and output APK path. Validate with `fdroid lint` and a real `fdroid build` in the official buildserver.
6. Submit a merge request to `https://gitlab.com/fdroid/fdroiddata`. GitHub Actions in this app repository cannot add an app to the official F-Droid catalogue by itself.

## Important

The F-Droid signing key is separate from the Play upload key and the GitHub release key. Do not reuse or expose Play production signing secrets in F-Droid jobs. F-Droid's own build infrastructure signs official-repository APKs.
