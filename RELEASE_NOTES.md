# Alal PDF v0.2.3-beta

## Fixes

- PDFs created by Alal PDF now open in the real PDF Editor instead of returning to the Create screen. The editor offers **Save** for safe in-place replacement and **Save As** for a new file, and cleans temporary overlay resources on every success or failure path.
- Whole-page Rewrite no longer crashes when the page text changes while the dialog is open.
- Text and hyperlinks are laid out from an exclusive character slice per page. A bottom-page link can no longer be painted or annotated again on the following page; duplicate annotation hit targets are also filtered while reading.
- Create Text PDF uses one native scroll owner for title and body without driving Compose top-bar layout on every pixel, removing reverse-scroll clipping and jank.
- The reader fast-scroll thumb reacts immediately to a new gesture and stays below the top app bar.
- Reader pages receive low-resolution readable previews across a wider prefetch window before expensive full-resolution rendering, reducing numbered white placeholders during scrolling.

## Version

- Version name: `0.2.3-beta`
- Version code: `7`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.
