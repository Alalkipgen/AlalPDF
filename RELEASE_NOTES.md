# Alal PDF v0.3.1-beta

## Fixes

- **Save As** now builds and verifies the complete PDF before opening Android's file picker. Failed writes remove new zero-byte placeholders without ever deleting an existing document.
- Document-provider writes retry safe truncate modes and verify both the PDF header and page count before reporting success.
- Alal-created PDFs store exact hyperlink URLs and normalized hit boxes in document metadata. Older Alal PDFs and external PDFs use a short-lived annotation compatibility reader.
- Leaving the reader for the structured editor explicitly closes its renderer and text engines, preventing the native-memory spike that could terminate the app during Save As.
- Create Text PDF now shows labeled **Preview** and **Save** actions. Edit mode keeps **Save As** in the additional save menu.
- Low-resolution previews remain fast while scrolling; after scrolling settles, the focused page is immediately promoted ahead of neighbor work for crisp text.

## Version

- Version name: `0.3.1-beta`
- Version code: `9`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.
