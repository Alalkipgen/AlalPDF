# Alal PDF v0.3.6-beta

## Fixes

- Fixed the cold-start race that clamped a saved reading position to page 1
  before the PDF page count was available.
- Reading progress now uses a dedicated Room table, timestamps and stable
  document aliases, with a non-destructive migration from v0.3.5.
- Reader jobs now belong to one cancellable document scope and all PDFium calls
  are serialized process-wide.
- Text geometry, links, search results and UI thumbnails are bounded to prevent
  long-document memory growth.
- Reader state and native resources are fully cleared when a document closes.
- The app records the previous Android exit reason locally, distinguishing
  Java/native crashes, ANRs and low-memory kills without network telemetry.

## Version

- Version name: `0.3.6-beta`
- Version code: `14`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.
