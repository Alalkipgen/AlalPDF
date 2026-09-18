# Alal PDF v0.1.0-beta

## Features

- Local PDF opening and page rendering
- Recent documents and reading-progress restoration
- Bookmarks and page navigation
- Light, dark, and system themes with night-mode rendering
- Android share action

## Requirements

Minimum Android version: Android 8.0 (API 26). This release is distributed as an APK and is not available through Google Play Store.

## Installation

Download the APK asset from this GitHub Release. Enable **Install unknown apps** for the browser or file manager that opens the APK, then open it and confirm installation. Alal PDF is not available in the Play Store.

## Known limitations

Text search, OCR, text reflow, annotations, signatures, and PDF merge/split are not included.

## Privacy

Alal PDF is offline-only, requests no `INTERNET` permission, and does not send PDF content outside the device.

## Third-party dependencies and licenses

The application uses Kotlin, Kotlin Coroutines, AndroidX, Jetpack Compose, Material 3, Room, Android Gradle Plugin, Gradle, and AndroidX Test. These upstream projects are distributed under the Apache License 2.0. Dependency versions are declared in `app/build.gradle.kts`; consult upstream notices for complete license text.