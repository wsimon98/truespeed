# TrueSpeed

A clean, **GPS speedometer** for Android with a sci-fi HUD look. Big readable
seven-segment numbers, a glowing heading-up compass ring, black background —
built for motorcycle/scooter use. No ads, no accounts, no analytics, no
trackers, and **no Google Play Services**; it uses Android's built-in location
APIs so it runs on old phones (Galaxy S7 and older).

## Features

- Large centered seven-segment speed number (MPH default; long-press to switch
  MPH/KPH, remembered).
- Glowing dual-ring compass with fine radar ticks and N/S/E/W, drawn heading-up
  from **GPS course-over-ground** (with an optional, clearly-labelled
  magnetometer fallback).
- Speed comes from GPS only (never faked from the accelerometer); smoothed, with
  a stop timeout so it reads 0 when you are stopped, and a weak-GPS indicator.
- HUD stat panels: trip distance, max speed, and moving-average speed (the
  average excludes long stops).
- GPS status line: locked / waiting, estimated accuracy (ft or m), and the
  active heading source.
- Swipe left/right to recolor everything (white/red/blue/yellow/green/orange/
  purple); the background always stays black.
- Movable floating speedometer bubble over other apps. **Pinch** to resize it,
  **drag** to move it, **tap** to open the app, **hold** to close it. It only
  shows while you are in another app (it hides while TrueSpeed is open).
- Portrait and landscape layouts: in landscape the compass stays full-size and
  the controls sit in the side wings so they never crowd the gauge.
- Transparent, opt-in OTA update check. It never installs silently.

## How to use

1. Open TrueSpeed and allow **Location** when asked. Go outside with a clear
   view of the sky for the first GPS lock (it can take a minute on a cold start).
2. **Read your speed** in the center; the unit (MPH/KPH) is shown below it.
3. **Compass**: the ring rotates so your direction of travel is at the top once
   you are moving fast enough for GPS to report a reliable course. While
   stopped or with a weak fix it dims and shows "waiting for movement".
4. **Long-press** the screen to open the menu:
   - Switch units (MPH / KPH)
   - Toggle the magnetic-heading fallback (uses the phone's compass when GPS
     course isn't available; clearly labelled when active)
   - Show/hide the floating bubble
   - Check for updates
   - About
5. **Swipe** left or right anywhere on the gauge to change the display color.
6. **Trip stats**: TRIP / MAX / AVG panels update as you ride. Tap **RESET TRIP**
   to clear them (it's saved until you reset).
7. **Floating bubble** (`BUBBLE` button or the menu): a small speed readout that
   floats over other apps. Requires the overlay permission (the app prompts you
   and opens the right settings screen). Once shown: drag to move, pinch with two
   fingers to resize, tap to jump back into TrueSpeed, and press-and-hold to
   close it. It automatically hides whenever TrueSpeed is in the foreground.

The app works fully offline; the only networked feature is the optional update
check.

## Build

Toolchain: JDK 17+, Android SDK with `platforms;android-34` and
`build-tools;34.0.0`. Gradle is provided via the wrapper.

```sh
cd TrueSpeed
./gradlew assembleRelease      # signed if keystore.properties exists
# or, for quick testing:
./gradlew assembleDebug
```

APK output:
- `app/build/outputs/apk/release/app-release.apk`
- `app/build/outputs/apk/debug/app-debug.apk`

Optional release signing: create a `keystore.properties` at the repo root
(gitignored) with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Reuse
the same keystore for every release so OTA updates install over the existing
app. Without it, the project still builds with the local debug key.

There's a JVM smoke test that inflates the main screen with Robolectric (no
device needed), handy for catching layout/launch regressions:

```sh
./gradlew testReleaseUnitTest
```

### OTA updates (`update.json`)

The app can check a small JSON manifest and, on your confirmation, download and
hand the APK to the system installer. Host this at the URL in
`UpdateChecker.UPDATE_URL`:

```json
{
  "versionCode": 2,
  "versionName": "1.1",
  "apkUrl": "https://example.com/TrueSpeed/TrueSpeed.apk",
  "notes": "What changed.",
  "minAndroidSdk": 21
}
```

For each release, bump `versionCode`/`versionName` in `app/build.gradle.kts`
**and** in `update.json`, rebuild, and replace the hosted APK. A static download
page lives in `web/` (`index.html` + `update.json`).

## Minimum Android version & fallbacks

- **minSdk 21 (Android 5.0).** Installs on the Galaxy S7 and older.
- Floating bubble uses `TYPE_APPLICATION_OVERLAY` on Android 8+ and `TYPE_PHONE`
  below it — both work.
- Runtime permissions (location, install-unknown, notifications) only apply on
  the API levels that require them; older devices grant at install time.

## Permissions (and why)

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS speed & course. Core function. |
| `ACCESS_COARSE_LOCATION` | Fallback when the user grants only approximate location. |
| `INTERNET` | Only to fetch `update.json` and download an update APK on request. |
| `ACCESS_NETWORK_STATE` | Skip update attempts cleanly when offline. |
| `SYSTEM_ALERT_WINDOW` | Floating speedometer bubble. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_LOCATION` | Keep the bubble + its GPS alive in the background (typed FGS required on Android 14). |
| `REQUEST_INSTALL_PACKAGES` | Launch the system installer for an approved OTA update. |
| `POST_NOTIFICATIONS` | Show the bubble's foreground-service notification on Android 13+. |

## Credits & third-party

- **Seven-segment font:** [DSEG](https://github.com/keshikan/DSEG) by keshikan,
  used for the speed and stat digits. Licensed under the SIL Open Font License
  1.1; the full license is in [`licenses/DSEG-OFL.txt`](licenses/DSEG-OFL.txt).
- **Compass behavior reference:** [Kr0oked/Compass](https://github.com/Kr0oked/Compass)
  was used only as a behavioral reference (how a sensible compass should gate and
  smooth its heading) — TrueSpeed's compass code was written fresh, not copied.
- **Inspiration:** the idea of a dependency-light, Play-Services-free GPS
  speedometer was informed by open projects like
  [bluesquarespeedometer](https://github.com/nhirokinet/bluesquarespeedometer)
  and [velociraptor](https://github.com/cyb3rko/velociraptor-v2). No code was
  taken from them; TrueSpeed is an independent implementation.

## License

GPL-3.0. See [`LICENSE`](LICENSE). The bundled DSEG font is separately licensed
under the SIL OFL 1.1 (see above).
