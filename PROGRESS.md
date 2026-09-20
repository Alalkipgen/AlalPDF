# AlalPDF Overhaul — Progress Tracker

> **Purpose:** This file is the single source of truth for resuming work after a
> sandbox/session crash. Always read this file FIRST before doing anything.

## Resume instructions (read me first)

```bash
git clone -b fix/alalpdf-overhaul https://github.com/Alalkipgen/AlalPDF.git
cd AlalPDF
cat PROGRESS.md      # <- you are here; find the first unchecked box and continue
```

- Branch: `fix/alalpdf-overhaul`
- Base branch: `main`
- Base SHA: `178b5205d8accc86dd78596d1dfa8676f24f7db1`
- Rule: **never force-push, never rewrite history, never commit directly to `main`.**
- Rule: **commit + push after every phase** (and after every risky single file edit).
- Rule: **push every interdependent file in the same commit** so the branch
  always compiles on its own.

---

## Status board

| Phase | Title | Status |
|-------|-------|--------|
| Setup | Branch + PROGRESS.md | ✅ DONE |
| 0 | Surface hidden text-extraction errors | ✅ DONE |
| 1 | Rebuild text pipeline (lazy extract, Myanmar normalize, real search) | ✅ DONE |
| 2 | Render performance + thumbnail pipeline | ✅ DONE |
| 3 | pdfium engine + Drive-style text selection | ⬜ TODO ← **resume here** |
| 4 | Create PDF: long title wrapping | ⬜ TODO |
| 5 | WYSIWYG Edit PDF overlay editor | ⬜ TODO |
| PR | Open pull request into `main` | ⬜ TODO |

---

## Root-cause summary (why these bugs happen)

User-reported issues 1, 2, 3 and 5 all collapse into **one** defect:

1. `PdfReaderRepository.text(uri)` extracts text for **every page at once**.
   For a 109-page document this loads the whole `PDDocument` into memory and
   runs `PDFTextStripper(sortByPosition = true)` 109 times.
2. On API < 35 that whole loop runs on PDFBox, which is slow and OOM-prone.
   On API >= 35 it runs inside `synchronized(lock)` — the **same lock the page
   renderer uses** — so rendering stalls while text is extracted.
3. `PdfReaderViewModel.load()` calls it as
   `runCatching { repository.text(uri) }.onSuccess { ... }` with **no
   `onFailure`**, so any exception is silently swallowed.

Result: `pageTexts` stays empty forever →
- "No selectable text" (issue 2)
- `Search document` returns `0 page(s)` (issue 3)
- Nothing to copy, so Myanmar encoding cannot be verified (issue 5)
- Selection overlay has no text runs to draw (issue 1)

Other independent defects:

- **Issue 4** — `CreatePdfRepository.addText()` uses a single
  `canvas.drawText(title.take(120), ...)` with a hard-coded `52f` title block
  height. No line wrapping → long titles run off the right edge.
- **Issue 6** — `PdfToolsScreen` / `PdfToolsRepository` is a page-reorder tool,
  not an editor: one single-line text field (`take(500)`, newlines stripped),
  one target page, a fixed `48f`-tall white cover rectangle, and no preview.
- **Issue 7** — `DISPLAY_DISTANCE = 1`, `ARGB_8888` at 1400 px (~8 MB/page),
  a dead per-pixel night-invert loop in `renderPage`, a `delay(2000)` retry
  inside the item composable, and a single global render lock.
- **Issue 8** — the thumbnail grid reads `state.pages`, which
  `pruneDisplayedPages()` trims to focused ±1, so only ~2-3 thumbnails exist.

---

## Non-negotiable safety rules for this branch

### Do NOT break PDF Create / Preview

These files are **off limits** except where explicitly listed in Phase 4:

- `create/CreatePdfScreen.kt`
- `create/PdfFilePreview.kt`
- `create/RichTextEditor.kt`
- `create/PyidaungsuFonts.kt`

Additional guardrails:

- **Keep the PDFBox dependency.** Create and Tools keep using PDFBox; only the
  Reader migrates to pdfium.
- `PdfRendererSource` becomes an interface with two implementations so the
  Reader can swap engines without touching anything the Create flow uses.
- Phase 4 touches **only** `CreatePdfRepository.addText()`.

### Regression checklist — run after EVERY phase

- [ ] Create → Text PDF → Preview renders
- [ ] Create → Save PDF produces a valid file
- [ ] Myanmar text renders correctly in the created PDF
- [ ] Create → Images / Image+Text / Scan modes still work
- [ ] The created PDF opens correctly in the Reader
- [ ] `./gradlew assembleDebug` succeeds before pushing

---

## Phase detail

### Phase 0 — Surface hidden errors ✅
- [x] `PdfReaderUiState`: `textState` (Loading / Ready / ImageOnly / Failed)
- [x] `PdfReaderViewModel`: real `.onFailure` on the text branch + `textError`
- [x] `PdfReaderScreen`: four distinct messages instead of "No selectable text"
      (done with Phase 1)

### Phase 1 — Text pipeline ✅
- [x] `PdfReaderRepository`: `text(uri)` replaced by `pageText(uri, page)` and
      `pageTextRuns(uri, page)`
- [x] `PdfTextIndex`: one spooled `PDDocument`, `MemoryUsageSetting.setupMixed`,
      48-page `LruCache`, one page stripped at a time
- [x] Text extraction moved off the render lock onto its own `textLock`
- [x] New `MyanmarText.kt`: NFC normalize, zero-width strip, Zawgyi detection,
      best-effort Zawgyi → Unicode, `searchKey()`
- [x] `search()`: every match per page, normalized, incremental with progress
- [x] Search UI: query field, live progress, result list, jump to page

### Phase 2 — Render performance ✅
- [x] `DISPLAY_DISTANCE` 1 → 3 (full-width only within ±1: `FULL_QUALITY_DISTANCE`)
- [x] Two-pass render: 360 px pass first, then full width
- [x] Deleted the dead per-pixel night-invert loop
- [x] Removed the `delay(2000)` retry from the item composable
- [x] New `ThumbnailCache.kt`: 160 px RGB_565 + WebP disk cache
- [x] Thumbnail grid reads `state.thumbnails`
- [ ] ~~`inBitmap` pool~~ — intentionally skipped. Compose can still be drawing
      an evicted bitmap, so reusing its memory risks visible corruption. The
      two-pass render already removed most of the allocation pressure.

### Phase 3 — pdfium + selection ⬅ NEXT
- [ ] Add the pdfium dependency
- [ ] Extract a `PdfRendererSource` interface; add a pdfium implementation
- [ ] Character-box API wrapper (`GetCharBox`, `GetCharIndexAtPos`, `FindNext`)
- [ ] New `SelectionLayer.kt`: long-press, drag handles, highlight rects,
      floating Copy / Highlight / Share toolbar
- [ ] Cross-page selection
- [ ] Delete the old transparent-`Text` + `SelectionContainer` overlay

### Phase 4 — Create PDF title
- [ ] `addText()`: `StaticLayout` for the title, dynamic block height,
      drop `take(120)`, auto-shrink beyond 3 lines

### Phase 5 — Edit PDF rewrite
- [ ] Multiple text annotations across multiple pages
- [ ] Tap-to-place with live WYSIWYG preview
- [ ] Freeform whiteout rectangle instead of the fixed 48f bar
- [ ] Manual line breaking using measured font width
- [ ] Preserve bookmarks and links (stop using `importPage`)

---

## Changelog

| Date | Commit | Note |
|------|--------|------|
| 2026-09-20 | `60b0bb3` | Branch created, progress tracker added |
| 2026-09-20 | `97dc2ee` | Phase 0 — text load state + real failure handling |
| 2026-09-20 | `6315782` | Phase 1a — `MyanmarText`, `PdfTextIndex`, lazy repository, new view model |
| 2026-09-20 | `c723f6d` | Phase 1b — reader UI wired to the lazy pipeline (branch compiles again) |
| 2026-09-20 | `f5ae18e` | Phase 2a — two-pass render, thumbnail cache, dead night-invert removed |
| 2026-09-20 | _(this commit)_ | Phase 2b — grid uses thumbnails, retry loop removed |
