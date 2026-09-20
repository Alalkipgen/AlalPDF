# Alal PDF v0.2.1-beta

## Fixes in this release

- Burmese text copied or searched from a PDF that Alal PDF created is now
  exact. Android shapes Myanmar correctly when it draws a page, but it can only
  map one glyph to one code point afterwards, so reading that text back lost
  medials and reordered syllables. The real text is now stored with the
  document and used directly. PDFs made by other apps with a broken ToUnicode
  table still cannot be read back correctly; that needs OCR.
- Text selection is smooth. Hit testing is a binary search over a prebuilt line
  index and grapheme boundaries are precomputed, instead of scanning every
  character and rebuilding a break iterator on every drag event. Handles now
  track the finger from wherever they were grabbed.
- Links work again, and a single tap toggles the top bar again: the selection
  overlay used to swallow every tap and now forwards them when nothing is
  selected.
- Pages no longer jump when the top bar appears or disappears; the space it
  needs is always reserved and only the bar itself fades.
- Create Text PDF: the title scrolls away with the text and comes back at the
  top, and the body can no longer draw over it.

# Alal PDF v0.2.1-beta

## Fixes in this release

- Burmese text copied or searched from a PDF that Alal PDF created is now
  exact. Android shapes Myanmar correctly when it draws a page, but it can only
  map one glyph to one code point afterwards, so reading that text back lost
  medials and reordered syllables. The real text is now stored with the
  document and used directly. PDFs made by other apps with a broken ToUnicode
  table still cannot be read back correctly; that needs OCR.
- Text selection is smooth. Hit testing is a binary search over a prebuilt line
  index and grapheme boundaries are precomputed, instead of scanning every
  character and rebuilding a break iterator on every drag event. Handles now
  track the finger from wherever they were grabbed.
- Links work again, and a single tap toggles the top bar again: the selection
  overlay used to swallow every tap and now forwards them when nothing is
  selected.
- Pages no longer jump when the top bar appears or disappears; the space it
  needs is always reserved and only the bar itself fades.
- Create Text PDF: the title scrolls away with the text and comes back at the
  top, and the body can no longer draw over it.

# Alal PDF v0.2.0-beta

## Myanmar text

- Zawgyi is now detected with Google's `myanmar-tools` n-gram model instead of
  hand written rules. Unicode Burmese pages are no longer mistaken for Zawgyi
  and mangled; text on screen is never silently rewritten, and a Zawgyi page
  offers an explicit "show Unicode" switch.
- Pyidaungsu is used for the whole interface, so Burmese renders correctly even
  on devices that ship an old Myanmar font.
- Text written into a PDF (both Create PDF and Edit PDF) is shaped by Android's
  text engine and embedded as real vector text, so Burmese keeps its stacked
  and reordered glyphs.

## Reading

- Text selection is now character level, backed by PDFium. Both handles can be
  dragged after the first long press, they have a full sized touch target,
  dragging past the edge scrolls the page, and Myanmar syllables are never cut
  in half.
- The reader top bar no longer covers the first lines of a page.

## Creating and editing

- Create Text PDF: the title bar slides away while writing and returns when the
  page is scrolled back up.
- Edit PDF: tapping an existing paragraph opens it for editing in place, keeping
  its position, width and text size. "Rewrite page" replaces the whole page text
  when the original layout does not matter.

## Requirements

Minimum Android version: Android 8.0 (API 26). Distributed as an APK; not
available through Google Play Store.

## Installation

Download the APK asset from this GitHub Release, enable **Install unknown apps**
for the browser or file manager that opens it, then open it and confirm. The
`applicationId` is unchanged and the `versionCode` is higher, so it installs as
an update over the previous build.

## Known limitations

OCR, text reflow across pages, annotations, signatures and PDF merge/split are
not included. Rewriting a page replaces its text layer, so the original layout
of that page is not preserved.

## Privacy

Alal PDF is offline only, requests no `INTERNET` permission, and does not send
PDF content off the device.

## Third-party dependencies and licenses

Kotlin, Kotlin Coroutines, AndroidX, Jetpack Compose, Material 3, Room,
PDFBox-Android, PDFium (`io.legere:pdfiumandroid`), Google myanmar-tools, the
Android Gradle Plugin and Gradle. See `THIRD_PARTY_NOTICES.md` for the licences,
including the SIL Open Font License covering the bundled Pyidaungsu fonts.
