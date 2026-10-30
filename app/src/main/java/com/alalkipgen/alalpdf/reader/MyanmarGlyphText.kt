package com.alalkipgen.alalpdf.reader

import com.tom_roush.fontbox.cmap.CMap
import com.tom_roush.fontbox.cmap.CMapParser
import com.tom_roush.fontbox.ttf.CmapSubtable
import com.tom_roush.fontbox.ttf.CmapTable
import com.tom_roush.fontbox.ttf.TrueTypeFont
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.font.PDCIDFontType2
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import java.util.IdentityHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Glyph text for fonts whose ToUnicode CMap is wrong, as Microsoft Word
 * writes them for Burmese.
 *
 * Word's "Save as PDF" embeds the font as an Identity-H CIDFontType2 subset
 * that keeps the original glyph ids and a small Unicode `cmap` with the
 * typed characters, but fills the ToUnicode CMap per cluster by position,
 * first use wins: the medial-ra glyph reads as မ, the မ glyph as ြ, marks get
 * extra characters. Such a font is **broken**: for a Myanmar code point
 * (U+1000..U+109F) in its `cmap`, the ToUnicode entry of that glyph is a
 * different text. Glyphs without a ToUnicode entry do not count.
 *
 * A glyph of a broken font gets its text, cached per glyph id, from:
 *
 * 1. the font's `cmap`, reversed (code points below U+0020 ignored; of
 *    several, the one equal to the ToUnicode text, else the lowest);
 * 2. [MyanmarGlyphMap], by outline hash ([MyanmarGlyphHash]);
 * 3. nothing, for a glyph without contours (Noto's spacing glyphs);
 * 4. the ToUnicode text, as before; these fallbacks are counted.
 *
 * Every other font keeps its ToUnicode text. One instance serves one
 * document: fonts are cached by their dictionary until [clear].
 */
internal class MyanmarGlyphText(
    private val table: () -> MyanmarGlyphMap = { MyanmarGlyphMap.EMPTY },
    private val log: ((String) -> Unit)? = null,
) {
    /** Where the text of a glyph of a broken font came from. */
    enum class Source { CMAP, TABLE, EMPTY, FALLBACK }

    /** Glyphs of broken fonts read so far, by [Source], and the names of the broken fonts. */
    data class Counts(val brokenFonts: Int, val cmap: Int, val table: Int, val empty: Int, val fallback: Int)

    private val lock = ReentrantLock()

    @Volatile
    private var stale = false

    // Guarded by [lock]. A null value is a font that keeps its ToUnicode text.
    private val fonts = IdentityHashMap<COSDictionary, BrokenFont?>()
    private var glyphMap: MyanmarGlyphMap? = null
    private val brokenNames = LinkedHashSet<String>()
    private val counts = IntArray(Source.entries.size)
    private var reported = IntArray(Source.entries.size)

    /**
     * The text of the glyph with [code] in [font]: [unicode], PDFBox's
     * ToUnicode text, unless the font is broken.
     */
    fun text(font: PDFont?, code: Int, unicode: String?): String? {
        if (font !is PDType0Font) return unicode
        return lock.withLock {
            dropIfStale()
            val broken = brokenFont(font) ?: return unicode
            glyph(broken, code, unicode)
        }
    }

    /** True when the ToUnicode CMap of [font] is broken, see above. */
    fun isBroken(font: PDFont): Boolean =
        font is PDType0Font && lock.withLock {
            dropIfStale()
            brokenFont(font) != null
        }

    fun counts(): Counts = lock.withLock {
        Counts(
            brokenFonts = brokenNames.size,
            cmap = counts[Source.CMAP.ordinal],
            table = counts[Source.TABLE.ordinal],
            empty = counts[Source.EMPTY.ordinal],
            fallback = counts[Source.FALLBACK.ordinal],
        )
    }

    /** Logs the document's totals after [page] was read, when logging is on and they changed. */
    fun report(page: Int) {
        val log = log ?: return
        val message = lock.withLock {
            if (counts.contentEquals(reported)) return
            reported = counts.copyOf()
            "after page ${page + 1}: ${brokenNames.size} broken font(s); glyph text from cmap " +
                "${counts[Source.CMAP.ordinal]}, glyph map ${counts[Source.TABLE.ordinal]}, " +
                "no outline ${counts[Source.EMPTY.ordinal]}, ToUnicode fallback ${counts[Source.FALLBACK.ordinal]}"
        }
        log(message)
    }

    /**
     * Forgets the fonts, which belong to a document that is closed or
     * trimmed. Never waits: a running [text] call drops them on its next call.
     */
    fun clear() {
        stale = true
        if (lock.tryLock()) {
            try {
                dropIfStale()
            } finally {
                lock.unlock()
            }
        }
    }

    private fun dropIfStale() {
        if (!stale) return
        stale = false
        fonts.clear()
    }

    private fun brokenFont(font: PDType0Font): BrokenFont? {
        val key = font.cosObject
        if (fonts.containsKey(key)) return fonts[key]
        val broken = try {
            detect(font)
        } catch (error: Exception) {
            null
        }
        fonts[key] = broken
        return broken
    }

    private fun detect(font: PDType0Font): BrokenFont? {
        val descendant = font.descendantFont as? PDCIDFontType2 ?: return null
        if (!descendant.isEmbedded) return null
        val cidToGid = descendant.cosObject.getDictionaryObject(COSName.CID_TO_GID_MAP)
        if (cidToGid != null && cidToGid != COSName.IDENTITY) return null
        val ttf = descendant.trueTypeFont ?: return null
        val cmap = unicodeCmap(ttf) ?: return null
        val toUnicode = toUnicodeCMap(font) ?: return null
        var mapped = 0
        var wrong = 0
        for (codePoint in MYANMAR_FIRST..MYANMAR_LAST) {
            val gid = cmap.getGlyphId(codePoint)
            // Word writes Identity-H with CIDToGIDMap Identity: a glyph's code is its glyph id.
            if (gid == 0 || font.codeToGID(gid) != gid) continue
            val text = toUnicode.toUnicode(gid) ?: continue
            mapped++
            if (text != text(codePoint)) wrong++
        }
        if (wrong == 0) return null
        val broken = BrokenFont(font, ttf, cmap)
        if (brokenNames.add(broken.name)) {
            log?.invoke("${broken.name}: ToUnicode differs from the font's cmap for $wrong of $mapped Myanmar glyphs")
        }
        return broken
    }

    private fun glyph(font: BrokenFont, code: Int, unicode: String?): String? {
        val glyph = try {
            val gid = font.font.codeToGID(code)
            font.glyphs.getOrPut(gid) { resolve(font, gid, unicode) }
        } catch (error: Exception) {
            return unicode
        }
        counts[glyph.source.ordinal]++
        return glyph.text
    }

    private fun resolve(font: BrokenFont, gid: Int, unicode: String?): Glyph {
        val codePoints = font.cmap.getCharCodes(gid).orEmpty().filter { it >= FIRST_PRINTABLE }
        if (codePoints.isNotEmpty()) {
            val codePoint = codePoints.firstOrNull { text(it) == unicode } ?: codePoints.min()
            return Glyph(text(codePoint), Source.CMAP)
        }
        val outline = font.ttf.glyph?.getGlyph(gid)?.description
        val hash = outline?.let(MyanmarGlyphHash::of)
        if (hash != null) map()[hash]?.let { return Glyph(it, Source.TABLE) }
        if (outline != null && hash == null) return Glyph("", Source.EMPTY)
        log?.invoke(
            "${font.name}: no text for glyph $gid (outline ${hash?.let(MyanmarGlyphHash::hex) ?: "-"}), " +
                "using ToUnicode \"${unicode.orEmpty()}\"",
        )
        return Glyph(unicode.orEmpty(), Source.FALLBACK)
    }

    private fun map(): MyanmarGlyphMap =
        glyphMap ?: (runCatching(table).getOrNull() ?: MyanmarGlyphMap.EMPTY).also { glyphMap = it }

    /**
     * The font's ToUnicode CMap, read here because PDType0Font.toUnicode
     * falls back to the font's cmap for a code without an entry.
     */
    private fun toUnicodeCMap(font: PDFont): CMap? {
        val stream = font.cosObject.getDictionaryObject(COSName.TO_UNICODE) as? COSStream ?: return null
        return stream.createInputStream().use { CMapParser(true).parse(it) }
    }

    /** The Unicode cmap subtable: Windows BMP, else Windows full, else any Unicode platform one. */
    private fun unicodeCmap(ttf: TrueTypeFont): CmapSubtable? {
        val table = ttf.cmap ?: return null
        return table.getSubtable(CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_BMP)
            ?: table.getSubtable(CmapTable.PLATFORM_WINDOWS, CmapTable.ENCODING_WIN_UNICODE_FULL)
            ?: table.cmaps.orEmpty().firstOrNull { it.platformId == CmapTable.PLATFORM_UNICODE }
    }

    private fun text(codePoint: Int): String = String(Character.toChars(codePoint))

    private class BrokenFont(val font: PDType0Font, val ttf: TrueTypeFont, val cmap: CmapSubtable) {
        val name: String = font.name ?: "?"
        val glyphs = HashMap<Int, Glyph>()
    }

    private class Glyph(val text: String, val source: Source)

    private companion object {
        const val MYANMAR_FIRST = 0x1000
        const val MYANMAR_LAST = 0x109F
        const val FIRST_PRINTABLE = 0x20
    }
}
