# Alal PDF

Offline-first PDF reader for Android, built with Kotlin and Jetpack Compose.

Phase 1 establishes the Android application foundation. PDF file access and reader features are planned for later phases.

## Build

Open the project in Android Studio with Android SDK 35 installed, then run the `app` debug configuration.

Application ID: `com.alalkipgen.alalpdf`

Minimum Android version: 8.0 (API 26)

## Persistence

Room schema version 1 stores recent documents, reading progress, and bookmarks. On first database open, the legacy SharedPreferences stores are imported once and marked with a migration flag. Future schema changes must add an explicit `Migration` object and increment the database version; destructive migration is intentionally disabled.