# Notes for Claude

- Owner rides a Wattbike Proton and asks for workouts in plain language ("make me a workout like this").
  Add them to `workouts/workouts.json` (format in README.md), with a unique kebab-case `id`.
  Run `gradle -PcoreOnly :core:test --offline` (drop `--offline` the first time) before committing:
  the tests parse the whole library and check ids are unique.
- Workout-only commits don't trigger an app build; the owner taps Sync in the app to get them.
- The Android SDK can't be downloaded in the cloud sandbox (dl.google.com is blocked), so the app module
  only compiles in GitHub Actions. The Actions API is also blocked here, so the workflow pushes its log to the
  `ci-logs` branch: `git fetch origin ci-logs && git show origin/ci-logs:status.txt` and `...:latest.log`.
- Maven Central sometimes returns 429 through the sandbox proxy; retry Gradle with `--max-workers=1`.
- The release signing key is in the GitHub secret `APP_SIGNING_KEY` (line 1: password, line 2: base64 PKCS12, alias `wattbike`). Never commit it.
