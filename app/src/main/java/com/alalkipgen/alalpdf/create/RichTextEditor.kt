package com.alalkipgen.alalpdf.create

import android.graphics.Color
import android.graphics.Typeface
import android.text.*
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.EditText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat

data class EditorLink(val text: String, val url: String, val start: Int, val end: Int)

@Stable class RichTextController {
    private var view: EditText? = null
    private var changed: (() -> Unit)? = null
    internal fun attach(v: EditText, callback: () -> Unit) { view = v; changed = callback }
    internal fun html() = view?.editableText?.let { HtmlCompat.toHtml(it, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) }.orEmpty()
    fun toggleBold() = toggle(Typeface.BOLD)
    fun toggleItalic() = toggle(Typeface.ITALIC)
    private fun toggle(style: Int): Boolean {
        val v = view ?: return false; val e = v.editableText
        val a = minOf(v.selectionStart, v.selectionEnd).coerceAtLeast(0); val b = maxOf(v.selectionStart, v.selectionEnd).coerceAtMost(e.length)
        if (a >= b) return false
        val spans = e.getSpans(a, b, StyleSpan::class.java).filter { it.style == style }
        val covered = spans.any { e.getSpanStart(it) <= a && e.getSpanEnd(it) >= b }
        if (covered) spans.forEach { s ->
            val x = e.getSpanStart(s); val y = e.getSpanEnd(s); e.removeSpan(s)
            if (x < a) e.setSpan(StyleSpan(style), x, a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (y > b) e.setSpan(StyleSpan(style), b, y, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else e.setSpan(StyleSpan(style), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        changed?.invoke(); return true
    }
    fun selectionLink(): EditorLink? {
        val v = view ?: return null; val e = v.editableText
        val a = minOf(v.selectionStart, v.selectionEnd).coerceAtLeast(0); val b = maxOf(v.selectionStart, v.selectionEnd).coerceAtMost(e.length)
        val end = if (a == b) (a + 1).coerceAtMost(e.length) else b
        val span = e.getSpans(a, end, URLSpan::class.java).firstOrNull()
        if (span != null) { val x = e.getSpanStart(span); val y = e.getSpanEnd(span); return EditorLink(e.subSequence(x, y).toString(), span.url, x, y) }
        return if (a < b) EditorLink(e.subSequence(a, b).toString(), "", a, b) else null
    }
    fun select(link: EditorLink) { view?.setSelection(link.start, link.end) }
    fun apply(link: EditorLink, label: String, url: String) {
        val v = view ?: return; val e = v.editableText; val a = link.start.coerceIn(0, e.length); val b = link.end.coerceIn(a, e.length)
        e.getSpans(a, b.coerceAtLeast((a + 1).coerceAtMost(e.length)), URLSpan::class.java).forEach(e::removeSpan)
        e.replace(a, b, label); if (label.isNotBlank()) e.setSpan(URLSpan(normalizeHttpUrl(url)), a, a + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        v.setSelection((a + label.length).coerceAtMost(e.length)); changed?.invoke()
    }
    fun remove(link: EditorLink) { val e = view?.editableText ?: return; e.getSpans(link.start, link.end, URLSpan::class.java).forEach(e::removeSpan); changed?.invoke() }
}

@Composable fun rememberRichTextController() = remember { RichTextController() }

@Composable fun RichTextEditor(html: String, onChange: (String) -> Unit, controller: RichTextController, modifier: Modifier, onLongLink: (EditorLink) -> Unit) {
    val context = LocalContext.current; val uri = LocalUriHandler.current
    val latestChange by rememberUpdatedState(onChange); val latestLong by rememberUpdatedState(onLongLink)
    val color = MaterialTheme.colorScheme.onSurface.toArgb(); val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    AndroidView(modifier = modifier, factory = {
        EditText(context).apply {
            setBackgroundColor(Color.TRANSPARENT); setTextColor(color); setHintTextColor(hintColor); textSize = 17f; gravity = android.view.Gravity.TOP; hint = "Start writing…"; setPadding(16, 12, 16, 16)
            setText(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)); setSelection(text.length)
            fun emit() = latestChange(controller.html())
            controller.attach(this, ::emit)
            addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit; override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit; override fun afterTextChanged(s: Editable?) = emit() })
            fun at(e: MotionEvent): EditorLink? { val l = layout ?: return null; val line = l.getLineForVertical((e.y + scrollY - totalPaddingTop).toInt().coerceAtLeast(0)); val o = l.getOffsetForHorizontal(line, e.x + scrollX - totalPaddingLeft); val s = editableText.getSpans(o, (o + 1).coerceAtMost(editableText.length), URLSpan::class.java).firstOrNull() ?: return null; val a = editableText.getSpanStart(s); val b = editableText.getSpanEnd(s); return EditorLink(editableText.subSequence(a, b).toString(), s.url, a, b) }
            var pressed: EditorLink? = null
            val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() { override fun onDown(e: MotionEvent) = true; override fun onSingleTapConfirmed(e: MotionEvent): Boolean { pressed?.let { runCatching { uri.openUri(normalizeHttpUrl(it.url)) }; return true }; return false }; override fun onLongPress(e: MotionEvent) { pressed?.let { setSelection(it.start, it.end); latestLong(it) } } })
            setOnTouchListener { _, event -> if (event.actionMasked == MotionEvent.ACTION_DOWN) pressed = at(event); val intercept = pressed != null; if (intercept) detector.onTouchEvent(event); if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) postDelayed({ pressed = null }, 350); intercept }
        }
    }, update = { v -> controller.attach(v) { latestChange(controller.html()) }; v.setTextColor(color); v.setHintTextColor(hintColor) })
}

fun normalizeHttpUrl(value: String): String { val s = value.trim(); return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "https://$s" }
