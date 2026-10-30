# Myanmar text fixtures

One-page PDFs with known Burmese text, used by `MyanmarFixtureTest` and
`MyanmarGlyphTextTest`.

| File | Made with | Embedded font subsets |
|---|---|---|
| `chrome_noto.pdf.b64` | Chrome 153 (Skia/PDF m153), printing `source/chrome_noto.html` | Noto Sans Myanmar Regular and Bold, Times New Roman (Latin words) |
| `libreoffice_noto.pdf.b64` | LibreOffice 26.8 Writer, converting a DOCX with the same text | Noto Sans Myanmar Regular, DejaVu Serif, DejaVu Sans, Noto Color Emoji |
| `word_noto2001.pdf.b64` | `tools/myanmar-glyph-map/gen_word_style_fixture.py word`, from `source/word_style_lines.txt` | Noto Sans Myanmar 2.001 Regular and Bold |
| `word_noto2107.pdf.b64` | the same, with Noto Sans Myanmar 2.107 | Noto Sans Myanmar 2.107 Regular and Bold |
| `visual_noto2001.pdf.b64` | `gen_word_style_fixture.py visual`, same text and fonts as `word_noto2001` | Noto Sans Myanmar 2.001 Regular and Bold |

- The text was written for these tests. `expected/<name>.txt` is the exact
  source text, one paragraph per line: medials ျ ြ ွ ှ, ော်, kinzi
  (သင်္ချိုင်း, မင်္ဂလာ), stacked consonants, ို့, ုံ, ှုံ့, ဿ, ဥ, ၎င်း, Myanmar
  digits, ၊ ။, English words and digits, and one bold line.
- Noto fonts are under the SIL Open Font License 1.1, DejaVu fonts under the
  DejaVu (Bitstream Vera) license. The other subsets are what Chrome and
  LibreOffice embed in any exported document.
- Chrome writes one `/ActualText` span per reordered cluster into a CID font
  with ToUnicode. LibreOffice uses simple TrueType fonts, draws some runs out
  of order (spaces of a line first) and also uses `/ActualText`.
- The three Word-style PDFs are synthetic, made the way Microsoft Word's
  "Save as PDF" writes Burmese: the text of `source/word_style_lines.txt`
  (24 lines; `# ` starts a bold line, `@...@` is a Latin run; the three
  `expected/` files are that text without the marks) is shaped with
  HarfBuzz, the font is subset with fontTools into an Identity-H
  CIDFontType2 that keeps the original glyph ids and a small `cmap`, and the
  glyphs are drawn in visual order without `/ActualText`. `word` writes
  Word's broken ToUnicode, filled per cluster by position (first use wins);
  `visual` writes a correct ToUnicode per glyph. Latin words use Helvetica,
  not embedded. See `tools/myanmar-glyph-map/README.md`. The fonts are the
  Noto Sans Myanmar files listed in `tools/myanmar-glyph-map/fonts.txt`
  (SIL OFL 1.1).
- `reorder_vectors.tsv`: 61 rows `expected<TAB>glyph texts in visual order
  joined by |`, from HarfBuzz and Noto Sans Myanmar 2.001, used by
  `MyanmarReorderTest`; `tools/myanmar-glyph-map/myreorder.py` passes them too.

## Why base64

The PDFs are committed through a text-only API, so each one is stored as
base64 in 76-column lines. Decoding gives the original file byte for byte,
and the test checks the SHA-256 before using it:

```
base64 -d chrome_noto.pdf.b64 > chrome_noto.pdf
# ee8d446b8937e519c7d1fbc9edce242ecaba3171484d4bb8ca641aaa8812de86
base64 -d libreoffice_noto.pdf.b64 > libreoffice_noto.pdf
# 8c837b9c40a24b1ca9ddb7a5776ead3edf8428d4a7feb733a084079a713ae37d
base64 -d word_noto2001.pdf.b64 > word_noto2001.pdf
# 1399ac3a9e43a6503c556044e2b400f9f2680e6c8438a7d67bcd8120a06d4cf0
base64 -d word_noto2107.pdf.b64 > word_noto2107.pdf
# a3efb0dd52f0517537f9f1bdb1c713136b8679224137e3c5a1a46f454e535469
base64 -d visual_noto2001.pdf.b64 > visual_noto2001.pdf
# 60425aac2d478a32c352f7b1dc7f1b9e36e7e07aae25a3b9ff908feed8313fda
```

## Regenerating

```
# Noto Sans Myanmar Regular and Bold .ttf files next to the HTML
chromium --headless=new --no-pdf-header-footer --print-to-pdf=chrome_noto.pdf source/chrome_noto.html
soffice --headless --convert-to pdf libreoffice_noto.docx
# needs fontTools and uharfbuzz; the font files are listed in tools/myanmar-glyph-map/fonts.txt;
# each run also writes the expected text next to the PDF
gen=tools/myanmar-glyph-map/gen_word_style_fixture.py
lines=app/src/test/resources/myanmar/source/word_style_lines.txt
python3 $gen word NotoSansMyanmar-Regular.ttf NotoSansMyanmar-Bold.ttf $lines word_noto2001.pdf
python3 $gen visual NotoSansMyanmar-Regular.ttf NotoSansMyanmar-Bold.ttf $lines visual_noto2001.pdf
```

A regenerated file differs in bytes (dates, font subset tags), so update the
SHA-256 in `MyanmarFixtures` as well.
