# Alal PDF v0.3.2-beta

## Fixes

- The reader now focuses the page occupying the largest visible area instead of the first list item. Partially visible hyperlink pages receive full-quality rendering and active link hit boxes without requiring zoom.
- Text and links load for every page currently visible on screen, so hyperlinks work across page boundaries.
- Readable RGB_565 previews are retained in the bounded cache, preventing revisited pages from falling back to a white page-number placeholder.
- **Save As** uses `ContentResolver.openOutputStream()` instead of double-owning a raw file descriptor, and no longer opens a second PDFBox document immediately after writing. This removes the native crash/0-byte-file path.
- Image, Text + Image, and Scan PDFs created by Alal now reopen in their matching Create UI. Image/scan pages are reconstructed as temporary editable assets when original source URIs are unavailable.

## Version

- Version name: `0.3.2-beta`
- Version code: `10`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.
