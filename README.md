# Lyn × Boa: Walk (Android)

**Status: v0.1 source-code preview; pending first APK build and on-device sensor test.**

A native Android pedometer and GPS walking journal with a cold, character-themed interface. This is NOT an HTML wrapper or a simulated step counter. The app is written in Kotlin using Jetpack Compose, SensorManager, SQLite, Fused Location Provider, WorkManager, and MapLibre. User-provided Lyn and Boa art is bundled in the app. Android 10+ (API 29+).

## Features in this version

- Boa: daily step count sourced from **hardware `TYPE_STEP_COUNTER`** (no GPS-based step guesses), 7k milestone, daily goal +100 per day to 12k, calories (estimated), step-based distance, water log and reminders.
- Lyn: start/end GPS walk, point validation (35 m accuracy cutoff, speed/jump filter), durable on-device route track and route length, purple line and map.
- Map starts centered on **Jalalpur Bhattian, Punjab** (32.06344, 73.37776). An in-app button fetches a **local offline map** (approx 32.035–32.090 N, 73.335–73.420 E, zoom 12–15) the first time you have internet. It is NOT prepackaged in the APK. This uses MapLibre + OpenFreeMap, subject to its 3000-tile/50 MB/4-request offline limit.
- Android persistent health foreground service for sensor reads; optional location foreground service during recorded walk. Local SQLite history; no account/server, no GPS track upload. Online map tiles require network requests to OpenFreeMap.
- Distinct character palette and bundled images, frosted panels with glass-like highlights, reserved praise and cold encouragement.
- Automated GitHub Actions APK build plus optional **stable-signed release** for updates without losing data.

## Build APK using GitHub (no Android Studio required)

1. Create an **empty private GitHub repository** (for example, `lyn-boa-walk`).
2. Upload the **contents of this folder**, including the hidden `.github/workflows/build-apk.yml`. Upload the files into the root of the repository, *not* under a second folder.
3. Open **Actions → Build installable Android APK**. The build runs on push to `main`/`master`; alternatively click **Run workflow**.
4. Open the completed workflow → **Artifacts → LynBoaWalk-debug-apk**. Unzip artifact and install `app-debug.apk` on your phone (Android will ask you to allow installs from this source).
5. Open Boa, grant **Activity Recognition** and notification permissions. Tap **Enable real step tracking**. Walk 100 steps with phone in pocket, compare against a trusted step app. Open Lyn, grant **Precise location**, tap **Start walk** and test a short loop. For offline map, press **Download Jalalpur map** while on Wi-Fi.

**Important:** Debug builds may be signed with different ephemeral keys on different GitHub runners. For **reliable upgrades without uninstalling**, use the stable-signed release below from the first install.

## Recommended: Stable signed releases (data-preserving iterations)

Once, on a computer that has Java's `keytool`:

```bash
keytool -genkeypair -v -keystore lynboa-release.jks -alias lynboa -keyalg RSA -keysize 3072 -validity 10000
```

Set the password securely. Create four **GitHub Actions secrets** (repo → Settings → Secrets and variables → Actions):

- `RELEASE_KEYSTORE_BASE64`: Base64 encoding of the `.jks` file. On Windows PowerShell use `[Convert]::ToBase64String([IO.File]::ReadAllBytes("lynboa-release.jks"))`; on Linux `base64 -w0 lynboa-release.jks`.
- `RELEASE_KEYSTORE_PASSWORD`: the keystore password.
- `RELEASE_KEY_ALIAS`: `lynboa`.
- `RELEASE_KEY_PASSWORD`: the key password (often same).

Trigger Actions again and download **LynBoaWalk-signed-release-apk** → `app-release.apk`. Keep that exact `.jks` file and its passwords backed up privately. **Install signed release initially and keep using signed release builds**; do not switch between debug/release signatures. Never commit `.jks` or keys to Git. GitHub Actions artifacts expire, but the signing secret lets new APKs install as updates over the same application ID, preserving the local history.

## Accuracy: what is real, and what is estimated?

### Steps

Android's `TYPE_STEP_COUNTER` reports cumulative real hardware steps since boot while the sensor is active. The tracker persists each raw sensor checkpoint and that day's step change **atomically in one SQLite transaction**. It rejects sensor/reboot resets and won't invent a number when first installed. After a force-stop or certain vendor battery killers, the tracker must be reopened. On reboot, open the app to restart tracking; any steps before the first post-boot sensor observation cannot be reconstructed from this sensor alone. Some phones lack the hardware counter; the app reports that instead of faking values. Around midnight, delayed/batched sensor reports may be attributed to the day the callback arrives. This is why real-device comparison is mandatory.

### GPS

GPS is recorded **only** between Lyn's Start/End walk. Fixes with accuracy worse than 35 m, backward timestamps, or implausible speed are discarded. Map route distance is GPS-derived; Boa's step-based distance is estimated using adjustable stride. They are different metrics by design. Highly inaccurate locations and stretches with no signal may leave gaps; the app never connects a route with fabricated positions.

### Calories

Calories are **estimated active walking kcal** from step distance × body weight × 0.65. Adjust weight and stride in Boa. They are **not** measured calories and are not a promise of weight loss. The daily step goal caps at 12,000 to avoid an indefinitely growing demand.

### Hydration notifications

WorkManager schedules **approximately** every two hours, from 9 a.m. to 10:59 p.m., and stops nagging at 2,000 ml logged. Android may defer background jobs. This is not an exact alarm and does not infer real fluid intake; use Boa's +250 ml button to log actual water.

## Project layout

```text
app/src/main/java/com/lynboa/walk/
  MainActivity.kt              permission orchestration, service entry
  WalkApp.kt                   app wiring, map HTTP identification, reminders
  domain/FitnessMath.kt       pure step, goal, calories, GPS validation rules
  data/FitnessDatabase.kt     versioned SQLite + transactional raw step checkpoints
  data/FitnessRepository.kt   observable data boundary and settings
  tracking/TrackingService.kt health FGS, sensor, optional GPS FGS
  maps/LynMap.kt              MapLibre map / overlay
  maps/OfflineCityMap.kt      offline city tiles + provider limits
  notifications/*             hydration work, rewards, service notification
  ui/LynBoaScreen.kt          pink Boa / purple Lyn Compose UI
app/src/test/...              JVM unit tests for key math rules
.github/workflows/build-apk.yml
```

## Roadmap after device verification

**P0:** confirm accurate on-device counts across screen-off, app swipe, reboot, midnight and walking while GPS is off. Test GPS in dense streets of Jalalpur; check network and offline map. Fix any phone-specific background battery restriction.

**P1:** optional Google Health Connect / mobile Recording API import as a recovery source, with a *single source-of-truth and deduplication* (never simply add Health Connect steps to hardware steps). Calibrate stride on a measured walk. Add GPX export, route history and pause/resume.

**P2:** spoken dialogue with locally cached audio, branching affection mechanics, more artwork/expressions, charts, wearable integration, configurable notification styles.

**P3:** shared stable Gradle conventions, dependency injection, instrumented tests on a real Android device, opt-in encrypted backups, CI release signing, database migrations for additional features.

## Map and image notices

Map data © OpenStreetMap contributors, displayed with OpenFreeMap; see [OpenFreeMap terms](https://openfreemap.org/tos/) and [OSM attribution](https://www.openstreetmap.org/copyright). User-supplied character images are included for private use; don't republish art without appropriate rights. Map tiles require a network connection until the user completes the local offline download. Route location samples stay on-device. The map provider can see tile requests for areas viewed on the map, so it may infer approximate viewing locations; GPS history is not intentionally uploaded as a file or route. `android:allowBackup=false` deliberately avoids surprise cloud backups; backup/export can be designed as an explicit opt-in later.
