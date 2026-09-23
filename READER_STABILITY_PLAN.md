# Reader Persistence & Stability Plan — v0.3.6-beta

This file is the recovery checkpoint for the page-restore and reader-crash
work. If a development environment stops, continue from the first unchecked
item instead of repeating completed work.

## Confirmed root causes

1. On a cold process start, the saved page was applied while `pageCount == 0`.
   It was clamped to page zero, consumed, and immediately persisted over the
   real checkpoint.
2. Warm reopen masked that race because `releaseDocument()` left the old
   `pageCount` and text UI state in the Activity's retained ViewModel.
3. URI-only progress keys did not join SAF, MediaStore and file URI aliases.
4. Character geometry, links, page text and UI thumbnails could grow for every
   visited page.
5. URI/session-keyed reader ViewModels accumulated in the Activity
   `ViewModelStore`; per-page jobs were not owned by a cancellable document
   scope.
6. PDFium locks were per session even though PDFium requires process-wide
   serialization.

## Phase 1 — Cold-start restore

- [x] Keep page selection persistence disabled while page count is unknown.
- [x] Apply the saved target only after real lazy-list items exist.
- [x] Enable `onPageSelected` only after the target scroll completes.
- [x] Clamp out-of-range progress only after the final page count is known.
- [x] Reset reader UI state on document release.
- [x] Add pure restore-policy unit tests.

## Phase 2 — Durable progress

- [x] Add a dedicated Room `reading_progress` table.
- [x] Add a non-destructive Room v1 → v2 migration.
- [x] Resolve provider, metadata and legacy URI aliases for a document.
- [x] Store page plus timestamp and reconcile the newest journal/Room record.
- [x] Migrate existing URI preferences and recent-document progress on read.
- [x] Keep the existing library progress indicator synchronized.

## Phase 3 — Reader crash and memory hardening

- [x] Reuse one reader ViewModel/native repository per Activity.
- [x] Give every opened document a cancellable coroutine scope.
- [x] Cancel stale text/link/thumbnail/search work before close or reload.
- [x] Bound page text, character geometry and link state to the active window.
- [x] Bound Compose thumbnail state while keeping the disk tier.
- [x] Cap in-memory search results.
- [x] Serialize every PDFium source through a process-wide gate.
- [x] Clear all heavy state on release and critical memory callbacks.

## Phase 4 — Local crash diagnosis

- [x] Capture `ApplicationExitInfo` on Android 11+.
- [x] Distinguish Java crash, native crash, ANR and low-memory termination.
- [x] Save the latest report locally under app-private storage.
- [x] Preserve and delegate to Android's original uncaught-exception handler.
- [x] Keep diagnostics offline; no INTERNET permission or telemetry SDK.

## Verification

- [x] Unit tests
- [x] Debug APK assembly
- [x] Room migration instrumentation-test APK compilation
- [x] Debug and release lint
- [x] Release APK assembly and ZIP/secret integrity checks
- [ ] Cold-start instrumentation/device matrix:
  - Back and reopen
  - Home and reopen
  - Swipe task from Recents and reopen
  - Force-stop/process kill and reopen
  - Rotation while reading
  - Long image/text PDFs and rapid scrolling

## Acceptance criteria

- The first visible-page event can never overwrite a saved non-zero page during
  cold start.
- A persisted page survives task removal and process restart.
- Replacing a PDF with fewer pages safely clamps to its new last page.
- Reader memory reaches a bounded plateau instead of growing with every visited
  page.
- No stale reader coroutine can reopen a closed native session.
- A future unexpected exit leaves a local reason that identifies crash, native
  crash, ANR or low-memory kill.