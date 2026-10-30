#!/usr/bin/env python3
"""Synthesize a PDF that looks like a Microsoft Word export of Myanmar text:
visual glyph order, no ActualText, Identity-H CIDFontType2 subset that keeps the
original glyph ids and a small cmap but no GSUB/GPOS/post names, and a ToUnicode
CMap that is either 'word' (broken, built positionally per cluster, first use wins,
unassigned glyphs -> space) or 'visual' (correct per glyph, still visual order).
Usage: gen_word_style_fixture.py MODE FONT.ttf [BOLD.ttf] LINES.txt OUT.pdf
Lines starting with '# ' are rendered with BOLD; '@' marks a Latin run in Helvetica: @AlalPDF 2.0@.
Needs: fontTools, uharfbuzz.
"""
import sys, io, re, zlib
import uharfbuzz as hb
from fontTools.ttLib import TTFont
from fontTools import subset
from gsubrev import build

class Face:
    def __init__(self, path, tag):
        self.path=path; self.tag=tag
        self.tt=TTFont(path); self.upem=self.tt['head'].unitsPerEm
        self.hbfont=hb.Font(hb.Face(hb.Blob.from_file_path(path)))
        _,self.order,self.uni=build(path)
        self.used=set([0]); self.chars=set(); self.tounicode={}
    def shape(self, text):
        buf=hb.Buffer(); buf.add_str(text); buf.guess_segment_properties()
        hb.shape(self.hbfont, buf, {})
        return buf.glyph_infos, buf.glyph_positions
def word_tounicode(face, text, infos, mode):
    # group glyphs by cluster (cluster = char index of first char)
    cl=[i.cluster for i in infos]
    starts=sorted(set(cl)); bounds=starts+[len(text)]
    for k,s in enumerate(starts):
        chars=text[s:bounds[k+1]]
        gl=[i.codepoint for i in infos if i.cluster==s]
        for g in gl: face.used.add(g)
        face.chars.update(chars)
        if mode=='visual':
            for g in gl: face.tounicode.setdefault(g, face.uni.get(face.order[g],''))
            continue
        # 'word': char i -> glyph min(i, m-1); glyphs without chars -> ' '
        m=len(gl); assign=[[] for _ in gl]
        for i,c in enumerate(chars): assign[min(i,m-1)].append(c)
        for g,a in zip(gl,assign): face.tounicode.setdefault(g, ''.join(a) or ' ')
def subset_font(face):
    opts=subset.Options(); opts.retain_gids=True; opts.layout_features=[]; opts.layout_closure=False
    opts.drop_tables+=['GSUB','GPOS','GDEF','BASE','JSTF','MATH','DSIG']; opts.glyph_names=False
    opts.name_IDs=['*']; opts.notdef_outline=True; opts.hinting=True
    s=subset.Subsetter(opts)
    s.populate(gids=sorted(face.used), unicodes=[ord(c) for c in face.chars if ord(c)>=0x20])
    tt=TTFont(face.path, recalcTimestamp=False); s.subset(tt)
    tt['post'].formatType=3.0
    b=io.BytesIO(); tt.save(b); return b.getvalue()
def tounicode_cmap(m):
    out=['/CIDInit /ProcSet findresource begin','12 dict begin','begincmap',
         '/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def',
         '/CMapName /Adobe-Identity-UCS def','/CMapType 2 def',
         '1 begincodespacerange','<0000> <FFFF>','endcodespacerange']
    items=sorted((k,v) for k,v in m.items() if v)
    for i in range(0,len(items),100):
        chunk=items[i:i+100]; out.append('%d beginbfchar'%len(chunk))
        for g,t in chunk:
            out.append('<%04X> <%s>'%(g, t.encode('utf-16-be').hex().upper() if t else ''))
        out.append('endbfchar')
    out+=['endcmap','CMapName currentdict /CMap defineresource pop','end','end']
    return '\n'.join(out).encode()
def main():
    mode=sys.argv[1]; args=sys.argv[2:]
    reg=Face(args[0],'F1'); bold=Face(args[1],'F2') if len(args)==4 else None
    lines=[l.rstrip('\n') for l in open(args[-2],encoding='utf-8')]
    out=args[-1]
    size=14; lead=26; W,H=595,842; margin=56
    pages=[]; ops=[]; y=H-margin
    expected=[]
    for raw in lines:
        if not raw.strip(): continue
        face=reg; text=raw
        if raw.startswith('# ') and bold: face=bold; text=raw[2:]
        expected.append(text.replace('@',''))
        if y<margin+lead: pages.append(ops); ops=[]; y=H-margin
        x=margin
        for k,run in enumerate(text.split('@')):
            if not run: continue
            if k%2==1:  # Latin run
                ops.append('BT /F9 %g Tf 1 0 0 1 %.2f %.2f Tm (%s) Tj ET'%(size,x,y,run.replace('\\','\\\\').replace('(','\\(').replace(')','\\)')))
                x+=0.55*size*len(run); continue
            infos,pos=face.shape(run)
            word_tounicode(face, run, infos, mode)
            sc=size/face.upem; pen=x
            parts=['BT /%s %g Tf'%(face.tag,size)]
            for inf,p in zip(infos,pos):
                gx=pen+p.x_offset*sc; gy=y+p.y_offset*sc
                parts.append('1 0 0 1 %.2f %.2f Tm <%04X> Tj'%(gx,gy,inf.codepoint))
                pen+=p.x_advance*sc
            parts.append('ET'); ops.append(' '.join(parts)); x=pen
        y-=lead
    pages.append(ops)
    # --- write PDF
    objs=[]
    def add(o): objs.append(o); return len(objs)
    def stream(d, data, compress=True):
        if compress: data=zlib.compress(data); d=d+' /Filter /FlateDecode'
        return ('<< %s /Length %d >>\nstream\n'%(d,len(data))).encode()+data+b'\nendstream'
    catalog=add(None); pagesobj=add(None)
    fontrefs={}
    for face in [f for f in (reg,bold) if f]:
        ff=subset_font(face)
        ffo=add(stream('/Length1 %d'%len(ff), ff))
        tt=face.tt; hmtx=tt['hmtx']; sc=1000/face.upem
        name='ABCDE%s+%s'%('F' if face is reg else 'G', tt['name'].getDebugName(6))
        fd=add(('<< /Type /FontDescriptor /FontName /%s /Flags 32 /ItalicAngle 0 /Ascent %d /Descent %d /CapHeight %d /StemV 80 /FontBBox [%d %d %d %d] /FontFile2 %d 0 R >>'%(
            name, tt['hhea'].ascent*sc, tt['hhea'].descent*sc, tt['hhea'].ascent*sc,
            tt['head'].xMin*sc, tt['head'].yMin*sc, tt['head'].xMax*sc, tt['head'].yMax*sc, ffo)).encode())
        w=' '.join('%d [%d]'%(g, round(hmtx[face.order[g]][0]*sc)) for g in sorted(face.used))
        cid=add(('<< /Type /Font /Subtype /CIDFontType2 /BaseFont /%s /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor %d 0 R /CIDToGIDMap /Identity /DW 1000 /W [%s] >>'%(name,fd,w)).encode())
        tu=add(stream('', tounicode_cmap(face.tounicode)))
        fontrefs[face.tag]=add(('<< /Type /Font /Subtype /Type0 /BaseFont /%s /Encoding /Identity-H /DescendantFonts [%d 0 R] /ToUnicode %d 0 R >>'%(name,cid,tu)).encode())
    fontrefs['F9']=add(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>')
    res='<< /Font << %s >> >>'%' '.join('/%s %d 0 R'%(k,v) for k,v in fontrefs.items())
    kids=[]
    for ops in pages:
        c=add(stream('', '\n'.join(ops).encode()))
        kids.append(add(('<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %d %d] /Resources %s /Contents %d 0 R >>'%(pagesobj,W,H,res,c)).encode()))
    objs[catalog-1]=('<< /Type /Catalog /Pages %d 0 R >>'%pagesobj).encode()
    objs[pagesobj-1]=('<< /Type /Pages /Kids [%s] /Count %d >>'%(' '.join('%d 0 R'%k for k in kids),len(kids))).encode()
    bio=io.BytesIO(); bio.write(b'%PDF-1.7\n%\xe2\xe3\xcf\xd3\n'); offs=[]
    for i,o in enumerate(objs,1):
        offs.append(bio.tell()); bio.write(b'%d 0 obj\n'%i+o+b'\nendobj\n')
    x=bio.tell(); bio.write(b'xref\n0 %d\n0000000000 65535 f \n'%(len(objs)+1))
    for o in offs: bio.write(b'%010d 00000 n \n'%o)
    bio.write(('trailer\n<< /Size %d /Root %d 0 R /Info << /Producer (alalpdf synthetic word-style fixture) >> >>\nstartxref\n%d\n%%%%EOF\n'%(len(objs)+1,catalog,x)).encode())
    open(out,'wb').write(bio.getvalue())
    if len(sys.argv)>0: open(re.sub(r'\.pdf$','',out)+'.txt','w',encoding='utf-8').write('\n'.join(expected)+'\n')
    print('wrote',out,'pages',len(pages),'lines',len(expected),file=sys.stderr)
main()
