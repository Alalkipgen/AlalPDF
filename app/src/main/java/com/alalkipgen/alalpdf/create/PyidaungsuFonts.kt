package com.alalkipgen.alalpdf.create
import android.content.Context
import android.graphics.Typeface
import android.text.*
import android.text.style.*
import androidx.core.content.res.ResourcesCompat
import com.alalkipgen.alalpdf.R
internal object PyidaungsuFonts{fun regular(c:Context)=ResourcesCompat.getFont(c,R.font.pyidaungsu_regular)?:Typeface.DEFAULT;fun bold(c:Context)=ResourcesCompat.getFont(c,R.font.pyidaungsu_bold)?:Typeface.DEFAULT_BOLD;fun styled(c:Context,s:Spanned)=SpannableStringBuilder(s).also{t->t.getSpans(0,t.length,StyleSpan::class.java).forEach{o->val a=t.getSpanStart(o);val b=t.getSpanEnd(o);val f=t.getSpanFlags(o);t.removeSpan(o);t.setSpan(S(regular(c),bold(c),o.style),a,b,f)}}}
private class S(val r:Typeface,val b:Typeface,val s:Int):MetricAffectingSpan(){override fun updateDrawState(p:TextPaint)=x(p);override fun updateMeasureState(p:TextPaint)=x(p);fun x(p:TextPaint){p.typeface=if(s==1||s==3)b else r;p.textSkewX=if(s==2||s==3)-.25f else 0f}}
