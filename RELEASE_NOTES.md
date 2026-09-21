# Alal PDF v0.2.2-beta

## Fixes in this release

- Create Text PDF scrolling is smooth and stable. The title and body now share one scroll container, and scrolling no longer resizes the Compose/Android view hierarchy on every pixel. The title scrolls away naturally and the top bar follows without flashing.
- A hyperlink is emitted only on the PDF page and line that actually contain it. Trailing paragraph breaks are removed from link spans, duplicate annotations are rejected, and every annotation has an explicit target page.
- PDF text selection uses binary spatial lookup for both lines and characters. Handles preserve the exact point where the finger grabbed them, selection endpoints stay stable, and edge scrolling no longer queues a coroutine for every pointer event.

## Requirements

Minimum Android version: Android 8.0 (API 26). Distributed as an APK.

## Installation

Download the APK, enable **Install unknown apps** for the app that opens it, then confirm installation. The application ID is unchanged and versionCode 6 allows installation over v0.2.1-beta when both builds use the same signing key.

## Privacy

Alal PDF works offline and does not request the Android `INTERNET` permission.
