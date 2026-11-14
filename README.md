# Countdown

Countdown is an offline Android app for tracking time until an event or since it happened. It’s built with Kotlin and Jetpack Compose and tested on a Tecno Camon 40 Pro 4G.

## Features

- Separate **Countdown** and **Countup** tabs with emoji, color-coded labels, search, sorting, and filters.
- True-black AMOLED mode and a glass-inspired bottom navigation bar.
- A default time format with per-timer overrides. **Auto** shows the largest applicable unit. **Precise** shows nonzero units from years through seconds.
- Optional local reminders when a countdown ends, one day before, or one week before. Enable reminders on the timer and allow notifications in Android. Precise delivery uses Android’s **Alarms & reminders** access.
- JSON backup and restore through Android’s file picker. Restore replaces the current timers and labels after confirmation.

The app runs entirely offline and requests no internet permission.

## Build from source

Use Android Studio’s bundled JDK or JDK 25. Install Android SDK Platform 37.0 and Build Tools 36.0.0. Open the project in Android Studio or set `JAVA_HOME` and `ANDROID_HOME` to your local installations, then run:

```sh
./gradlew :app:assembleDebug
```

The build writes the APK to `app/build/outputs/apk/debug/app-debug.apk`. The first build needs internet access to download Gradle and project dependencies. The installed app works offline.

## License

MIT. See [LICENSE](LICENSE).
