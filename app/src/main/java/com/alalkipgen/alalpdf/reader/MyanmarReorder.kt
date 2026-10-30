package com.alalkipgen.alalpdf.reader

/**
 * Visual (glyph) order to logical order for shaped Burmese text, a port of
 * `tools/myanmar-glyph-map/myreorder.py`.
 *
 * A shaping engine draws the glyphs of a syllable in visual order: the vowel
 * sign ေ and the medial ြ before their consonant, kinzi after it. A PDF
 * without /ActualText, as Word writes it, keeps that order. The glyph texts
 * of one line, in content order, are cut into syllables:
 *
 * - A glyph whose text starts with U+1031 (ေ) or U+103C (ြ) is pre-base: it
 *   closes a syllable that already has a base and is held for the next base.
 * - A glyph whose text starts with kinzi (U+1004 U+103A U+1039) belongs to
 *   the current syllable and is written before its base.
 * - A base (consonant, independent vowel, digit, ၊ ။ ၌ ၍ ၎ ၏ ဿ) closes the
 *   current syllable, unless that has only pre-base or kinzi glyphs. Text
 *   that does not start with a Myanmar character is a unit of its own.
 * - Any other glyph is a mark of the current syllable.
 *
 * A syllable is written as kinzi + base + the subjoined consonants (mark
 * texts starting with U+1039, in order) + the other mark characters, stably
 * sorted by weight in the order of the OpenType Myanmar syllable:
 * ျ ြ ွ ှ ေ, ိ ီ ဲ, ု ူ, ါ ာ, ံ, ့, then း last. Asat ် goes right after the
 * base when it is drawn before a medial (ယောက်ျား), else after ါ ာ, else
 * after ့, else before ု ူ (ကျွန်ုပ်), else last before း.
 */
internal object MyanmarReorder {
    /**
     * One syllable, or one glyph that is not Myanmar: its text in logical
     * order and the indexes of its first and last glyph.
     */
    data class Syllable(val text: String, val first: Int, val last: Int)

    /** The text of [glyphs] in logical order. */
    fun reorder(glyphs: List<String>): String = syllables(glyphs).joinToString("") { it.text }

    /**
     * [glyphs], the glyph texts of one line in content order, cut into
     * syllables. Each syllable covers consecutive glyphs; an empty text
     * belongs to none.
     */
    fun syllables(glyphs: List<String>): List<Syllable> {
        val syllables = ArrayList<Syllable>()
        var open: Builder? = null
        for ((index, glyph) in glyphs.withIndex()) {
            if (glyph.isEmpty()) continue
            val first = glyph.codePointAt(0)
            val current = open
            val syllable: Builder
            when {
                first !in MYANMAR -> {
                    current?.let { syllables += it.build() }
                    open = null
                    syllables += Syllable(glyph, index, index)
                    continue
                }
                first == E || first == MEDIAL_RA -> {
                    syllable = if (current == null || current.base != null) {
                        current?.let { syllables += it.build() }
                        Builder(index)
                    } else {
                        current
                    }
                    syllable.pre += glyph
                }
                glyph.startsWith(KINZI) -> {
                    syllable = current ?: Builder(index)
                    syllable.kinzi++
                    if (glyph.length > KINZI.length) syllable.marks += glyph.substring(KINZI.length)
                }
                isBase(first) -> {
                    syllable = if (current != null && current.waitsForBase) {
                        current
                    } else {
                        current?.let { syllables += it.build() }
                        Builder(index)
                    }
                    syllable.base = first
                    val rest = glyph.substring(Character.charCount(first))
                    if (rest.isNotEmpty()) syllable.marks += rest
                }
                else -> {
                    syllable = current ?: Builder(index)
                    syllable.marks += glyph
                }
            }
            syllable.last = index
            open = syllable
        }
        open?.let { syllables += it.build() }
        return syllables
    }

    private class Builder(val first: Int) {
        var last = first
        val pre = ArrayList<String>()
        var kinzi = 0
        var base: Int? = null
        val marks = ArrayList<String>()

        /** Only pre-base or kinzi glyphs so far: the next base belongs here. */
        val waitsForBase: Boolean
            get() = base == null && (pre.isNotEmpty() || kinzi > 0) && marks.isEmpty()

        fun build(): Syllable = Syllable(text(), first, last)

        private fun text(): String {
            val base = base ?: return pre.joinToString("") + KINZI.repeat(kinzi) + marks.joinToString("")
            val (subjoined, rest) = marks.partition { it.startsWith(VIRAMA) }
            val post = rest.flatMap { codePoints(it) }
            val chars = pre.flatMap { codePoints(it) } + post
            val firstMedial = post.indexOfFirst { it in MEDIALS }
            val asat = post.indexOf(ASAT)
            val asatWeight = when {
                asat >= 0 && firstMedial >= 0 && asat < firstMedial -> 2.5
                TALL_AA in chars || AA in chars -> 13.0
                DOT_BELOW in chars -> 12.5
                U in chars || UU in chars -> 8.5
                else -> 13.0
            }
            val sorted = chars.sortedBy { if (it == ASAT) asatWeight else WEIGHTS[it] ?: OTHER_WEIGHT }
            return buildString {
                repeat(kinzi) { append(KINZI) }
                appendCodePoint(base)
                subjoined.forEach { append(it) }
                sorted.forEach { appendCodePoint(it) }
            }
        }
    }

    private fun isBase(c: Int): Boolean =
        c in 0x1000..0x102A || c == 0x103F || c in 0x1040..0x104F || c in 0x1050..0x1055 || c in OTHER_BASES

    private fun codePoints(text: String): List<Int> {
        val codePoints = ArrayList<Int>(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            codePoints += codePoint
            index += Character.charCount(codePoint)
        }
        return codePoints
    }

    private val MYANMAR = 0x1000..0x109F
    private const val E = 0x1031
    private const val MEDIAL_RA = 0x103C
    private const val ASAT = 0x103A
    private const val TALL_AA = 0x102B
    private const val AA = 0x102C
    private const val DOT_BELOW = 0x1037
    private const val U = 0x102F
    private const val UU = 0x1030
    private const val KINZI = "\u1004\u103A\u1039"
    private const val VIRAMA = "\u1039"
    private const val OTHER_WEIGHT = 15.0
    private val MEDIALS = setOf(0x103B, 0x103C, 0x103D, 0x103E)
    private val WEIGHTS = mapOf(
        0x103B to 3.0, 0x103C to 4.0, 0x103D to 5.0, 0x103E to 6.0, 0x1031 to 7.0,
        0x102D to 8.0, 0x102E to 8.0, 0x1032 to 8.0, 0x102F to 9.0, 0x1030 to 9.0,
        0x102B to 10.0, 0x102C to 10.0, 0x1036 to 11.0, 0x1037 to 12.0, 0x1038 to 16.0,
    )
    private val OTHER_BASES = setOf(0x105A, 0x105B, 0x105C, 0x105D, 0x1061, 0x1065, 0x1066, 0x106E, 0x106F, 0x1070) +
        (0x1075..0x1081) + 0x108E + (0x1090..0x1099)
}
