package com.alalkipgen.alalpdf.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One glyph in content-stream order.
 *
 * Coordinates are in points in the glyph's text frame: y grows downwards and
 * the text runs left to right (PDFBox "DirAdj" values). Every glyph of one
 * /ActualText span has the same [spanId] and carries the span's ActualText as
 * [text]. [direction] is the text direction in degrees (0, 90, 180, 270).
 */
internal data class GlyphItem(
    val text: String,
    val left: Float,
    val right: Float,
    val top: Float,
    val bottom: Float,
    val baselineY: Float,
    val fontSize: Float,
    val spanId: Int?,
    val direction: Int = 0,
)

/**
 * Maps a box from a glyph text frame to normalized coordinates (0..1, origin
 * top-left) of the page as it is rendered, i.e. after /Rotate.
 *
 * [width] and [height] are the unrotated crop box size in points and
 * [rotation] is the page's /Rotate. A frame of direction d is the unrotated
 * page turned by d, so the rendered position is that frame turned by
 * (rotation - d), using the same quarter turns as the link boxes.
 */
internal class PageFrame(
    private val width: Float,
    private val height: Float,
    rotation: Int,
) {
    private val rotation = quarter(rotation)

    fun normalize(direction: Int, left: Float, top: Float, right: Float, bottom: Float): PdfTextRunBox {
        val sideways = quarter(direction) == 90 || quarter(direction) == 270
        val frameWidth = (if (sideways) height else width).coerceAtLeast(1f)
        val frameHeight = (if (sideways) width else height).coerceAtLeast(1f)
        val u1 = left / frameWidth
        val u2 = right / frameWidth
        val v1 = top / frameHeight
        val v2 = bottom / frameHeight
        val turn = quarter(rotation - direction)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for ((u, v) in arrayOf(u1 to v1, u2 to v1, u1 to v2, u2 to v2)) {
            val x = when (turn) {
                90 -> 1f - v
                180 -> 1f - u
                270 -> v
                else -> u
            }
            val y = when (turn) {
                90 -> u
                180 -> 1f - v
                270 -> 1f - u
                else -> v
            }
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        return PdfTextRunBox(
            minX.coerceIn(0f, 1f),
            minY.coerceIn(0f, 1f),
            maxX.coerceIn(0f, 1f),
            maxY.coerceIn(0f, 1f),
        )
    }

    private fun quarter(degrees: Int): Int = ((degrees % 360) + 360) % 360
}

internal data class PdfTextRunBox(val left: Float, val top: Float, val right: Float, val bottom: Float)

/** Page text plus one selection run per cluster, space and line break, so concat(runs) == text. */
internal data class AssembledPageText(val text: String, val runs: List<PdfTextRun>, val lineCount: Int)

/**
 * Builds page text from glyphs in content-stream order.
 *
 * Myanmar needs this because the visual order of glyphs is not the logical
 * order of the text: zero-width marks can sit right of the next consonant,
 * and shaping engines reorder or merge glyphs, which is what /ActualText
 * spans repair. One rule set is used for every PDF:
 *
 * 1. All items of one /ActualText span become one cluster whose text is the
 *    ActualText, emitted once; its box is the union of the glyph boxes.
 * 2. A new line starts only when the baseline moves by more than
 *    [LINE_BREAK] x font size (or the text direction changes). Glyph-box
 *    overlap is never used.
 * 3. Inside a line a cluster is an ActualText span or a base item plus the
 *    combining marks (Mn/Mc) that follow it. Clusters are stable-sorted by
 *    left x; marks are never sorted or split on their own.
 * 4. Real space glyphs are kept, repeats collapsed. One space is added
 *    between clusters whose gap is more than [SPACE_GAP] x font size, never
 *    before a mark. Lines are trimmed. The font size is the rendered size, so
 *    the threshold is a quarter em on every page; a word space is about that
 *    wide, and the widest gap inside a Burmese word in the test PDFs is 0.22 em.
 * 5. A cluster with the same text as the previous one and a box within
 *    [DUPLICATE] x font size of it in x and y is a fake-bold or shadow copy
 *    and is dropped.
 */
internal object MyanmarTextAssembler {
    const val LINE_BREAK = 0.5f
    const val SPACE_GAP = 0.25f
    const val DUPLICATE = 0.15f

    fun assemble(
        page: Int,
        items: List<GlyphItem>,
        frame: PageFrame,
        normalize: (String) -> String = { it },
    ): AssembledPageText {
        val lines = splitLines(mergeSpans(items.mapNotNull(::printable)))
            .map { line -> buildPieces(dropDuplicates(clusters(line))) }
            .map { pieces -> trim(collapseSpaces(normalizePieces(pieces, normalize))) }
            .filter { it.isNotEmpty() }
        val runs = ArrayList<PdfTextRun>()
        lines.forEachIndexed { index, pieces ->
            if (index > 0) {
                // The line break belongs to the end of the previous line, with no width.
                val last = lines[index - 1].last()
                runs += run(page, frame, "\n", last.direction, last.right, last.top, last.right, last.bottom)
            }
            pieces.forEach { piece ->
                runs += run(page, frame, piece.text, piece.direction, piece.left, piece.top, piece.right, piece.bottom)
            }
        }
        val text = lines.joinToString("\n") { pieces -> pieces.joinToString("") { it.text } }
        return AssembledPageText(text, runs, lines.size)
    }

    /**
     * PDFBox turns a code that has no Unicode mapping into a control
     * character; such a glyph carries no text. Empty text means the same.
     */
    private fun printable(item: GlyphItem): GlyphItem? {
        val text = item.text.filterNot { it.isISOControl() && !it.isWhitespace() }
        return when {
            text.isEmpty() -> null
            text.length == item.text.length -> item
            else -> item.copy(text = text)
        }
    }

    /** Rule 1: one atom per /ActualText span, one per glyph otherwise. */
    private fun mergeSpans(items: List<GlyphItem>): List<Atom> {
        val atoms = ArrayList<Atom>(items.size)
        for (item in items) {
            val last = atoms.lastOrNull()
            if (item.spanId != null && last != null && last.spanId == item.spanId) {
                last.left = min(last.left, item.left)
                last.right = max(last.right, item.right)
                last.top = min(last.top, item.top)
                last.bottom = max(last.bottom, item.bottom)
            } else {
                atoms += Atom(item)
            }
        }
        return atoms
    }

    /** Rule 2: lines by baseline in content-stream order. */
    private fun splitLines(atoms: List<Atom>): List<List<Atom>> {
        val lines = ArrayList<MutableList<Atom>>()
        var previous: Atom? = null
        for (atom in atoms) {
            val sameLine = previous != null &&
                previous.direction == atom.direction &&
                abs(atom.baselineY - previous.baselineY) <= LINE_BREAK * max(previous.fontSize, atom.fontSize)
            if (sameLine) lines.last() += atom else lines += mutableListOf(atom)
            previous = atom
        }
        return lines
    }

    /**
     * Rule 3: base plus following marks, then a stable sort by left x. A mark
     * after a space or at the start of a line has no base and starts its own
     * cluster.
     */
    private fun clusters(line: List<Atom>): List<Cluster> {
        val clusters = ArrayList<Cluster>()
        for (atom in line) {
            val current = clusters.lastOrNull()
            if (current != null && !current.isBlank && atom.startsWithMark && !atom.isBlank) {
                current.add(atom)
            } else {
                clusters += Cluster(atom)
            }
        }
        return clusters.sortedBy { it.left }
    }

    /** Rule 5: fake-bold and shadow copies drawn on top of the previous cluster. */
    private fun dropDuplicates(clusters: List<Cluster>): List<Cluster> {
        val kept = ArrayList<Cluster>(clusters.size)
        for (cluster in clusters) {
            val previous = kept.lastOrNull()
            if (previous != null && !cluster.isBlank && previous.text == cluster.text) {
                val tolerance = DUPLICATE * max(previous.fontSize, cluster.fontSize)
                if (abs(previous.left - cluster.left) <= tolerance &&
                    abs(previous.right - cluster.right) <= tolerance &&
                    abs(previous.top - cluster.top) <= tolerance &&
                    abs(previous.bottom - cluster.bottom) <= tolerance
                ) {
                    continue
                }
            }
            kept += cluster
        }
        return kept
    }

    /** Rule 4: clusters and spaces of one line, left to right. */
    private fun buildPieces(clusters: List<Cluster>): MutableList<Piece> {
        val pieces = ArrayList<Piece>()
        var previousRight = Float.NaN
        var previousFontSize = 0f
        for (cluster in clusters) {
            val head = cluster.head
            if (cluster.isBlank) {
                // A real space glyph; repeats collapse into one space.
                if (pieces.isNotEmpty() && !pieces.endsWithSpace()) {
                    pieces += Piece.space(head.left, head.right, head.top, head.bottom, head.direction)
                }
                previousRight = head.right
                previousFontSize = head.fontSize
                continue
            }
            val gap = cluster.left - previousRight
            if (!previousRight.isNaN() &&
                gap > SPACE_GAP * max(previousFontSize, head.fontSize) &&
                pieces.isNotEmpty() &&
                !pieces.endsWithSpace() &&
                !cluster.startsWithMark
            ) {
                pieces += Piece.space(previousRight, cluster.left, cluster.top, cluster.bottom, head.direction)
            }
            pieces += Piece(cluster.text, cluster.left, cluster.right, cluster.top, cluster.bottom, head.direction, false)
            previousRight = cluster.right
            previousFontSize = head.fontSize
        }
        // A space spans the gap between its neighbours, so a tap there selects it.
        for (index in 1 until pieces.lastIndex) {
            val piece = pieces[index]
            if (!piece.isSpace) continue
            val before = pieces[index - 1]
            val after = pieces[index + 1]
            piece.left = min(before.right, after.left)
            piece.right = max(before.right, after.left)
            piece.top = min(before.top, after.top)
            piece.bottom = max(before.bottom, after.bottom)
        }
        return pieces
    }

    /** Drops a space that follows another space, e.g. after a piece normalized away. */
    private fun collapseSpaces(pieces: List<Piece>): MutableList<Piece> {
        val kept = ArrayList<Piece>(pieces.size)
        for (piece in pieces) {
            if (piece.isSpace && kept.endsWithSpace()) continue
            kept += piece
        }
        return kept
    }

    /** Trims the line the same way as String.trim(), keeping pieces and text in step. */
    private fun trim(pieces: MutableList<Piece>): MutableList<Piece> {
        while (pieces.isNotEmpty()) {
            val first = pieces.first()
            first.text = first.text.trimStart()
            if (first.text.isEmpty()) pieces.removeAt(0) else break
        }
        while (pieces.isNotEmpty()) {
            val last = pieces.last()
            last.text = last.text.trimEnd()
            if (last.text.isEmpty()) pieces.removeAt(pieces.lastIndex) else break
        }
        return pieces
    }

    /**
     * Normalizes each piece and drops pieces that become empty. A piece that
     * starts with a mark joins the cluster before it, so normalizing piece by
     * piece gives the same text as normalizing the whole line.
     */
    private fun normalizePieces(pieces: List<Piece>, normalize: (String) -> String): List<Piece> {
        val merged = ArrayList<Piece>(pieces.size)
        for (piece in pieces) {
            val previous = merged.lastOrNull()
            if (previous != null && !previous.isSpace && !piece.isSpace && startsWithMark(piece.text)) {
                previous.text += piece.text
                previous.left = min(previous.left, piece.left)
                previous.right = max(previous.right, piece.right)
                previous.top = min(previous.top, piece.top)
                previous.bottom = max(previous.bottom, piece.bottom)
            } else {
                merged += piece
            }
        }
        merged.forEach { it.text = normalize(it.text) }
        return merged.filter { it.text.isNotEmpty() }
    }

    private fun run(
        page: Int,
        frame: PageFrame,
        text: String,
        direction: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): PdfTextRun {
        val box = frame.normalize(direction, left, top, right, bottom)
        return PdfTextRun(page, text, box.left, box.top, box.right, box.bottom, character = true)
    }

    private fun List<Piece>.endsWithSpace(): Boolean = lastOrNull()?.text?.lastOrNull() == ' '

    internal fun startsWithMark(text: String): Boolean {
        if (text.isEmpty()) return false
        val type = Character.getType(text.codePointAt(0))
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt()
    }

    private class Atom(item: GlyphItem) {
        val text = item.text
        val spanId = item.spanId
        val baselineY = item.baselineY
        val fontSize = item.fontSize
        val direction = item.direction
        var left = item.left
        var right = item.right
        var top = item.top
        var bottom = item.bottom
        val isBlank = text.isBlank()
        val startsWithMark = startsWithMark(text)
    }

    private class Cluster(val head: Atom) {
        private val atoms = mutableListOf(head)
        var left = head.left
        var right = head.right
        var top = head.top
        var bottom = head.bottom
        val fontSize get() = head.fontSize
        val isBlank get() = head.isBlank
        val startsWithMark get() = head.startsWithMark
        val text: String get() = atoms.joinToString("") { it.text }

        fun add(atom: Atom) {
            atoms += atom
            left = min(left, atom.left)
            right = max(right, atom.right)
            top = min(top, atom.top)
            bottom = max(bottom, atom.bottom)
        }
    }

    private class Piece(
        var text: String,
        var left: Float,
        var right: Float,
        var top: Float,
        var bottom: Float,
        val direction: Int,
        val isSpace: Boolean,
    ) {
        companion object {
            fun space(left: Float, right: Float, top: Float, bottom: Float, direction: Int) =
                Piece(" ", left, right, top, bottom, direction, true)
        }
    }
}
