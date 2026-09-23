# Alal PDF v0.3.7-beta — UI performance and crash recovery

This file is the recovery checkpoint for the library-scroll and haptic pass.

## Root cause

- The library thumbnail cache was limited to 48 **entries**, not bytes.
- A row could retain a tall 480 px ARGB bitmap worth several megabytes.
- Fast scrolling could open many `PdfRenderer` instances concurrently.
- Recent-file permission checks performed blocking file-descriptor I/O on the
  Compose main thread.
- Ordinary taps, toggles and scrollbar gestures all used the same heavy
  long-press vibration.

## Implementation checklist

- [x] Byte-bounded thumbnail LRU (4–12 MiB based on heap size).
- [x] Render thumbnails at their actual on-screen size and retain RGB_565.
- [x] Limit native thumbnail rendering to two concurrent jobs.
- [x] Pause new thumbnail work during a fling and resume after scroll settles.
- [x] Trim the thumbnail cache on Android memory-pressure callbacks.
- [x] Move recent-file readability checks to `Dispatchers.IO`.
- [x] Add lazy-list `contentType` values alongside stable keys.
- [x] Replace heavy generic haptics with semantic, system-respecting effects.
- [x] Add unit coverage for thumbnail memory and dimension policy.
- [x] Run unit tests and debug build.
- [x] Run lint and release build.
- [x] Commit and push to `main`.

## Recovery

```bash
cd /data/AlalPDF-ui-performance
git status --short --branch
cat UI_PERFORMANCE_PLAN.md
```

Branch: `fix/library-scroll-crash-haptics`  
Base: `763bf8966469e298ea746e435c13932552be8bee`