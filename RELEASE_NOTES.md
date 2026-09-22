# Alal PDF v0.3.4-beta

## Fixes

- Reopening a PDF now returns to the last page read. Refreshing Recent-document metadata no longer replaces the database row with `lastReadPage = 0`.
- The reader waits for persisted progress before composing its page list, preventing page 1 from winning a startup race and overwriting the saved page.
- Leaving through either the toolbar or Android back gesture performs a non-cancellable final progress write.
- Common portrait and landscape widths share one stable full-quality render size, while a short width-change debounce avoids duplicate work during rotation.

## Version

- Version name: `0.3.4-beta`
- Version code: `12`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.
