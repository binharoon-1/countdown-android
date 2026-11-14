# Countdown

Countdown is an offline Android app for tracking time until an event or since it happened. It is built with Kotlin and Jetpack Compose and tested on a Tecno Camon 40 Pro 4G.

## Features

- Separate countdown and countup views with emoji, color-coded labels, search, sorting, and filters.
- AMOLED and dark themes with a glass-inspired bottom tab bar.
- A default time format and per-timer overrides. Auto shows the largest applicable unit. Precise displays nonzero units from years through seconds.
- Local countdown reminders at the end, one day before, or one week before. Enable reminders on the timer and allow Android notifications. Precise delivery requires Alarms & reminders access on supported Android versions.
- Offline JSON backup and restore. Restore replaces the current timers and labels after confirmation.

The app requests no internet permission.

## Build

Use Android Studio's bundled JDK or JDK 25 and install Android SDK Platform 37.0 and Build Tools 36.0.0. Open the project in Android Studio or set `JAVA_HOME` and `ANDROID_HOME` to your local installations, then run:

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The first build needs network access to fetch Gradle and dependencies. The installed app runs offline.

The debug APK is intended for preview and testing. Save a JSON backup before switching to a build signed with a different key.
