# Alal PDF v0.3.0-beta

## Fixes

- Alal-created Text PDFs now reopen in the structured blank-page editor, with direct **Save** and **Save As** instead of the general overlay editor.
- Reworked reader ownership: Android `PdfRenderer` renders pages, PDFium handles text geometry/search/links, and PDFBox is limited to short-lived encrypted unlock, metadata, creation, and editing work.
- Reader memory is bounded by device capability, uses a three-page display window, renders only the focused page at full quality, and uses low-memory RGB_565 previews for neighboring pages.
- Added direction-aware render priority, instant previous-page cache reuse, Android memory-pressure trimming, and an OOM-safe low-resolution fallback instead of an app crash.
- Text selection and hyperlinks load lazily after scrolling settles, avoiding competition with page rendering and preventing long-lived duplicate PDF document models.
- PDF hyperlink hit boxes now come from the same PDFium session as text selection, with URL validation, rotation handling, and duplicate filtering.
- Alal-created PDF save paths preserve structured draft metadata so later edits remain Word/notes-style.

## Version

- Version name: `0.3.0-beta`
- Version code: `8`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.
