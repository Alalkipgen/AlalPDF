# Alal PDF v0.3.5-beta

## Fixes

- The last-read page is now synchronously checkpointed after each settled page and when the reader stops.
- Reading progress survives swiping Alal PDF away from Android Recents and a complete app-process restart.
- Room remains the long-term progress store, with a small SharedPreferences checkpoint preventing process-kill write races.

## Version

- Version name: `0.3.5-beta`
- Version code: `13`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.
