package com.alalkipgen.alalpdf.reader

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFMarkedContentExtractor
import com.tom_roush.pdfbox.text.TextPosition
import java.util.concurrent.CancellationException

/**
 * Page text from PDFBox in content-stream order, with /ActualText.
 *
 * PDFium orders zero-width Myanmar marks by glyph position, splits lines by
 * glyph-box overlap, drops real spaces and partly duplicates /ActualText
 * spans, so its text for Burmese pages is broken. This source reads the
 * glyphs in the order the producer wrote them, replaces every /ActualText
 * span by its text, and leaves line, cluster and space decisions to
 * [MyanmarTextAssembler].
 */
internal object PdfBoxTextSource {
    /** A page that needs longer than this is left to PDFium. */
    const val PAGE_TIMEOUT_MS = 2_000L

    /**
     * Text and selection runs for one page of [document].
     *
     * Throws [PageTextTimeoutException] when the page runs past [timeoutMs]
     * and [CancellationException] as soon as [isActive] returns false.
     */
    fun extract(
        document: PDDocument,
        page: Int,
        timeoutMs: Long = PAGE_TIMEOUT_MS,
        isActive: () -> Boolean = { true },
    ): AssembledPageText {
        val pdPage = document.getPage(page)
        val crop = pdPage.cropBox
        val collector = GlyphCollector(System.nanoTime() + timeoutMs * NANOS_PER_MILLI, isActive)
        collector.processPage(pdPage)
        // Rethrow a stop that PDFBox might have swallowed on the way out.
        collector.stop?.let { throw it }
        return MyanmarTextAssembler.assemble(
            page = page,
            items = collector.items,
            frame = PageFrame(crop.width, crop.height, pdPage.rotation),
            normalize = MyanmarText::normalize,
        )
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}

internal class PageTextTimeoutException : RuntimeException("Page text took too long")

/**
 * Collects one [GlyphItem] per glyph. The marked-content stack is tracked so
 * every glyph inside an /ActualText span (the outermost one, when nested)
 * carries that span's text and id; the glyph's own Unicode is ignored there.
 */
private class GlyphCollector(
    private val deadlineNanos: Long,
    private val isActive: () -> Boolean,
) : PDFMarkedContentExtractor() {
    val items = ArrayList<GlyphItem>()
    var stop: RuntimeException? = null
        private set

    private val frames = ArrayDeque<Frame>()
    private var nextFrameId = 0

    override fun beginMarkedContentSequence(tag: COSName?, properties: COSDictionary?) {
        super.beginMarkedContentSequence(tag, properties)
        frames.addLast(Frame(nextFrameId++, properties?.getString(COSName.ACTUAL_TEXT)))
    }

    override fun endMarkedContentSequence() {
        frames.removeLastOrNull()
        super.endMarkedContentSequence()
    }

    override fun processOperator(operator: Operator, operands: MutableList<COSBase>) {
        stop?.let { throw it }
        val reason = when {
            !isActive() -> CancellationException("Page text cancelled")
            System.nanoTime() > deadlineNanos -> PageTextTimeoutException()
            else -> null
        }
        if (reason != null) {
            stop = reason
            throw reason
        }
        super.processOperator(operator, operands)
    }

    override fun processTextPosition(text: TextPosition) {
        val span = frames.firstOrNull { it.actualText != null }
        val value = span?.actualText ?: text.unicode ?: return
        val left = text.xDirAdj
        val baseline = text.yDirAdj
        items += GlyphItem(
            text = value,
            left = left,
            right = left + text.widthDirAdj,
            top = baseline - text.heightDir,
            bottom = baseline,
            baselineY = baseline,
            fontSize = fontSizeOf(text),
            spanId = span?.id,
            direction = text.dir.toInt(),
        )
    }

    /** Rendered font size in points: the text rendering matrix, which includes the CTM. */
    private fun fontSizeOf(text: TextPosition): Float {
        val rendered = text.yScale
        if (rendered.isFinite() && rendered > 0f) return rendered
        return text.fontSizeInPt.takeIf { it.isFinite() && it > 0f } ?: 1f
    }

    private class Frame(val id: Int, val actualText: String?)
}
