package com.alalkipgen.alalpdf.create

import android.graphics.Color
import android.graphics.Typeface
import android.text.*
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.view.GestureDetector
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.core.text.HtmlCompat
import androidx.core.view.ViewCompat
import androidx.core.widget.NestedScrollView
import kotlin.math.abs
import kotlin.math.roundToInt

data class EditorLink(val text: String, val url: String, val start: Int, val end: Int)

@Stable class RichTextController {
    private var view: EditText? = null
    private var changed: (() -> Unit)? = null
    internal fun attach(v: EditText, callback: () -> Unit) { view = v; changed = callback }
    internal fun html() = view?.editableText?.let { HtmlCompat.toHtml(it, HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE) }.orEmpty()
    fun undo() { view?.onTextContextMenuItem(android.R.id.undo) }
    fun redo() { view?.onTextContextMenuItem(android.R.id.redo) }
    fun clear() { view?.text?.clear(); changed?.invoke() }
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

@Composable
fun RichTextEditor(
    html: String,
    onChange: (String) -> Unit,
    title: String,
    onTitleChange: (String) -> Unit,
    controller: RichTextController,
    modifier: Modifier,
    onLongLink: (EditorLink) -> Unit,
    onScroll: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val latestChange by rememberUpdatedState(onChange)
    val latestTitleChange by rememberUpdatedState(onTitleChange)
    val latestLong by rememberUpdatedState(onLongLink)
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val pyidaungsu=remember(context){PyidaungsuFonts.regular(context)}
    val latestScroll by rememberUpdatedState(onScroll)
    // The editor lives in a NestedScrollView, so this interop connection is what
    // lets its scrolling drive the collapsing top app bar above it.
    AndroidView(
        modifier = modifier.nestedScroll(rememberNestedScrollInteropConnection()),
        factory = {
            val titleEditor = EditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                id = TITLE_EDITOR_ID
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(color)
                setHintTextColor(hintColor)
                textSize = 24f
                typeface = PyidaungsuFonts.bold(context)
                gravity = android.view.Gravity.TOP
                hint = "Title"
                setPadding(32, 28, 32, 18)
                isSingleLine = true
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(title)
                setSelection(text.length)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val clean = s?.toString()?.replace("\n", "")?.take(120).orEmpty()
                        if (clean != s?.toString()) {
                            setText(clean)
                            setSelection(clean.length)
                        } else {
                            latestTitleChange(clean)
                        }
                    }
                })
            }
            val editor = EditText(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                id = BODY_EDITOR_ID
                setBackgroundColor(Color.TRANSPARENT)
                setTextColor(color)
                setHintTextColor(hintColor)
                textSize = 17f
                typeface=pyidaungsu
                gravity = android.view.Gravity.TOP
                hint = "Start writing…"
                setPadding(32, 28, 32, 48)
                isVerticalScrollBarEnabled = false
                setHorizontallyScrolling(false)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                overScrollMode = View.OVER_SCROLL_NEVER
                setText(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY))
                setSelection(text.length)
            }
            fun emit() = latestChange(controller.html())
            controller.attach(editor, ::emit)
            editor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = emit()
            })
            installLinkGestures(editor, { runCatching { uri.openUri(normalizeHttpUrl(it)) } }, latestLong)
            FastEditorScrollView(context).apply {
                // Title and body have one scroll owner. This avoids resizing an
                // AndroidView from Compose on every scroll pixel, which caused
                // a relayout feedback loop, flashing and dropped frames.
                setOnScrollChangeListener { _: View, _: Int, y: Int, _: Int, _: Int -> latestScroll(y) }
                isFillViewport = true
                isNestedScrollingEnabled = true
                ViewCompat.setNestedScrollingEnabled(this, true)
                clipToPadding = false
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        addView(titleEditor)
                        addView(editor)
                    },
                )
            }
        },
        update = { scrollView ->
            val container = scrollView.getChildAt(0) as LinearLayout
            val titleEditor = container.findViewById<EditText>(TITLE_EDITOR_ID)
            val editor = container.findViewById<EditText>(BODY_EDITOR_ID)
            controller.attach(editor) { latestChange(controller.html()) }
            titleEditor.setTextColor(color)
            titleEditor.setHintTextColor(hintColor)
            titleEditor.typeface = PyidaungsuFonts.bold(context)
            editor.setTextColor(color)
            editor.setHintTextColor(hintColor)
            editor.typeface=pyidaungsu
            if (titleEditor.text.toString() != title && !titleEditor.hasFocus()) {
                titleEditor.setText(title)
                titleEditor.setSelection(titleEditor.text.length)
            }
            val incoming = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            if (editor.text.toString() != incoming && !editor.hasFocus()) {
                editor.setText(HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY))
                editor.setSelection(editor.text.length)
            }
        },
    )
}

private const val TITLE_EDITOR_ID = 0x0a1a1001
private const val BODY_EDITOR_ID = 0x0a1a1002

private fun installLinkGestures(editor: EditText, openLink: (String) -> Unit, longLink: (EditorLink) -> Unit) {
    val touchSlop = ViewConfiguration.get(editor.context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var moved = false
    var pressed: EditorLink? = null
    fun at(event: MotionEvent): EditorLink? {
        val layout = editor.layout ?: return null
        val line = layout.getLineForVertical((event.y - editor.totalPaddingTop).toInt().coerceAtLeast(0))
        val offset = layout.getOffsetForHorizontal(line, event.x - editor.totalPaddingLeft)
        val text = editor.editableText
        val span = text.getSpans(offset, (offset + 1).coerceAtMost(text.length), URLSpan::class.java).firstOrNull() ?: return null
        val a = text.getSpanStart(span)
        val b = text.getSpanEnd(span)
        return EditorLink(text.subSequence(a, b).toString(), span.url, a, b)
    }
    val detector = GestureDetector(editor.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val link = pressed ?: return false
            if (moved) return false
            openLink(link.url)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val link = pressed ?: return
            if (moved) return
            editor.parent?.requestDisallowInterceptTouchEvent(true)
            editor.setSelection(link.start, link.end)
            editor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            longLink(link)
        }
    })
    editor.setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; moved = false; pressed = at(event) }
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop) { moved = true; pressed = null }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { editor.parent?.requestDisallowInterceptTouchEvent(false); editor.postDelayed({ pressed = null }, 350) }
        }
        detector.onTouchEvent(event)
        false
    }
}

private class FastEditorScrollView(context: Context) : NestedScrollView(context) {
    private val maximumVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var lastEdgeHapticAt = 0L
    override fun fling(velocityY: Int) {
        super.fling((velocityY * 1.25f).roundToInt().coerceIn(-maximumVelocity, maximumVelocity))
    }
    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
        val now = android.os.SystemClock.uptimeMillis()
        if (clampedY && now - lastEdgeHapticAt > 300L) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastEdgeHapticAt = now
        }
    }
}

fun normalizeHttpUrl(value: String): String { val s = value.trim(); return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s else "https://$s" }
