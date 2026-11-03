# Proton Workouts

A personal Android app for running structured workouts on a Wattbike Proton in ERG mode,
with a "take a different route" button for when the planned workout is too much, and
uploads to Strava.

## Installing on your phone

1. On your phone, open https://github.com/Vansaska/wattbike-app/releases/latest
2. Tap `proton-workouts.apk` to download it.
3. Open the downloaded file. The first time, Android will ask whether your browser may
   install apps: allow it, then go back and tap Install.
4. To update, do the same with a newer build. Your workouts, rides and settings are kept.

## First-time setup in the app

- **Settings → FTP**: enter your FTP. All workouts are written as % of FTP.
- **Find bike**: pedal for a few seconds so the Proton wakes up, then pick it from the list.
  Close the Wattbike Hub app first so it doesn't hold control of the bike.
- **Strava** (optional): see below.

## Strava setup

1. Go to https://www.strava.com/settings/api and create an app:
   - Application Name: anything (e.g. "Proton Workouts")
   - Category: Training
   - Website: `https://github.com/Vansaska/wattbike-app`
   - Authorization Callback Domain: `localhost`
2. Strava shows a **Client ID** and **Client Secret**. Copy them into the app's
   Settings → Strava, then tap **Connect Strava** and approve. Leave the
   "Upload your activities" box ticked.

## Workouts file

`workouts/workouts.json` is the shared library. The app's **Sync** button downloads it.
Workouts edited on the phone are marked "edited" and are not overwritten by sync.

```json
{
  "id": "unique-id",
  "name": "Shown in the app",
  "description": "Optional",
  "steps": [
    { "duration": "10:00", "from": 45, "to": 75, "label": "Warm-up" },
    { "times": 5, "steps": [
      { "duration": "3:00", "power": 115 },
      { "duration": "3:00", "power": 50, "label": "Recover" }
    ]},
    { "duration": "5:00", "power": 65 }
  ]
}
```

- `power`, `from`, `to` are % of FTP.
- `duration` is `"m:ss"`, `"h:mm:ss"`, or a number of seconds.
- A step with `times` + `steps` repeats; one with `from`/`to` is a ramp; otherwise steady.
- `label` is optional; without one the zone name is shown.

## Development

- `core/` is plain Kotlin (workout model, route generator, FTMS protocol, ride engine,
  TCX writer) with unit tests: `gradle -PcoreOnly :core:test`.
- `app/` is the Android app (Jetpack Compose).
- Every push to `main` (except workout-only changes) builds a signed APK in GitHub Actions
  and publishes it as a release. The build log is pushed to the `ci-logs` branch.
