# Visual (glyph) order -> logical Unicode order for shaped Myanmar text (Burmese subset).
# Syllable = [pre-base glyphs (text starts with U+1031 E or U+103C RA)] + base + following marks.
# Kinzi glyph text (U+1004 U+103A U+1039) is drawn after its base -> emitted before the base.
# Subjoined "U+1039 C" stays right after the base. All other mark chars are emitted in the order
# required by the OpenType/HarfBuzz Myanmar syllable grammar:
#   MY ျ < MR ြ < MW ွ < MH ှ < E ေ < above ိ ီ ဲ < below ု ူ < anusvara ံ < dot below ့ < asat ် < visarga း
# Asat: right after the base if it is drawn before a medial (ယောက်ျား); else after ါ/ာ; else after ့;
# else BEFORE ု/ူ (ကျွန်ုပ်); else last before း.
E='\u1031'; RA='\u103c'; KINZI='\u1004\u103a\u1039'; ASAT='\u103a'
W={0x103B:3,0x103C:4,0x103D:5,0x103E:6,0x1031:7,0x102D:8,0x102E:8,0x1032:8,0x102F:9,0x1030:9,0x1036:11,0x1037:12,0x103A:13,0x102B:10,0x102C:10,0x1038:16}
def is_base(c):
    o=ord(c)
    if not (0x1000<=o<=0x109F): return True          # non-Myanmar: treat as its own unit
    return (0x1000<=o<=0x102A) or o==0x103F or (0x1040<=o<=0x104F) or (0x1050<=o<=0x1055) or o in (0x105A,0x105B,0x105C,0x105D,0x1061,0x1065,0x1066,0x106E,0x106F,0x1070,0x1075,0x1076,0x1077,0x1078,0x1079,0x107A,0x107B,0x107C,0x107D,0x107E,0x107F,0x1080,0x1081,0x108E,0x1090,0x1091,0x1092,0x1093,0x1094,0x1095,0x1096,0x1097,0x1098,0x1099)
def build_syllable(pre, base, marks, kinzi):
    sub=[]; rest=[]
    for m in marks:
        (sub if m.startswith('\u1039') else rest).append(m)
    chars=[c for m in pre+rest for c in m]
    s=set(chars)
    post=[c for m in rest for c in m]            # post-base mark chars in visual order
    first_med=next((i for i,c in enumerate(post) if c in '\u103b\u103c\u103d\u103e'),None)
    if ASAT in post and first_med is not None and post.index(ASAT)<first_med: wa=2.5   # ယောက်ျား: C + asat + medial
    elif '\u102b' in s or '\u102c' in s: wa=13          # ော် ါ် (asat after aa)
    elif '\u1037' in s: wa=12.5                        # င့်
    elif '\u102f' in s or '\u1030' in s: wa=8.5         # န်ု
    else: wa=13
    chars.sort(key=lambda c: wa if c==ASAT else W.get(ord(c),15))
    return ''.join(kinzi)+base+''.join(sub)+''.join(chars)
def reorder(glyphs):
    out=[]; syl=None   # [pre, base, marks, kinzi]
    def flush():
        nonlocal syl
        if syl is None: return
        pre,base,marks,kinzi=syl
        out.append(''.join(pre)+''.join(kinzi)+''.join(marks) if base is None else build_syllable(pre,base,marks,kinzi))
        syl=None
    for s in glyphs:
        if not s: continue
        c0=s[0]
        if not ('\u1000'<=c0<='\u109f'):
            flush(); out.append(s); continue
        if c0 in (E,RA):
            if syl is not None and syl[1] is not None: flush()
            if syl is None: syl=[[],None,[],[]]
            syl[0].append(s); continue
        if s.startswith(KINZI):
            if syl is None: syl=[[],None,[],[]]
            syl[3].append(KINZI)
            if len(s)>3: syl[2].append(s[3:])
            continue
        if is_base(c0):
            if syl is not None and syl[1] is None and (syl[0] or syl[3]) and not syl[2]:
                syl[1]=c0
            else:
                flush(); syl=[[],c0,[],[]]
            if len(s)>1: syl[2].append(s[1:])
            continue
        if syl is None: syl=[[],None,[],[]]
        syl[2].append(s)
    flush()
    return ''.join(out)
