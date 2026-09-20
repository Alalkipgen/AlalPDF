# Alal PDF v0.1.3-beta

## Fixes in this release

- Text selection now works. Word boxes come from the PDF text layer, and a long press opens Drive-style selection with drag handles and a Copy / Search / Share bar.
- Search now finds text. Pages are extracted one at a time with a real error path, every match on a page is reported, and tapping a result jumps to the page.
- Myanmar text is handled correctly. Text is NFC-normalised, invisible characters are stripped, and Zawgyi is detected and converted only when it really is Zawgyi, so Unicode Burmese pages are left untouched and match either encoding.
- Page rendering is faster. A cheap low-resolution pass shows the page first, the render window is wider, and a dead per-pixel night-invert loop and a duplicate retry delay were removed.
- The thumbnail grid shows every page. Thumbnails come from a dedicated 160 px memory and disk cache instead of the live render map.
- Long document titles wrap over up to three lines and shrink instead of being cut off.
- Edit PDF is usable: the page renders underneath while editing, notes are placed by tapping, text wraps and keeps line breaks, whiteout is a sized rectangle, and page reordering keeps outlines and links intact.

## Features

- Local PDF opening and page rendering
- Text selection, copy, and in-document search
- Recent documents and reading-progress restoration
- Bookmarks and page navigation
- Thumbnail grid
- Basic PDF editing: notes, whiteout, page reordering
- Light, dark, and system themes with night-mode rendering
- Android share action

## Requirements

Minimum Android version: Android 8.0 (API 26). This release is distributed as an APK and is not available through Google Play Store.

## Installation

Download the APK asset from this GitHub Release. Enable **Install unknown apps** for the browser or file manager that opens the APK, then open it and confirm installation.

This release shares the same `applicationId` as earlier builds and has a higher `versionCode`, so it installs as an update over the previous build.

## Known limitations

OCR, text reflow, annotations, signatures, and PDF merge/split are not included.

## Privacy

Alal PDF is offline-only, requests no `INTERNET` permission, and does not send PDF content outside the device.

## Third-party dependencies and licenses

The application uses Kotlin, Kotlin Coroutines, AndroidX, Jetpack Compose, Material 3, Room, PDFBox-Android, Android Gradle Plugin, Gradle, and AndroidX Test. These upstream projects are distributed under the Apache License 2.0. Dependency versions are declared in `app/build.gradle.kts`; consult upstream notices for complete license text.
