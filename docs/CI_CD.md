# CI/CD

All automation runs on GitHub Actions. Every workflow starts with a read-only `GITHUB_TOKEN`
(`permissions: contents: read`) and grants more per job only where a job needs it. Every action is
pinned to a full commit SHA, with the version in a comment; Dependabot keeps them current. Command-line
tools (detekt, gitleaks, OSV-Scanner, actionlint, zizmor) are downloaded at a pinned version and
checked against a pinned SHA-256 by `scripts/ci/fetch-tool.sh` before they run.

Signing secrets exist only in the `release` environment and are used only by `ci.yml`'s
`signed-release` job (pushes to main) and `release.yml`'s `build` job (version tags). No workflow
that runs for pull requests can read them. Fork pull requests get a read-only token and no secrets.

## Workflows

| Workflow | Trigger | Purpose | Required for merge / release |
| --- | --- | --- | --- |
| `pr-validation.yml` | pull request to `main`, manual | Calls `android-build.yml`: debug build, unit tests, lint, R8 release build, APK audit, 16 KB alignment, detekt (new findings), emulator launch of the release APK | Merge: `Android / Build, unit tests, lint, APK audit`, `Android / Detekt (new findings)`, `Android / Release APK smoke test (emulator)` |
| `ci.yml` | push to `main`, manual | Same build on the merged commit; optional signed release check | Release: a successful `ci.yml` run on the tagged commit is required by `release.yml` |
| `android-build.yml` | `workflow_call` only | The shared build (see above) | Through its callers |
| `security.yml` | pull request, push to `main`, Mondays 04:17 UTC, manual | gitleaks secret scan (PR commits, or full history); on main and weekly: OSV vulnerabilities (high and critical fail) and licences of the shipped dependencies | Merge: `Secret scan (gitleaks)` |
| `dependency-review.yml` | pull request to `main` | SBOM of base and head; shipped dependencies the PR adds or changes must have no high/critical vulnerability and an allowed licence. GitHub's dependency-review-action too, when enabled | Merge: `Shipped dependency changes (OSV, licences)` |
| `codeql.yml` | pull request, push to `main`, Mondays 04:41 UTC, manual | CodeQL `security-extended` for Kotlin/Java; high and critical findings fail | Merge: `Analyze (java-kotlin)` |
| `workflow-audit.yml` | pull request, push to `main`, manual | actionlint and zizmor (medium and above fail) on workflows and `dependabot.yml` | Merge: `actionlint and zizmor` |
| `release.yml` | push of a `v*` tag | Validate tag and version, signed build, certificate check, APK audit, emulator smoke test, GitHub release with checksums and SBOM | Produces the release |

`android-build.yml` runs as a job named `Android` in its callers, so its checks appear as
`Android / <job name>` in pull requests.

None of the merge-gating workflows uses path filters. A required check that is skipped because of a
path filter never reports, and the pull request then waits for it forever.

### What each check blocks on

| Check | Fails when |
| --- | --- |
| Gradle build, unit tests | compile error or failing test |
| Android Lint | any error. Security checks (exported components, PendingIntent flags, TLS trust managers and hostname verifiers, WebView JavaScript, insecure network config, `SecureRandom`, weak crypto `getInstance`, world-readable files, debuggable manifest) are promoted to errors in `app/build.gradle.kts`. Other lint findings stay warnings |
| detekt | a finding not in `config/detekt/baseline.xml` |
| APK audit (`scripts/ci/apk_audit.py`) | debuggable or testOnly APK; a permission not in the reviewed list; an exported component that is neither a launcher entry nor permission-protected; cleartext traffic or user CAs allowed; backups without explicit rules |
| `zipalign -c -P 16 4` | native libraries not 16 KB page aligned |
| Emulator smoke | the R8 release APK crashes or is not running 20 s after launch (API 35 emulator) |
| gitleaks | any finding |
| OSV (shipped dependencies) | a vulnerability with CVSS 7.0 or more. On pull requests only for dependencies the PR adds or changes |
| Licences | a shipped dependency whose licence is not allowed or reviewed |
| CodeQL | a result with security severity 7.0 or more (or an `error`-level problem) |
| actionlint | any error |
| zizmor | a finding of medium severity or more |

Lower-severity results are listed in the job summary and do not fail the check.

## Running the checks locally

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug          # the build job, part 1
./gradlew assembleRelease :app:cyclonedxDirectBom            # part 2: R8 release + SBOM
python3 scripts/ci/apk_audit.py --apk app/build/outputs/apk/release/app-release-unsigned.apk \
  --package app.daysemble --build-tools "$ANDROID_HOME/build-tools/36.0.0"
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk

# detekt CLI 1.23.8 (download link and checksum in android-build.yml)
java -jar detekt-cli-1.23.8-all.jar --config detekt.yml --build-upon-default-config \
  --input app/src/main/java,app/src/test/java --baseline config/detekt/baseline.xml

# Shipped dependencies (OSV-Scanner reads CycloneDX files named *.cdx.json)
cp app/build/reports/sbom/daysemble-bom.json bom.cdx.json
osv-scanner scan source -L bom.cdx.json --format json > osv.json
python3 scripts/ci/deps_check.py vulns --osv osv.json
python3 scripts/ci/deps_check.py licenses --sbom bom.cdx.json

gitleaks git --redact .
actionlint && zizmor --min-severity medium .github/
```

The emulator smoke test (`scripts/ci/emulator-smoke.sh <apk> app.daysemble`) needs KVM and an
installable (signed) APK.

## Artifacts

| Artifact | From | Kept |
| --- | --- | --- |
| `reports` (lint, test reports and results) | build | 7 days |
| `daysemble-debug-apk` | build | 14 days |
| `daysemble-release-unsigned` (unsigned R8 APK, mapping, SBOM) | build | 7 days |
| `detekt-report` | detekt | 7 days |
| `emulator-smoke` (logcat, screenshot) | smoke | 7 days (14 for releases) |
| `gitleaks-report` (redacted) | security, only on failure | 7 days |
| `dependency-report`, `dependency-review` | security, dependency review | 30 / 14 days |
| `codeql-sarif` | CodeQL | 14 days |
| `release-files` | release | 30 days (the GitHub release keeps them permanently) |

Which APK is which:

- `daysemble-debug-apk` (`app-debug.apk`) is signed with the runner's throwaway debug key. You can
  install it for testing, but it is not a release and cannot update a release install.
- The unsigned R8 release APK cannot be installed as is. The smoke test signs a copy with a key
  generated inside the job and discards both afterwards.
- Signed release APKs come only from `release.yml`, attached to a GitHub release. `ci.yml`'s
  `signed-release` job verifies a signed build on main but does not upload it.

## Caching

`gradle/actions/setup-gradle` uses the MIT-licensed `basic` cache provider. Only runs on `main`
write the cache; pull requests read it. Jobs that hold secrets (`signed-release`) only read it.
`release.yml` uses no cache at all, so nothing a pull request could influence ends up in a release.

## Dependabot

`.github/dependabot.yml` opens weekly grouped version updates for Gradle and GitHub Actions, with a
cooldown (7 days, 14 for major versions) so brand-new releases are not adopted the day they appear.
Dependabot alerts and security updates are switched on in the repository settings, not in this file.
Dependabot pull requests run the same checks as any other pull request and get no secrets.

## Repository settings this relies on

Some protections are repository settings rather than files. They are listed with exact steps in
[RELEASE.md](RELEASE.md#repository-settings) and [SECURITY.md](SECURITY.md#github-settings).
The required status checks in particular must be set by hand: the old `build` check
from `android.yml` (workflow "Android CI") no longer exists, and if it is still required, pull requests will wait for it.
