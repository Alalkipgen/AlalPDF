# Reader fast-scroll: crash and rendering root cause

Target: v0.3.8-beta (`versionCode 16`)

Reported symptoms:

1. Scrolling quickly through PDF pages makes the scroll seize up, and a moment
   later the app quits by itself, like a crash.
2. Rendering and loading while scrolling is poor; the reader feels laggy.
3. Scrolling is not smooth.

## Short answer on PDFBox vs PDFium

Shipping both engines is **not** the problem in itself. They never touch the
same handles: PDFium only does text geometry, `android.graphics.pdf.PdfRenderer`
does the rasterization, and PDFBox only existed to read link annotations and to
decrypt password-protected files.

**How PDFBox was being used was absolutely part of the problem.**
`PdfLinkExtractor.extractPage()` performed a full `PDDocument.load()` - a
complete xref and object parse of the entire file into the Java heap - and the
reader called it **once per visible page**, on the scroll path. Scrolling
through thirty pages meant thirty full parses of the whole document, running
concurrently with PDFium text extraction and PdfRenderer rasterization of the
same file. That is three PDF stacks hammering one document at once, and it is
the single most expensive thing that used to happen while scrolling.

## Root causes

### 1. IO thread-pool starvation, which is what actually killed the app

Every render, preview, thumbnail, text and link request ran on
`Dispatchers.IO` and then **blocked** inside a `synchronized` block on the
shared `PdfDocumentSession`. `Dispatchers.IO` tops out at 64 threads.

A fast fling composes every page it passes, and every composed page issued a
request, so a fling across a long document parked all 64 IO threads on the same
monitor. Everything else that needs an IO thread - the Room write, the SAF
permission check, the reading checkpoint - then had nowhere to run. The main
thread waited on that work, the watchdog saw an unresponsive UI thread, and the
system killed the process.

That is exactly the reported shape: **the scroll freezes first, then the app
disappears a moment later.** It is an ANR kill, not a Kotlin exception, which is
why it never showed up as a normal crash.

**Fix** - `PdfWorkDispatchers`: one bounded dispatcher for rasterization, one
for PDFium text, one for PDFBox metadata. Native PDF work is serialized by locks
anyway, so bounding parallelism costs no throughput; the difference is that
callers now *suspend* while waiting their turn instead of holding a thread.

### 2. Synchronous disk writes on the main thread, once per page

`ReaderRoute.onPageSelected` runs on every visible-page change, and it called
`progressStore.checkpoint()`, which used `SharedPreferences.commit()` - a
blocking fsync. Flinging across 100 pages meant **100 synchronous disk writes on
the UI thread**, plus a Room write each time.

Worse, `syncToDatabase()` used `withContext(NonCancellable)`, which replaces the
Job but *not* the dispatcher, so it inherited the caller's
`rememberCoroutineScope()` - the main dispatcher.

**Fix** - `checkpoint()` now uses `apply()`. The value is published to the
in-memory map instantly and Android's `QueuedWork` forces the flush at the next
lifecycle transition, so `ON_STOP` durability is unchanged.
`checkpointBlocking()` stays available for a genuine hard fsync.
`syncToDatabase()` is pinned off the main thread and debounced.

### 3. One unbounded coroutine per thumbnail

`requestThumbnail` did `documentScope.launch { ... }` for every page beyond the
render window - no queue, no bound, no cancellation. Each one blocked on the
render lock and each one also wrote a WebP file to disk.

**Fix** - thumbnails go through the same single-consumer priority queue as page
renders, at a priority worse than any page work, so the grid can never delay the
reader. A page that fails to rasterize is remembered so the list cannot
re-request it forever.

### 4. O(n) queue bookkeeping on the main thread

`enqueueLocked` scanned the `PriorityQueue` with `firstOrNull { ... }` and
`requestWindow` did `filter {}` + `removeAll {}` - linear scans, under a lock
shared with the drain loop, executed from composition on the **main thread**,
for every list item that scrolled past.

**Fix** - a `HashMap` of the best pending request per page makes enqueueing
O(1). Superseded entries stay in the queue and are skipped when polled. The
queue is compacted once per gesture, when the list settles, and has a hard
length ceiling.

### 5. Doing maximum work at the worst possible moment

During a fling the reader was rendering pages, extracting character geometry and
parsing link annotations for pages that were already gone by the time the work
finished.

**Fix** - the view model derives the fling phase from the cadence of
focused-page changes. While the list is moving, only the focused page and its
immediate neighbour are rasterized, and distant-page thumbnails are not even
requested. Everything else waits in the queue and is dropped for free once it
drifts out of the viewport. The settle threshold sits below the reader's
existing 250 ms extraction debounce, so text selection and link tapping behave
exactly as before.

Also: `pruneTextLayers` rebuilt three maps and pushed a new UI state on every
single page change. It now only copies state when something actually falls out
of the retention window.

### 6. A data race on the session password

`readerSession(uri, password = session?.password)` evaluated its default
argument **outside** `sessionLock`, so it could read a session another thread
was closing. Split into two explicit accessors, both fully inside the lock.

## Changes

| File | Change |
| --- | --- |
| `reader/PdfWorkDispatchers.kt` | New. Bounded dispatchers for render / text / metadata. |
| `reader/ReaderScrollPolicy.kt` | New. Scroll-phase scheduling rules, unit tested. |
| `reader/PdfPageLink.kt` | `extractDocument()` reads every page's links in one PDFBox pass. |
| `reader/PdfReaderRepository.kt` | Bounded dispatchers, memoized link index, race-free session accessors. |
| `reader/PdfReaderViewModel.kt` | Unified render/thumbnail queue, O(1) bookkeeping, fling-aware scheduling, cheaper pruning. |
| `reader/ReadingProgressStore.kt` | Non-blocking checkpoints, off-main debounced Room sync. |
| `app/build.gradle.kts` | `versionCode 16`, `versionName 0.3.8-beta`. |

## Verification

- `ReaderScrollPolicyTest` covers the fling scheduling and staleness rules.
- `ReaderMemoryPolicyTest` and `VisiblePagePolicyTest` are unchanged and still
  describe the same behaviour; no public signature they depend on was touched.

Manual checks worth running on a long document (100+ pages):

1. Fling hard from the first page to the last, repeatedly. The scroll must stay
   responsive and the process must survive.
2. Stop on a page and confirm it sharpens from preview to full quality.
3. Open the page grid and scroll it; thumbnails must still fill in.
4. Select text and tap a link on a settled page.
5. Leave the reader and reopen it; the saved page must be restored.

## Remaining UI-layer polish

Not required for the crash, but worth doing next, all inside
`PdfReaderScreen.kt`:

- The `derivedStateOf` that computes the most-visible page allocates a list per
  read; it can scan `visibleItemsInfo` without allocating.
- `items(...)` has no `contentType`, so LazyColumn cannot reuse page slots.
- The `LazyColumn` is wrapped in `horizontalScroll(...).width(pageWidth)` even at
  `scale == 1f`, which adds a layout pass that is only needed when zoomed.
- `SelectionLayer` builds its `SelectionIndex` over thousands of character runs
  during composition; it could be skipped entirely while the list is moving.
