"""Reference extractor (Python, PyMuPDF + fontTools) for Word-style Myanmar PDFs.
Usage: GLYPH_MAP=../../app/src/main/assets/myanmar/glyph_map_v1.tsv python3 reference_extract.py FILE.pdf
"""
import pymupdf as fitz, sys, re, io
from fontTools.ttLib import TTFont
from ghash import ghash
from myreorder import reorder
def parse_tounicode(s):
    m={}
    def hx(h): 
        b=bytes.fromhex(h); return b.decode('utf-16-be')
    for blk in re.findall(r'beginbfchar(.*?)endbfchar',s,re.S):
        for a,b in re.findall(r'<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]*)>',blk): m[int(a,16)]=hx(b)
    for blk in re.findall(r'beginbfrange(.*?)endbfrange',s,re.S):
        for a,b,rest in re.findall(r'<([0-9A-Fa-f]+)>\s*<([0-9A-Fa-f]+)>\s*(\[[^\]]*\]|<[0-9A-Fa-f]+>)',blk):
            a=int(a,16);b=int(b,16)
            if rest.startswith('['):
                vals=re.findall(r'<([0-9A-Fa-f]*)>',rest)
                for i,v in enumerate(vals): m[a+i]=hx(v)
            else:
                v=int(rest[1:-1],16)
                for i in range(b-a+1):
                    cp=v+i
                    m[a+i]=chr(cp) if cp<0x10000 else ''
    return m
def fontinfo(d,xref):
    o=d.xref_object(xref)
    tu=int(re.search(r'/ToUnicode (\d+)',o).group(1))
    desc=int(re.search(r'/DescendantFonts \[?\s*(\d+)',o).group(1))
    dd=d.xref_object(desc)
    if '/BaseFont' not in dd:
        desc=int(re.search(r'\[\s*(\d+) 0 R',dd).group(1)); dd=d.xref_object(desc)
    fd=int(re.search(r'/FontDescriptor (\d+)',dd).group(1))
    ff=int(re.search(r'/FontFile2 (\d+)',d.xref_object(fd)).group(1))
    f=TTFont(io.BytesIO(d.xref_stream(ff)))
    rev={}
    for t in f['cmap'].tables:
        if t.platformID==3:
            for cp,gn in t.cmap.items(): rev.setdefault(f.getGlyphID(gn),chr(cp))
    return parse_tounicode(d.xref_stream(tu).decode('latin1')), rev, f

import os
TABLE={}
for ln in open(os.environ.get('GLYPH_MAP','glyph_map_v1.tsv'),encoding='utf-8'):
    if ln.startswith('#'): continue
    h,v=ln.rstrip('\n').split('\t'); TABLE[h]='' if v=='-' else ''.join(chr(int(x,16)) for x in v.split())
def font_maps(d):
    """xref of Type0 font -> (broken, gid->text)"""
    res={}
    for pno in range(len(d)):
        for (xref,ext,typ,base,name,enc,*_) in d.get_page_fonts(pno):
            if xref in res or typ!='Type0': continue
            try: tu,rev,tt=fontinfo(d,xref)
            except Exception as e: res[xref]=(False,None,{}); continue
            my=lambda c: c and '\u1000'<=c[0]<='\u109f'
            broken=any(my(rev[g]) and g in tu and tu[g]!=rev[g] for g in rev)
            order=tt.getGlyphOrder()
            stats={'cmap':0,'table':0,'fallback':0}
            def text(g, tu=tu, rev=rev, tt=tt, order=order, stats=stats):
                if g in rev and rev[g]>=' ': stats['cmap']+=1; return rev[g]
                if g>=len(order): return tu.get(g,'')
                h=ghash(tt,order[g])
                if h is None: return ''          # empty outline (space-like glyph)
                if h in TABLE: stats['table']+=1; return TABLE[h]
                stats['fallback']+=1; return tu.get(g,'')
            res[xref]=(broken,text,stats)
    return res
def extract(pdf):
    d=fitz.open(pdf); maps=font_maps(d); pages=[]
    namemap={}
    for pno in range(len(d)):
        for (xref,ext,typ,base,name,*_) in d.get_page_fonts(pno): namemap[(pno,base)]=xref
    for pno,page in enumerate(d):
        lines=[]; cur=None; lasty=None
        for sp in page.get_texttrace():
            fn=sp['font']; size=sp['size']
            xref=next((x for (p,b),x in namemap.items() if p==pno and (b==fn or b.endswith('+'+fn) or fn.endswith(b.split('+')[-1]))),None)
            broken,text,_=maps.get(xref,(False,None,{}))
            gl=[]
            for ch in sp['chars']:
                ucs,gid,org=ch[0],ch[1],ch[2]
                if broken:
                    if gid<0: continue
                    s=text(gid)
                else:
                    s=chr(ucs) if ucs>0 and ucs!=0xFFFD else ''
                    if gid<0 and gl: gl[-1]=(gl[-1][0],gl[-1][1],gl[-1][2]+s); continue
                gl.append((org[0],org[1],s))
            if not gl: continue
            y=gl[0][1]
            if cur is None or abs(y-lasty)>0.5*size: cur=[]; lines.append(cur)
            lasty=y; cur.extend(gl)
        pages.append('\n'.join(re.sub(' +',' ',reorder([g[2] for g in ln])).strip() for ln in lines))
    st={x:(m[0],m[2]) for x,m in maps.items()}
    return pages, st
if __name__=='__main__':
    pages,st=extract(sys.argv[1])
    for x,(broken,stats) in st.items():
        if stats: print('font xref %d: ToUnicode broken=%s %s'%(x,broken,stats),file=sys.stderr)
    print('\n\f\n'.join(pages))
