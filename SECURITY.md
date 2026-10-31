# Security Policy

## Supported versions

Daysemble is an Android application. Security fixes are applied to the latest
release only; older builds are not maintained.

| Version | Supported |
| --- | --- |
| Latest release | Yes |
| Older releases | No |

## Reporting a vulnerability

Please **do not** open a public issue for security problems.

- Preferred: use GitHub's private vulnerability reporting via the repository
  **Security** tab -> **Report a vulnerability**
  (the maintainer enables this under *Settings -> Code security*).
- If private reporting is unavailable, open a regular issue asking for a private
  contact channel **without** including any vulnerability details.

Please include, where possible:

- the affected app version and device / Android version,
- clear steps to reproduce,
- the impact and the affected component.

Do not include real personal data or credentials in a report.

## What to expect

- Acknowledgement of your report within 7 days.
- An assessment and a planned fix window within 30 days.
- Credit in the release notes once a fix ships, unless you prefer to remain
  anonymous.

## Security practices

How the app and its build are secured, what CI enforces, and the reviewed exceptions are described
in [docs/SECURITY.md](docs/SECURITY.md). Release signing and verification are in
[docs/RELEASE.md](docs/RELEASE.md).
