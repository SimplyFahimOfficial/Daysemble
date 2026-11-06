# Store distribution

## Google Play

The signed App Bundle is produced by `.github/workflows/release.yml` and attached to every stable
GitHub release as `daysemble-<version>.aab`. Use `.github/workflows/play-publish.yml` to upload
that existing AAB to the Play Console.

Required one-time configuration:

- Create the Play Console app with package name `app.daysemble` and complete its initial setup.
- Enable the Google Play Android Developer API in Google Cloud.
- Configure Workload Identity Federation for this repository and restrict it to the GitHub
  `play-store` environment.
- Create the Google service account with only this app's release-upload permissions and add it to
  Play Console API access.
- Create the GitHub `play-store` environment, restrict it to the default branch, enable required
  reviewers, and set the environment variables `PLAY_WIF_PROVIDER` and `PLAY_SERVICE_ACCOUNT`.
- Publish the GitHub release, then dispatch **Publish to Google Play** with its stable tag. A
  production upload is deliberately created as a draft for manual review in Play Console.

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
Before the first upload, verify in Play Console **App integrity** that the **App signing key certificate SHA-256** matches `RELEASE_CERT_SHA256`. The upload-key fingerprint alone is not sufficient. If the app-signing fingerprint differs, stop: GitHub-installed users may not be able to update through Play.

## F-Droid status

Daysemble cannot be listed in the official public F-Droid repository while its source repository is
private. It also depends on proprietary Google Play Services authentication. A public source
release under a FOSS-compatible licence and a FOSS authentication alternative/build variant are
prerequisites; no F-Droid upload workflow can bypass those requirements.
