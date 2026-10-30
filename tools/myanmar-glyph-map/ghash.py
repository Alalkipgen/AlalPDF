import hashlib
from fontTools.ttLib import TTFont
def glyph_points(f, gn):
    g=f['glyf'][gn]
    if g.numberOfContours==0: return None
    coords,endPts,flags=g.getCoordinates(f['glyf'])
    return [(int(round(x)),int(round(y))) for x,y in coords], list(endPts), [fl & 1 for fl in flags]
def ghash(f, gn):
    p=glyph_points(f,gn)
    if p is None: return None
    pts,ends,on=p
    s='g1|%d|%s|%s'%(len(ends),','.join(map(str,ends)),';'.join('%d,%d,%d'%(o,x,y) for (x,y),o in zip(pts,on)))
    return hashlib.sha256(s.encode()).hexdigest()[:16]
if __name__=='__main__':
    import sys
    f=TTFont(sys.argv[1]); 
    for i,gn in enumerate(f.getGlyphOrder()):
        print(i, ghash(f,gn) or '-')
