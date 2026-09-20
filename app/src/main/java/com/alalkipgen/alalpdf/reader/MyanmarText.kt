package com.alalkipgen.alalpdf.reader

import com.google.myanmartools.TransliterateU2Z
import com.google.myanmartools.TransliterateZ2U
import com.google.myanmartools.ZawgyiDetector
import java.text.Normalizer

/**
 * Myanmar text helpers.
 *
 * Burmese PDFs are hard to search for two independent reasons:
 *
 * 1. **Combining-mark order.** The same visible syllable can be stored with a
 *    different code point order, so a plain `indexOf` misses it. NFC
 *    normalization fixes the common cases.
 * 2. **Zawgyi.** A large amount of Burmese content is still encoded in Zawgyi,
 *    which reuses Unicode Myanmar code points with different meanings. A
 *    Unicode query will never match Zawgyi bytes without conversion.
 *
 * Detection uses Google's `myanmar-tools` n-gram model instead of hand written
 * regex heuristics. The old heuristics flagged ordinary Unicode Burmese as
 * Zawgyi, and the "conversion" then destroyed the text.
 *
 * Conversion is only ever used to build a **search key**, or when the user
 * explicitly asks for it. Text shown on screen or copied to the clipboard is
 * never silently rewritten.
 */
object MyanmarText {

    /** Text is treated as Zawgyi only when the model is this confident. */
    const val ZAWGYI_THRESHOLD = 0.95

    private val ZERO_WIDTH = Regex("[\u200B\u200C\u200D\uFEFF]")

    private val MYANMAR_BLOCK = Regex("[\u1000-\u109F\uAA60-\uAA7F\uA9E0-\uA9FF]")

    private val detector: ZawgyiDetector by lazy { ZawgyiDetector() }

    private val z2u: TransliterateZ2U by lazy { TransliterateZ2U("Zawgyi to Unicode") }

    private val u2z: TransliterateU2Z by lazy { TransliterateU2Z("Unicode to Zawgyi") }

    /** True when the text contains any Myanmar code point. */
    fun hasMyanmar(text: String): Boolean = MYANMAR_BLOCK.containsMatchIn(text)

    /**
     * Probability that [text] is Zawgyi encoded, between 0 and 1.
     * Returns 0 for text without Myanmar characters.
     */
    fun zawgyiProbability(text: String): Double {
        if (text.isBlank() || !hasMyanmar(text)) return 0.0
        return runCatching { detector.getZawgyiProbability(text) }.getOrDefault(0.0)
    }

    /** Strips invisible characters and applies NFC. */
    fun normalize(text: String): String {
        if (text.isEmpty()) return text
        val stripped = ZERO_WIDTH.replace(text, "")
        return Normalizer.normalize(stripped, Normalizer.Form.NFC)
    }

    /** True when the n-gram model is confident that [text] is Zawgyi. */
    fun looksLikeZawgyi(text: String): Boolean = zawgyiProbability(text) >= ZAWGYI_THRESHOLD

    /** Best effort Zawgyi to Unicode conversion. Unicode input is returned unchanged. */
    fun toUnicode(text: String): String {
        if (text.isEmpty()) return text
        if (!looksLikeZawgyi(text)) return normalize(text)
        return normalize(runCatching { z2u.convert(text) }.getOrDefault(text))
    }

    /** Unicode to Zawgyi, for matching Unicode queries against Zawgyi pages. */
    fun toZawgyi(text: String): String {
        if (text.isEmpty()) return text
        return runCatching { u2z.convert(normalize(text)) }.getOrDefault(text)
    }

    /**
     * Encoding independent key used for search. Zawgyi input is converted to
     * Unicode first so a Unicode query matches a Zawgyi page and vice versa.
     */
    fun searchKey(text: String): String {
        if (text.isEmpty()) return text
        val unicode = if (looksLikeZawgyi(text)) {
            runCatching { z2u.convert(text) }.getOrDefault(text)
        } else {
            text
        }
        return normalize(unicode)
    }
}
