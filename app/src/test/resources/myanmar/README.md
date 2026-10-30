# Myanmar text fixtures

One-page PDFs with known Burmese text, used by `MyanmarFixtureTest`.

| File | Made with | Embedded font subsets |
|---|---|---|
| `chrome_noto.pdf.b64` | Chrome 153 (Skia/PDF m153), printing `source/chrome_noto.html` | Noto Sans Myanmar Regular and Bold, Times New Roman (Latin words) |
| `libreoffice_noto.pdf.b64` | LibreOffice 26.8 Writer, converting a DOCX with the same text | Noto Sans Myanmar Regular, DejaVu Serif, DejaVu Sans, Noto Color Emoji |

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

## Why base64

The PDFs are committed through a text-only API, so each one is stored as
base64 in 76-column lines. Decoding gives the original file byte for byte,
and the test checks the SHA-256 before using it:

```
base64 -d chrome_noto.pdf.b64 > chrome_noto.pdf
# ee8d446b8937e519c7d1fbc9edce242ecaba3171484d4bb8ca641aaa8812de86
base64 -d libreoffice_noto.pdf.b64 > libreoffice_noto.pdf
# 8c837b9c40a24b1ca9ddb7a5776ead3edf8428d4a7feb733a084079a713ae37d
```

## Regenerating

```
# Noto Sans Myanmar Regular and Bold .ttf files next to the HTML
chromium --headless=new --no-pdf-header-footer --print-to-pdf=chrome_noto.pdf source/chrome_noto.html
soffice --headless --convert-to pdf libreoffice_noto.docx
```

A regenerated file differs in bytes (dates, font subset tags), so update the
SHA-256 in the test as well.
