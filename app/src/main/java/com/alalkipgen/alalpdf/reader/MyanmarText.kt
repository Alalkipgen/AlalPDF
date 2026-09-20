package com.alalkipgen.alalpdf.reader

import java.text.Normalizer

/**
 * Myanmar text helpers.
 *
 * Two independent problems make Burmese PDFs hard to search:
 *
 * 1. **Combining-mark order.** The same visible syllable can be stored with a
 *    different code point order, so a plain `indexOf` misses it. NFC
 *    normalization fixes the common cases.
 * 2. **Zawgyi.** A large amount of Burmese content is still encoded in Zawgyi,
 *    which reuses Unicode Myanmar code points with different meanings. A
 *    Unicode query will never match Zawgyi bytes without conversion.
 *
 * The Zawgyi conversion here is **best effort** and is used only to build a
 * search key. Text shown to the user or copied to the clipboard is never
 * silently rewritten.
 */
object MyanmarText {

    private val ZERO_WIDTH = Regex("[\u200B\u200C\u200D\uFEFF]")

    /** Code points that only Zawgyi uses, or uses with a different meaning. */
    private val ZAWGYI_ONLY = charArrayOf(
        '\u1033', '\u1034', '\u1035',
        '\u1060', '\u1061', '\u1062', '\u1063', '\u1064', '\u1065', '\u1066',
        '\u1067', '\u1068', '\u1069', '\u106A', '\u106B', '\u106C', '\u106D',
        '\u106E', '\u106F', '\u1070', '\u1071', '\u1072', '\u1073', '\u1074',
        '\u1075', '\u1076', '\u1077', '\u1078', '\u1079', '\u107A', '\u107B',
        '\u107C', '\u107D', '\u107E', '\u107F', '\u1080', '\u1081', '\u1082',
        '\u1083', '\u1084', '\u1085', '\u1086', '\u1087', '\u1088', '\u1089',
        '\u108A', '\u108B', '\u108C', '\u108D', '\u108E', '\u108F', '\u1090',
        '\u1091', '\u1092', '\u1093', '\u1094', '\u1095', '\u1096', '\u1097',
    ).toSet()

    /**
     * In Unicode the e-vowel U+1031 and the medial ra U+103C follow their
     * consonant. In Zawgyi they are typed before it, so a leading vowel or
     * medial ra is a strong signal.
     *
     * The asat U+103A must NOT be listed here: "consonant + asat + consonant"
     * is ordinary Unicode Burmese, and treating it as Zawgyi made almost every
     * Unicode page get "converted", which broke the searches it was meant to
     * fix.
     */
    private val ZAWGYI_ORDER = Regex("[\u1031\u103C][\u1000-\u1021]")

    /** Strips invisible characters and applies NFC. */
    fun normalize(input: String): String {
        if (input.isEmpty()) return input
        val stripped = ZERO_WIDTH.replace(input, "")
        return runCatching { Normalizer.normalize(stripped, Normalizer.Form.NFC) }.getOrDefault(stripped)
    }

    /** Heuristic Zawgyi detection. */
    fun looksLikeZawgyi(text: String): Boolean {
        if (text.isEmpty()) return false
        if (text.any { it in ZAWGYI_ONLY }) return true
        return ZAWGYI_ORDER.containsMatchIn(text)
    }

    /**
     * Best-effort Zawgyi to Unicode conversion.
     *
     * This covers the frequent mappings and the vowel/medial reordering rules.
     * It is deliberately conservative: anything it does not recognise passes
     * through unchanged.
     */
    fun toUnicode(text: String): String {
        if (text.isEmpty()) return text
        var out = text

        // Consonant and vowel variants that Zawgyi splits into several glyphs.
        out = out
            .replace("\u103C\u108A", "\u103D\u103E")
            .replace("\u1064", "\u1004\u103A\u1039")
            .replace("\u104E", "\u104E\u1004\u103A\u1038")
            .replace("\u1086", "\u103F")
            .replace("\u1090", "\u101B")
            .replace("\u1093", "\u1018")
            .replace("\u1094", "\u1039")
            .replace("\u1095", "\u1039")
            .replace("\u1096", "\u1039\u1010\u103D")
            .replace("\u1097", "\u1039\u1014")

        // Stacked consonants written with a dedicated Zawgyi glyph.
        val stacked = mapOf(
            '\u1060' to "\u1039\u1000", '\u1061' to "\u1039\u1001",
            '\u1062' to "\u1039\u1002", '\u1063' to "\u1039\u1003",
            '\u1065' to "\u1039\u1005", '\u1066' to "\u1039\u1006",
            '\u1067' to "\u1039\u1006", '\u1068' to "\u1039\u1007",
            '\u1069' to "\u1039\u1008", '\u106C' to "\u1039\u100B",
            '\u106D' to "\u1039\u100C", '\u1070' to "\u1039\u100F",
            '\u1071' to "\u1039\u1010", '\u1072' to "\u1039\u1010",
            '\u1073' to "\u1039\u1011", '\u1074' to "\u1039\u1011",
            '\u1075' to "\u1039\u1012", '\u1076' to "\u1039\u1013",
            '\u1077' to "\u1039\u1014", '\u1078' to "\u1039\u1015",
            '\u1079' to "\u1039\u1016", '\u107A' to "\u1039\u1017",
            '\u107B' to "\u1039\u1018", '\u107C' to "\u1039\u1019",
            '\u107D' to "\u103A", '\u1085' to "\u1039\u101C",
        )
        val builder = StringBuilder(out.length + 16)
        for (character in out) {
            val replacement = stacked[character]
            if (replacement != null) builder.append(replacement) else builder.append(character)
        }
        out = builder.toString()

        // Vowel variants for tall consonants collapse onto the base vowel.
        out = out
            .replace('\u1033', '\u102F')
            .replace('\u1034', '\u1030')
            .replace('\u1035', '\u102D')
            .replace('\u1087', '\u103E')
            .replace('\u1088', '\u103E')
            .replace('\u1089', '\u103E')
            .replace('\u108B', '\u102D')
            .replace('\u108C', '\u102E')
            .replace('\u108D', '\u1036')
            .replace('\u108F', '\u1014')
            .replace('\u1091', '\u100F')
            .replace('\u1092', '\u100B')

        // Medial remapping. Zawgyi shifts every medial down by one code point.
        out = out.map { character ->
            when (character) {
                '\u103A' -> '\u103B'
                '\u103B' -> '\u103C'
                '\u103C' -> '\u103D'
                '\u103D' -> '\u103E'
                '\u1039' -> '\u103A'
                else -> character
            }
        }.joinToString("")

        // Reorder: in Zawgyi the e-vowel and medial ra are typed before the
        // consonant; Unicode stores them after it.
        out = REORDER.replace(out) { match ->
            val vowel = match.groupValues[1]
            val medial = match.groupValues[2]
            val consonant = match.groupValues[3]
            consonant + medial + vowel
        }

        return normalize(out)
    }

    private val REORDER = Regex("(\u1031)?(\u103C)?([\u1000-\u1021\u103F\u104E])")

    /**
     * Builds a comparable key for search. Case is preserved so that excerpts
     * stay readable; matching itself is case-insensitive.
     */
    fun searchKey(text: String): String {
        val normalized = normalize(text)
        return if (looksLikeZawgyi(normalized)) toUnicode(normalized) else normalized
    }
}
