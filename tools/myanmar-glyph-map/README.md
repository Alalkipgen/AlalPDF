# Myanmar glyph map (Word PDFs with a broken ToUnicode)

Microsoft Word's "Save as PDF" writes Burmese text as an Identity-H CIDFontType2
subset (original glyph ids, a small `cmap` with the typed characters, no
GSUB/GPOS, no glyph names), in visual glyph order, with **no `/ActualText`** and
a **wrong ToUnicode CMap**: it is filled per cluster by position, first use
wins. So the medial-ra glyph maps to မ and the မ glyph to ြ, the ေ glyph to
ဘ or ရ, several base glyphs to a space, and marks get extra characters
(`ေး`, `ြုံ`). Every reader that trusts ToUnicode (pdftotext, PDFium, MuPDF,
PDFBox) shows broken Burmese for these files.

The fix the app uses:

1. **Detect** a broken ToUnicode per font: the embedded font has a Unicode
   `cmap`, and for a glyph present in both, the cmap gives a Myanmar code point
   (U+1000–U+109F) that differs from the ToUnicode text.
2. **Map glyphs** of such fonts: embedded `cmap` reversed → else
   `glyph_map_v1.tsv` by outline hash → else empty outline = no text → else the
   ToUnicode text (fallback).
3. **Reorder** visual → logical per syllable for Myanmar text that has no
   `/ActualText` (see `myreorder.py`).

## Files

| File | What |
|---|---|
| `fonts.txt` | SHA-256 and URL of the 17 Noto Sans Myanmar / Bold / UI files (v1.100, 2.000, 2.001 x2, 2.103/2.105, 2.107; SIL OFL 1.1) |
| `build_glyph_map.sh` | `bash build_glyph_map.sh`: downloads them, checks SHA-256, runs `gen_myanmar_glyph_map.py` |
| `gen_myanmar_glyph_map.py`, `gsubrev.py` | glyph → text from each font's cmap plus GSUB reversed (types 1, 2, 3, 4, 7, 8; fixpoint), keyed by outline hash; hashes that map to different texts are dropped (6) |
| `ghash.py` | the outline hash (see below) |
| `gen_word_style_fixture.py` | makes the synthetic Word-style fixtures (HarfBuzz shaping + fontTools subset + positional ToUnicode like Word) |
| `myreorder.py` | reference visual → logical reorder |
| `reference_extract.py` | reference extractor (PyMuPDF), used to measure the results below |

## Outline hash `g1`

For glyph `gid` of the embedded TrueType font, take the resolved points
(composites resolved), then

    s = "g1|" + contourCount + "|" + endPts joined by "," + "|" +
        for each point "onCurve,x,y" joined by ";"     (onCurve = flags & 1)
    hash = lowercase hex of the first 8 bytes of SHA-256(UTF-8 s)

Glyphs with no contours have no hash (spaces). fontTools and Apache FontBox
2.0.27 (`GlyphData.getDescription()`: `getContourCount`, `getEndPtOfContours`,
`getPointCount`, `getFlags`, `getXCoordinate`, `getYCoordinate`) give the same
hash for all 610 glyphs of Noto Sans Myanmar 2.001 and of the subset Word
embedded. PdfBox-Android's `com.tom_roush.fontbox` is the same code.

## Results (CER against the source text)

| PDF | ToUnicode as-is (+ #17 assembler) | this fix | no table (cmap + ToUnicode fallback) |
|---|---|---|---|
| `word_noto2001` (synthetic, broken ToUnicode) | 18.8 % | 0 % | – |
| `visual_noto2001` (synthetic, correct ToUnicode, visual order) | 11.9 % | 0 % | – |
| `word_noto2107` (synthetic, newer Noto) | – | 0 % | – |
| real Word article 1 (9 pages, not in repo) | 36.4 % | 0 % | 5.2 % |
| real Word article 2 (15 pages, not in repo) | 30.8 % | 0 % | 5.5 % |

For the real articles the expected text was checked by shaping it again with
HarfBuzz and the same Noto 2.001 font: all 1,722 font runs give exactly the
glyph ids in the PDF.

Other fonts (Myanmar Text, Pyidaungsu, Padauk) are not in the table yet: add
their files to `fonts.txt` and rerun `build_glyph_map.sh`.
