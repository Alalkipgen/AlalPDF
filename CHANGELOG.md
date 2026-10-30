# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

### Fixed

- Burmese text in Microsoft Word PDFs (broken ToUnicode, no `/ActualText`) is
  copied, selected and searched correctly for Noto Sans Myanmar: glyph text
  comes from the embedded font's `cmap` and a bundled glyph map, and glyphs
  drawn in visual order are put back into logical order.
