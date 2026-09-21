# Alal PDF v0.3.3-beta

## Fixes

- Long Text and Text + Image draft bodies are no longer duplicated into Android's Activity saved-state Bundle when opening the **Save As** file picker.
- Native `EditText` hierarchy-state saving is disabled for the structured editor because Compose already owns the live document.
- The already-verified temporary PDF path remains saveable, so the picker result can finish writing without carrying the full document through Binder.

## Version

- Version name: `0.3.3-beta`
- Version code: `11`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.
