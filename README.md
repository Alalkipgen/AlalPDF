# Alal PDF

Alal PDF is an offline-first PDF reader for Android with local reading progress, bookmarks, themes, page navigation, and sharing.

## Features

- Open local PDFs with Android's document picker.
- Persist recent documents, reading position, and bookmarks with Room.
- Page jump dialog, page indicator, light/dark/system themes, and night-mode rendering.
- Share the opened document through Android's share sheet.
- Work without network access.

## Screenshots

Screenshots will be added after release-candidate device validation. The release checklist covers the library, reader, page jump, theme, bookmark, and share flows.

## Requirements and build

Android Studio with SDK 35, JDK 17, and Android API 26 or newer are required. Application ID: `com.alalkipgen.alalpdf`; version: `0.1.0-beta`.

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
./gradlew assembleRelease
```

Use `gradlew.bat` on Windows. CI runs debug assembly, unit tests, lint, and release assembly on pushes to the development branch.

## Architecture

`library` owns document discovery and recent-document UI. `reader` owns serialized `PdfRenderer` access, page rendering, bitmap caching, ViewModel state, and reader UI. `data` owns Room entities, DAOs, database creation, migration, and repositories. Compose renders ViewModel state, while rendering runs on the IO dispatcher and a mutex protects each renderer.

Room schema version 1 stores recent documents, progress, and bookmarks. Legacy preferences are imported once. Future schema changes require an explicit migration and version increment; destructive migration is disabled.

## Privacy

Documents are opened from user-selected URIs. PDF files, reading history, and bookmarks remain on the device; the app does not upload them and has no network requirement. Android backup is enabled in the manifest, so users requiring device-local-only storage should disable app backup in system settings.

## Known limitations

- Corrupted or password-protected PDFs may fail because Android `PdfRenderer` has no password-entry API.
- Large pages can use substantial memory; device memory affects rendering speed.
- Cloud sync, annotations, text search, and OCR are not included.
- Instrumented tests require an Android device or emulator.

## Third-party dependencies and licenses

The project uses Kotlin, Kotlin Coroutines, AndroidX Core/Activity/Lifecycle/Compose/Material 3/Room/Test, Android Gradle Plugin, Gradle, and Kotlin Compose tooling. These projects are distributed under the Apache License 2.0. Versions are declared in `app/build.gradle.kts`; consult each upstream project for its full license and notices when redistributing.

## Release installation

Download the signed APK from the release assets, allow installation from the selected source when Android prompts, install it, and launch **Alal PDF**. For local testing, `./gradlew assembleRelease` writes the APK to `app/build/outputs/apk/release/`. Production APKs must be signed with a private release key; never commit that key or its passwords.