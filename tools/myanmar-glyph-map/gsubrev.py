from fontTools.ttLib import TTFont
def build(path):
    f=TTFont(path); order=f.getGlyphOrder()
    uni={}
    for t in f['cmap'].tables:
        if t.isUnicode():
            for cp,gn in t.cmap.items():
                if gn not in uni or len(chr(cp))<len(uni[gn]): uni.setdefault(gn,chr(cp))
    lookups=f['GSUB'].table.LookupList.Lookup
    subs=[]
    for lk in lookups:
        for st in lk.SubTable:
            t=lk.LookupType
            if t==7: t=st.ExtSubTable.LookupType; st=st.ExtSubTable
            subs.append((t,st))
    changed=True
    while changed:
        changed=False
        for t,st in subs:
            if t==1:
                for a,b in st.mapping.items():
                    if a in uni and b not in uni: uni[b]=uni[a]; changed=True
            elif t==2:
                for a,outs in st.mapping.items():
                    if a not in uni or not outs: continue
                    unm=[o for o in outs if o not in uni]
                    if not unm: continue
                    rem=uni[a]
                    for o in outs:
                        if o in uni and uni[o] and uni[o] in rem: rem=rem.replace(uni[o],'',1)
                    glyf=f['glyf']
                    carrier=next((o for o in unm if glyf[o].numberOfContours!=0), unm[0])
                    for o in unm: uni[o]= rem if o==carrier else ''
                    changed=True
            elif t==3:
                for a,alts in st.alternates.items():
                    if a in uni:
                        for b in alts:
                            if b not in uni: uni[b]=uni[a]; changed=True
            elif t==8:
                for a,b in zip(st.Coverage.glyphs, st.Substitute):
                    if a in uni and b not in uni: uni[b]=uni[a]; changed=True
            elif t==4:
                for first,ligs in st.ligatures.items():
                    for lg in ligs:
                        comps=[first]+list(lg.Component)
                        if lg.LigGlyph not in uni and all(c in uni for c in comps):
                            uni[lg.LigGlyph]=''.join(uni[c] for c in comps); changed=True
    for gn in order:
        if gn not in uni and '.' in gn and gn.split('.')[0] in uni:
            uni[gn]=uni[gn.split('.')[0]]
    return f,order,uni
if __name__=='__main__':
    import sys
    f,order,uni=build(sys.argv[1])
    for gid in [int(x,16) for x in sys.argv[2:]]:
        gn=order[gid]; print(hex(gid),gn,repr(uni.get(gn)),[hex(ord(c)) for c in uni.get(gn,'')])
    print('mapped',len(uni),'of',len(order))
    missing=[g for g in order if g not in uni]
    print('unmapped:',missing)
