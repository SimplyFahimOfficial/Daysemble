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

## F-Droid status

Daysemble cannot be listed in the official public F-Droid repository while its source repository is
private. It also depends on proprietary Google Play Services authentication. A public source
release under a FOSS-compatible licence and a FOSS authentication alternative/build variant are
prerequisites; no F-Droid upload workflow can bypass those requirements.
