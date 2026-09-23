# AlalPDF Overhaul — Progress Tracker

> **Current reader work:** Phase 1–4 persistence, memory and diagnostics are
> tracked in [`READER_STABILITY_PLAN.md`](READER_STABILITY_PLAN.md). Read that
> file first for v0.3.6-beta recovery and verification status.

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
- Rule: **commit + push after every phase.**
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
| 3 | Word boxes + Drive-style text selection | ✅ DONE |
| 4 | Create PDF: long title wrapping | ✅ DONE |
| 5 | WYSIWYG Edit PDF | ✅ DONE |
| PR | Open pull request into `main` | ✅ DONE |

All phases are code-complete on the branch. What remains is **device testing**:
build the branch, walk the regression checklist below, and report anything that
misbehaves so it can be fixed in a follow-up commit on the same branch.

---

## Root-cause summary (why these bugs happened)

User-reported issues 1, 2, 3 and 5 all collapsed into **one** defect:

1. `PdfReaderRepository.text(uri)` extracted text for **every page at once**.
2. On API >= 35 that ran inside the **render lock**, so scrolling stalled; on
   older versions it simply ran out of memory on a 109-page document.
3. `PdfReaderViewModel.load()` had **no `onFailure`**, so the crash was
   swallowed and `pageTexts` stayed empty forever.

Other independent defects:

- **Issue 4** — `CreatePdfRepository.addText()` drew `title.take(120)` with a
  single `drawText` inside a hard-coded `52f` block: no wrapping.
- **Issue 6** — Edit PDF was a page-reorder tool: one single-line field
  (`take(500)`, newlines stripped), one page, a fixed 48f white bar, no
  preview, and `importPage()` dropped outlines and links.
- **Issue 7** — `DISPLAY_DISTANCE = 1`, ARGB_8888 at 1400 px, a dead per-pixel
  night-invert loop, and a `delay(2000)` retry inside the item composable.
- **Issue 8** — the thumbnail grid read `state.pages`, which
  `pruneDisplayedPages()` trims to focused ±1.

---

## Non-negotiable safety rules for this branch

### Do NOT break PDF Create / Preview

Off-limits files (never touched on this branch):

- `create/CreatePdfScreen.kt`
- `create/PdfFilePreview.kt`
- `create/RichTextEditor.kt`
- `create/PyidaungsuFonts.kt`

Only `CreatePdfRepository.addText()` + a new private `titleLayout()` changed,
and the PDFBox dependency stayed in place for Create and Tools.

### Regression checklist — run on a device before merging

- [ ] Create → Text PDF → Preview renders
- [ ] Create → Save PDF produces a valid file
- [ ] Long title now wraps instead of being cut off
- [ ] Myanmar text renders correctly in the created PDF
- [ ] Create → Images / Image+Text / Scan modes still work
- [ ] The created PDF opens correctly in the Reader
- [ ] Reader: long-press selects a word, dragging extends it, Copy works
- [ ] Reader: Search document returns matches and jumps to the page
- [ ] Reader: thumbnails show for every page
- [ ] Edit PDF: place text, see it in the preview, save, reopen and verify
- [ ] `./gradlew assembleDebug` succeeds

---

## Phase detail

### Phase 0 — Surface hidden errors ✅
- [x] `textState` (Loading / Ready / ImageOnly / Failed) + `textError`
- [x] Real `.onFailure` on the text branch
- [x] Four distinct reader messages instead of "No selectable text"

### Phase 1 — Text pipeline ✅
- [x] `pageText(uri, page)` / `pageTextRuns(uri, page)` replace `text(uri)`
- [x] `PdfTextIndex`: spooled document, `setupMixed`, 48-page LruCache
- [x] Text extraction moved onto its own `textLock`
- [x] `MyanmarText`: NFC normalize, zero-width strip, Zawgyi detection + convert
- [x] Incremental search with progress, every match per page, jump to result

### Phase 2 — Render performance ✅
- [x] `DISPLAY_DISTANCE` 1 → 3, full width only within ±1
- [x] Two-pass render (360 px, then full width)
- [x] Dead per-pixel night-invert loop deleted
- [x] `delay(2000)` retry removed
- [x] `ThumbnailCache`: 160 px RGB_565 memory + WebP disk cache
- [x] Grid reads `state.thumbnails`
- [ ] ~~`inBitmap` pool~~ — intentionally skipped: Compose may still be drawing
      an evicted bitmap, so reusing its memory risks visible corruption.

### Phase 3 — Selection ✅
- [x] `PdfTextIndex.pageRuns()`: word boxes from PDFBox `TextPosition`,
      normalised to the crop box, memoized per page
- [x] `pageTextRuns()` falls back to those boxes below API 35
- [x] New `SelectionLayer`: long-press word select, drag to extend, highlight,
      drag handles, floating Copy / Search / Share bar, tap to dismiss
- [x] Old transparent-`Text` + `SelectionContainer` page overlay removed
- [ ] ~~pdfium~~ — intentionally skipped: it would add an unverifiable JNI
      dependency, while PDFBox is already shipped and exposes the same glyph
      geometry. Revisit only if profiling shows PDFBox is too slow.

### Phase 4 — Create PDF title ✅
- [x] `titleLayout()`: `StaticLayout` wrapping at the body column width
- [x] Auto-shrink 24 → 20 → 18 past three lines
- [x] Body starts below the measured title height, `take(120)` gone

### Phase 5 — Edit PDF rewrite ✅
- [x] `TextNote` / `ImageNote`: any number of notes on any page
- [x] Rendered page background, tap to place, live preview of every note
- [x] Measured line breaking, own line breaks kept, no 500-character cut
- [x] Whiteout is a sized rectangle instead of a fixed 48f bar
- [x] Pages reordered inside the original document, so outlines and links
      survive (`importPage` gone)
- [x] Images use `LosslessFactory`, so PNG and screenshots work

---

## Changelog

| Date | Commit | Note |
|------|--------|------|
| 2026-09-20 | `60b0bb3` | Branch created, progress tracker added |
| 2026-09-20 | `97dc2ee` | Phase 0 — text load state + real failure handling |
| 2026-09-20 | `6315782` | Phase 1a — `MyanmarText`, `PdfTextIndex`, lazy repository |
| 2026-09-20 | `c723f6d` | Phase 1b — reader UI wired to the lazy pipeline |
| 2026-09-20 | `f5ae18e` | Phase 2a — two-pass render, thumbnail cache |
| 2026-09-20 | `6acb968` | Phase 2b — grid uses thumbnails, retry loop removed |
| 2026-09-20 | `74dde13` | Phase 3a — word boxes + `SelectionLayer` |
| 2026-09-20 | `3ae36fa` | Phase 3b — reader page uses the selection layer |
| 2026-09-20 | `9118754` | Phase 4 — wrapped Create PDF titles |
| 2026-09-20 | `54dadd8` | Phase 5 — WYSIWYG Edit PDF |
