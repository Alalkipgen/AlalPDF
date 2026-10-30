# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

## [0.4.0] - 2026-10-30

### Fixed

- Burmese text on Unicode PDFs is copied, selected and searched correctly:
  Myanmar pages use the PDFBox text layer (glyphs and `/ActualText`).
- Page text is copied as extracted, without a Zawgyi guess.
- Burmese text in Microsoft Word PDFs (broken ToUnicode, no `/ActualText`) is
  copied, selected and searched correctly for Noto Sans Myanmar: glyph text
  comes from the embedded font's `cmap` and a bundled glyph map, and glyphs
  drawn in visual order are put back into logical order.
- Zawgyi PDFs and Burmese text that is already in logical order keep the text
  as extracted instead of being reordered; Zawgyi is copied as Zawgyi.
- PDFium text reads each character by the index of its box.

### Changed

- One file-backed PDFBox document is shared per reader session.
- The whiteout tool warns that the original text stays in the file.
