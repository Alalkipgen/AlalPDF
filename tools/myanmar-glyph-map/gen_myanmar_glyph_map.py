#!/usr/bin/env python3
"""Build myanmar_glyph_map.tsv: glyph-outline hash -> Unicode text.

Usage: gen_myanmar_glyph_map.py OUT.tsv FONT.ttf [FONT.ttf ...]
Needs: fontTools.  Hash = first 16 hex chars of SHA-256 over
  "g1|<contours>|<endPts,...>|<on,x,y;...>"  (resolved points, on = flag & 1)
which must stay identical to the app's Kotlin implementation.
"""
import sys, collections
from fontTools.ttLib import TTFont
from gsubrev import build
from ghash import ghash
def main(out, fonts):
    seen = collections.defaultdict(set); src = collections.defaultdict(set)
    for p in fonts:
        f, order, uni = build(p)
        tag = '%s %.3f' % (f['name'].getDebugName(4), f['head'].fontRevision)
        for gn in order:
            h = ghash(f, gn)
            if h is None or gn not in uni: continue
            seen[h].add(uni[gn]); src[h].add(tag)
    rows = []; dropped = 0
    for h, texts in seen.items():
        if len(texts) != 1: dropped += 1; continue
        t = next(iter(texts))
        rows.append((h, ' '.join('%04X' % ord(c) for c in t) if t else '-'))
    rows.sort()
    with open(out, 'w') as fh:
        fh.write('# myanmar-glyph-map v1 hash=g1 (sha256/64 of resolved outline); value = code points, "-" = no text\n')
        fh.write('# fonts: ' + '; '.join(sorted({ '%s' % t for s in src.values() for t in s })) + '\n')
        for h, v in rows: fh.write('%s\t%s\n' % (h, v))
    print('entries', len(rows), 'dropped ambiguous', dropped, file=sys.stderr)
if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2:])
