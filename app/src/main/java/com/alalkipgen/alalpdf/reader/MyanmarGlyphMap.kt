package com.alalkipgen.alalpdf.reader

import android.content.res.AssetManager
import androidx.annotation.WorkerThread
import java.io.BufferedReader

/**
 * The text of known Myanmar glyphs by outline hash ([MyanmarGlyphHash]).
 *
 * The bundled map, `assets/myanmar/glyph_map_v1.tsv`, is made by
 * `tools/myanmar-glyph-map` from the Noto Sans Myanmar fonts listed in its
 * `fonts.txt`. Each row is `hash<TAB>code points`: the code points in hex,
 * separated by spaces, or `-` for a glyph that has no text. Lines starting
 * with `#` are comments.
 */
internal class MyanmarGlyphMap(private val texts: Map<Long, String>) {
    val size: Int
        get() = texts.size

    /** The text of the glyph with outline [hash], or null when it is not in the map. */
    operator fun get(hash: Long): String? = texts[hash]

    companion object {
        const val ASSET = "myanmar/glyph_map_v1.tsv"

        val EMPTY = MyanmarGlyphMap(emptyMap())

        @Volatile
        private var bundled: MyanmarGlyphMap? = null

        /**
         * The map in the app's assets. It is read once per process, on the
         * first call, which must not be on the main thread; [EMPTY] when the
         * asset cannot be read.
         */
        @WorkerThread
        fun bundled(assets: AssetManager): MyanmarGlyphMap {
            bundled?.let { return it }
            synchronized(this) {
                bundled?.let { return it }
                val map = runCatching { assets.open(ASSET).bufferedReader().use(::parse) }.getOrDefault(EMPTY)
                bundled = map
                return map
            }
        }

        /** Reads rows in the format above. Malformed rows are skipped. */
        fun parse(reader: BufferedReader): MyanmarGlyphMap {
            val texts = HashMap<Long, String>(EXPECTED_ROWS)
            reader.forEachLine { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val tab = line.indexOf('\t')
                if (tab < 0) return@forEachLine
                val hash = MyanmarGlyphHash.parse(line.substring(0, tab)) ?: return@forEachLine
                val text = text(line.substring(tab + 1).trim()) ?: return@forEachLine
                texts[hash] = text
            }
            return MyanmarGlyphMap(texts)
        }

        private fun text(value: String): String? {
            if (value == NO_TEXT) return ""
            if (value.isEmpty()) return null
            val text = StringBuilder()
            for (digits in value.split(' ')) {
                val codePoint = digits.toIntOrNull(16)?.takeIf(Character::isValidCodePoint) ?: return null
                text.appendCodePoint(codePoint)
            }
            return text.toString()
        }

        private const val NO_TEXT = "-"

        /** Room for the bundled map (about 5,000 rows) without rehashing. */
        private const val EXPECTED_ROWS = 8_192
    }
}
