# Releasing Daysemble

Releases are built, signed and published by `.github/workflows/release.yml` when a version tag is
pushed. Nobody builds or signs release APKs on a laptop for distribution.

## Versioning

- Tags are `vMAJOR.MINOR.PATCH` (semantic versioning), for example `v1.2.0`. A suffix makes a
  pre-release: `v1.2.0-rc.1`, `v1.2.0-beta.2`.
- `versionName` in `app/build.gradle.kts` must equal the tag without the `v`.
- `versionCode` must be greater than the previous release's. Android refuses to update an
  installed app to an equal or lower code. Increase it by one for every release, pre-releases included.

## Cutting a release

1. In a pull request, set `versionName` and `versionCode` in `app/build.gradle.kts`. Merge it once
   all checks pass.
2. Wait for `CI` on `main` to pass for the merge commit.
3. Tag that commit and push the tag:

   ```bash
   git switch main && git pull
   git tag -a v0.2.0 -m "Daysemble 0.2.0"
   git push origin v0.2.0
   ```

4. Approve the `release` environment deployment when GitHub asks (if reviewers are configured).
5. The workflow publishes a GitHub release with generated notes and these files:
   - `daysemble-<version>.apk`: the signed universal APK for direct installation
   - `daysemble-<version>.aab`: the signed Android App Bundle for Google Play
   - `daysemble-<version>-sbom.cdx.json`: CycloneDX SBOM of the libraries in the APK and bundle
   - `daysemble-<version>-mapping.txt`: R8 mapping, to read obfuscated stack traces
   - `SHA256SUMS`

If a step fails, nothing is published. Fix the problem on `main`, then either move the tag to the
fixed commit (`git tag -d v0.2.0 && git push origin :refs/tags/v0.2.0`, then tag again) or, if the
version was already distributed, release the next version instead.

## What the workflow checks

| Job | Checks |
| --- | --- |
| validate | the tag is semver; its commit is on `main`; `versionName` equals the tag; `versionCode` is greater than the previous `v*` tag's; `ci.yml` succeeded on the commit |
| build (`release` environment) | all signing secrets and `RELEASE_CERT_SHA256` are set; wrapper JAR is official; clean build without any cache; the APK is signed (v2/v3 scheme) by exactly one signer whose certificate SHA-256 equals `RELEASE_CERT_SHA256`; 16 KB alignment; APK audit with the expected version |
| smoke | checksums match; the signed APK installs and launches on an API 35 emulator without crashing |
| publish | checksums match again; creates the release (`--verify-tag`, pre-release when the tag has a suffix) |
| attest | public repositories only: signed build provenance for the APK and SBOM |

The release contains both a universal APK and a signed Android App Bundle. The AAB is the artifact
used by the manual Google Play publishing workflow. Configure Play App Signing in Play Console and
use the release workflow's certificate as the upload key when setting up the app.

## Signing key

Create the key once, keep it outside the repository, and back it up offline. Losing it means
existing installs can never be updated.

```bash
keytool -genkeypair -v -keystore daysemble-release.jks -alias daysemble \
  -keyalg RSA -keysize 4096 -validity 10000
# Certificate fingerprint for RELEASE_CERT_SHA256:
keytool -list -v -keystore daysemble-release.jks -alias daysemble | grep 'SHA256:'
```

`app/build.gradle.kts` signs the release build only when all four variables
`DAYSEMBLE_KEYSTORE_PATH`, `DAYSEMBLE_KEYSTORE_PASSWORD`, `DAYSEMBLE_KEY_ALIAS` and
`DAYSEMBLE_KEY_PASSWORD` are set. Otherwise it builds `app-release-unsigned.apk`; the workflows treat
that as a failure.

## Repository settings

Set these up once, before the first release. They are GitHub settings and cannot be set from a
pull request.

### The `release` environment (Settings → Environments → `release`)

The environment exists. As of this writing it allows deployments from branch `main` only, has no
required reviewers, and lets administrators bypass its rules. Add the tag rule (step 1) before the
first release, and add the secrets only after step 1.

1. **Deployment branches and tags:** "Selected branches and tags" with exactly one rule: tag `v*` (for `release.yml`). Release signing no longer runs on `main`; never add `refs/pull/*`.
2. **Environment secrets:**
   - `DAYSEMBLE_KEYSTORE_BASE64`: the keystore, base64 on one line (`base64 -w0 daysemble-release.jks`
     on Linux, `base64 -i daysemble-release.jks` on macOS)
   - `DAYSEMBLE_KEYSTORE_PASSWORD`, `DAYSEMBLE_KEY_ALIAS`, `DAYSEMBLE_KEY_PASSWORD`
3. **Environment variable** `RELEASE_CERT_SHA256`: the certificate SHA-256 from `keytool` (colons
   and case don't matter). A release signed with any other key fails.
4. **Required reviewers:** add yourself, so each release waits for approval. Turn off "Allow
   administrators to bypass configured protection rules".
5. Delete any signing secrets stored as repository secrets (Settings → Secrets and variables →
   Actions); they would be available to every job.

Plan note: for private repositories, environment secrets and deployment rules need GitHub Pro, Team
or Enterprise, and required reviewers need Team or Enterprise. Public repositories have them on
every plan.

### Tag protection (Settings → Rules → Rulesets → New tag ruleset)

Target tags matching `v*`. Restrict creation, updates and deletions to the maintainer (bypass list),
and block force pushes. Otherwise anyone with write access can push a release tag.

### Branch protection for `main` (Settings → Rules → Rulesets → New branch ruleset)

Target the default branch. Require a pull request before merging and these status checks
(they appear in the picker after they have run once):

- `Android / Build, unit tests, lint, APK audit`
- `Android / Detekt (new findings)`
- `Android / Release APK smoke test (emulator)`
- `Secret scan (gitleaks)`
- `Shipped dependency changes (OSV, licences)`
- `Analyze (java-kotlin)`
- `actionlint and zizmor`

Remove `build` (from the old `android.yml`) if it is still required. Block force pushes and
deletions. With a single maintainer, require 0 approvals (you cannot approve your own pull request);
add approvals once there are other reviewers.

## Verifying a downloaded release

```bash
sha256sum --check --ignore-missing SHA256SUMS
apksigner verify --print-certs daysemble-0.2.0.apk
```

The `Signer #1 certificate SHA-256 digest` must match the fingerprint in the release notes and in
every previous release. A different certificate means the APK is not from this project. Android
also refuses to install it over an existing Daysemble install.

For public repositories with attestations:

```bash
gh attestation verify daysemble-0.2.0.apk --repo Fahimofficial/Daysemble
```

## Google Play upload

The `.github/workflows/play-publish.yml` workflow uploads the signed AAB from a published stable
GitHub release. It uses GitHub OIDC / Google Workload Identity Federation; no long-lived Google
service-account JSON key is stored in GitHub.

One-time setup:

1. Create the Play Console app with package name `app.daysemble`. Complete the initial app setup
   and make the first manual upload if Play Console requires it.
2. Enable the Google Play Android Developer API in the Google Cloud project.
3. Configure a Workload Identity Pool provider for this repository, restricted to the
   `play-store` GitHub environment. Bind the service account with only the required Play Console
   app permissions and add it under Play Console API access.
4. Create a GitHub environment named `play-store`, restrict deployments to the default branch,
   and add required reviewers. Add environment variables `PLAY_WIF_PROVIDER` (the full provider
   resource name) and `PLAY_SERVICE_ACCOUNT` (the service-account email).
5. After a stable release is published, run **Actions → Publish to Google Play**, enter its tag,
   and choose the track. Production uploads remain drafts until a maintainer finishes publishing
   in Play Console.

### Narrow Workload Identity Federation trust

Configure the OIDC provider to map:

```
google.subject=assertion.sub
attribute.repository=assertion.repository
attribute.ref=assertion.ref
attribute.environment=assertion.environment
```

Restrict its attribute condition to
`assertion.repository == 'Fahimofficial/Daysemble'`,
`assertion.ref == 'refs/heads/main'`, and
`assertion.environment == 'play-store'`. Grant `roles/iam.workloadIdentityUser` to the
Google service account for a `principalSet` restricted to this repository. The
`PLAY_WIF_PROVIDER` variable is the full resource name:
`projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/POOL_ID/providers/PROVIDER_ID`.
Do not grant project-owner permissions.

### App signing compatibility

Google Play App Signing uses the app-signing key to sign APKs delivered to users; the key that
signs the uploaded AAB is the upload key. When enrolling, preserve Daysemble's existing release
certificate as the Play app-signing key if users who installed the APK from GitHub must be able
to update through Play. Configure the accepted upload certificate in Play Console and verify
the first release using internal testing before production.

## F-Droid status

Daysemble is currently in a private GitHub repository, so it cannot be submitted to the official
public F-Droid repository in this state. It no longer depends on Google Play services. Publishing the
source publicly under a FOSS-compatible licence is the remaining prerequisite. See
`docs/STORE_DISTRIBUTION.md` for the store-readiness checklist.
