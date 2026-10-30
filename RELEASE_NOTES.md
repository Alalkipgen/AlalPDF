# PDF Reader v0.4.0-beta

## Burmese (Myanmar) text

- Copy, text selection and search now give correct Burmese text on Unicode
  PDFs. Myanmar pages use the PDFBox text layer (glyphs and `/ActualText`).
- Microsoft Word PDFs in Noto Sans Myanmar, whose ToUnicode map is broken, are
  copied, selected and searched correctly: the text comes from the embedded
  font and a bundled glyph map, and glyphs drawn in visual order are put back
  into logical order.
- Zawgyi PDFs are copied as Zawgyi, with no conversion to Unicode. Burmese text
  that is already in logical order is kept as extracted.

## Other fixes

- The reader shares one file-backed PDFBox document per session.
- PDFium text reads each character by the index of its box.
- The whiteout tool warns that the original text stays in the file.

## Known limitations

- Word PDFs in Myanmar Text, Pyidaungsu or Padauk still have about 5 % wrong
  characters in copy and search, until those fonts are added to the glyph map.
- Scanned PDFs have no text to copy or search (no OCR yet).

## Version

- Version name: `0.4.0-beta`
- Version code: `17`
- Minimum Android: Android 8.0 (API 26)
- Signed with the same permanent key, so it installs over earlier versions.
