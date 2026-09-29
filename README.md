# TV Dash

A dashboard and app launcher for Android TV, built with Kotlin and Jetpack Compose for TV.

## Features

- Dashboard cards for the time and date, network status, and free storage. Each card opens the matching system settings screen.
- Grid of every installed app, using its TV banner or icon. Select an app to launch it.
- D-pad friendly focus handling.
- Can be set as the default Home screen.

## Get the APK

The APK is built by GitHub Actions, so Android Studio isn't needed.

1. Open the Actions tab and wait for the "Build APK" run to finish (about 5 minutes).
2. Download `TvDash.apk` from the Releases page (tag `latest`).
3. Install it on the TV with the Downloader app, or run `adb install TvDash.apk`.

Installing from outside the Play Store requires allowing unknown sources for the installer app.

## Project layout

- `app/src/main/java/com/example/tvdash/`: `MainActivity.kt` (screen), `Components.kt` (cards), `Data.kt` (app list and status)
- `app/src/main/AndroidManifest.xml`: TV and Home launcher declarations
- `.github/workflows/build.yml`: cloud build that publishes the APK

## Build locally

Open the folder in Android Studio and run the `app` configuration on an Android TV emulator or device.
