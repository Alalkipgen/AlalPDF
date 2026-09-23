# Alal PDF v0.3.7-beta

## Performance and stability

- Fixed library-scroll memory growth by replacing the 48-entry PDF thumbnail
  cache with a byte-bounded 4–12 MiB LRU cache.
- First-page previews now render at their actual on-screen size and are retained
  as RGB_565, substantially reducing per-row bitmap memory.
- Native thumbnail rendering is limited to two jobs and pauses during active
  flings, preventing background PDF work from competing with scroll frames.
- Thumbnail memory is trimmed when Android reports low-memory or background
  pressure, and an allocation failure now falls back to the placeholder instead
  of terminating the app.
- Recent-file readability checks and reader permission checks no longer block
  the Compose main thread.
- Library rows now provide stable keys and content types for more efficient
  lazy-list composition reuse.

## Haptics

- Replaced heavy long-press vibration on normal taps, toggles and scrollbar
  gestures with semantic system haptics.
- Added consistent light feedback to library filters, file actions and create
  actions while respecting the device's touch-feedback setting.

## Version

- Version name: `0.3.7-beta`
- Version code: `15`
- Minimum Android: Android 8.0 (API 26)

Alal PDF works offline and does not request the Android `INTERNET` permission.