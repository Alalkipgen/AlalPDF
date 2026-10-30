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
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Glyph text for fonts whose ToUnicode CMap is wrong, as Microsoft Word
 * writes them for Burmese, and which fonts are Zawgyi.
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
 * Every other font keeps its ToUnicode text.
 *
 * A font is **Zawgyi**, the legacy Burmese encoding whose text is stored
 * in visual order, if its BaseFont or FontName contains "zawgyi" (subset
 * prefix, case, spaces, "-" and "_" ignored), or if a glyph text it gives
 * has U+1033, U+1034 or a code point in U+1060..U+1097: one of its
 * ToUnicode values, checked when the font is first read (not for a broken
 * font), or a glyph text as it is read. Zawgyi uses these for short u and
 * uu, stacked consonants, kinzi and letter variants; Unicode Burmese does
 * not. Unicode Shan or Mon text can match too and then stays in content
 * order, as before. Zawgyi text is kept as it is, not converted to Unicode.
 *
 * One instance serves one document: fonts are cached by their dictionary
 * until [clear].
 */
internal class MyanmarGlyphText(
    private val table: () -> MyanmarGlyphMap = { MyanmarGlyphMap.EMPTY },
    private val log: ((String) -> Unit)? = null,
) {
    /** Where the text of a glyph of a broken font came from. */
    enum class Source { CMAP, TABLE, EMPTY, FALLBACK }

    /**
     * Glyphs of broken fonts read so far, by [Source]; the broken and the
     * Zawgyi fonts, by name; the runs kept in logical order.
     */
    data class Counts(
        val brokenFonts: Int,
        val cmap: Int,
        val table: Int,
        val empty: Int,
        val fallback: Int,
        val zawgyiFonts: Int = 0,
        val logicalRuns: Int = 0,
    )

    private val lock = ReentrantLock()

    @Volatile
    private var stale = false

    // Guarded by [lock].
    private val fonts = IdentityHashMap<COSDictionary, GlyphFont>()
    private var glyphMap: MyanmarGlyphMap? = null
    private val brokenNames = LinkedHashSet<String>()
    private val zawgyiNames = LinkedHashSet<String>()
    private val counts = IntArray(Source.entries.size)
    private var reported = IntArray(Source.entries.size)
    private var logicalRuns = 0

    /** [font] as this document reads it: broken or not, Zawgyi or not so far. */
    fun font(font: PDFont): GlyphFont = lock.withLock {
        dropIfStale()
        fonts.getOrPut(font.cosObject) { describe(font) }
    }

    /**
     * The text of the glyph with [code] in [font]: [unicode], PDFBox's
     * ToUnicode text, unless the font is broken. A text with a Zawgyi code
     * point marks the font as Zawgyi.
     */
    fun text(font: GlyphFont, code: Int, unicode: String?): String? {
        val text = if (font is BrokenFont) lock.withLock { glyph(font, code, unicode) } else unicode
        if (text != null && !font.zawgyi) {
            val codePoint = zawgyiCodePoint(text)
            if (codePoint != null) markZawgyi(font, codePointReason(codePoint))
        }
        return text
    }

    /** True when the ToUnicode CMap of [font] is broken, see above. */
    fun isBroken(font: PDFont): Boolean = font(font).brokenToUnicode

    /** True when [font] is Zawgyi by its name, its ToUnicode or a glyph text read so far, see above. */
    fun isZawgyi(font: PDFont): Boolean = font(font).zawgyi

    /** Counts, and logs, a run of Myanmar glyphs on [page] that is in logical order already. */
    fun keptInLogicalOrder(page: Int, run: String) {
        lock.withLock { logicalRuns++ }
        log?.invoke("page ${page + 1}: kept in logical order: ${run.take(LOGGED_RUN)}")
    }

    fun counts(): Counts = lock.withLock {
        Counts(
            brokenFonts = brokenNames.size,
            cmap = counts[Source.CMAP.ordinal],
            table = counts[Source.TABLE.ordinal],
            empty = counts[Source.EMPTY.ordinal],
            fallback = counts[Source.FALLBACK.ordinal],
            zawgyiFonts = zawgyiNames.size,
            logicalRuns = logicalRuns,
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

    private fun describe(font: PDFont): GlyphFont {
        val toUnicode = try {
            toUnicodeCMap(font)
        } catch (error: Exception) {
            null
        }
        val broken = (font as? PDType0Font)?.let { type0 ->
            try {
                detect(type0, toUnicode)
            } catch (error: Exception) {
                null
            }
        }
        val described = broken ?: GlyphFont(font.name ?: "?")
        val names = listOf(
            runCatching { font.cosObject.getNameAsString(COSName.BASE_FONT) }.getOrNull(),
            runCatching { font.fontDescriptor?.fontName }.getOrNull(),
        )
        if (names.any(::isZawgyiName)) {
            markZawgyi(described, "name")
        } else if (broken == null && toUnicode != null) {
            zawgyiCodePointIn(font, toUnicode)?.let { markZawgyi(described, codePointReason(it)) }
        }
        return described
    }

    /** The first Zawgyi code point in the ToUnicode values of [font], by code, or null. */
    private fun zawgyiCodePointIn(font: PDFont, toUnicode: CMap): Int? {
        val lastCode = if (font is PDType0Font) LAST_TWO_BYTE_CODE else LAST_ONE_BYTE_CODE
        for (code in 0..lastCode) {
            val codePoint = toUnicode.toUnicode(code)?.let(::zawgyiCodePoint)
            if (codePoint != null) return codePoint
        }
        return null
    }

    private fun markZawgyi(font: GlyphFont, reason: String) {
        lock.withLock {
            if (font.zawgyi) return
            font.zawgyi = true
            if (zawgyiNames.add(font.name)) log?.invoke("${font.name}: Zawgyi ($reason), kept as drawn")
        }
    }

    private fun detect(font: PDType0Font, toUnicode: CMap?): BrokenFont? {
        val descendant = font.descendantFont as? PDCIDFontType2 ?: return null
        if (!descendant.isEmbedded) return null
        val cidToGid = descendant.cosObject.getDictionaryObject(COSName.CID_TO_GID_MAP)
        if (cidToGid != null && cidToGid != COSName.IDENTITY) return null
        val ttf = descendant.trueTypeFont ?: return null
        val cmap = unicodeCmap(ttf) ?: return null
        if (toUnicode == null) return null
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

    private fun codePointReason(codePoint: Int): String =
        String.format(Locale.ROOT, "code point U+%04X", codePoint)

    private class BrokenFont(val font: PDType0Font, val ttf: TrueTypeFont, val cmap: CmapSubtable) :
        GlyphFont(font.name ?: "?", brokenToUnicode = true) {
        val glyphs = HashMap<Int, Glyph>()
    }

    private class Glyph(val text: String, val source: Source)

    internal companion object {
        private const val MYANMAR_FIRST = 0x1000
        private const val MYANMAR_LAST = 0x109F
        private const val FIRST_PRINTABLE = 0x20
        private const val LAST_ONE_BYTE_CODE = 0xFF
        private const val LAST_TWO_BYTE_CODE = 0xFFFF
        private const val LOGGED_RUN = 40
        private val SUBSET_PREFIX = Regex("^[A-Z]{6}\\+")

        /** True when a BaseFont or FontName names a Zawgyi font, see above. */
        fun isZawgyiName(name: String?): Boolean =
            name != null &&
                name.replace(SUBSET_PREFIX, "").filterNot { it == ' ' || it == '-' || it == '_' }
                    .contains("zawgyi", ignoreCase = true)

        /** The first code point in [text] that Zawgyi uses and Unicode Burmese does not, or null. */
        fun zawgyiCodePoint(text: String): Int? {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                if (codePoint == 0x1033 || codePoint == 0x1034 || codePoint in 0x1060..0x1097) return codePoint
                index += Character.charCount(codePoint)
            }
            return null
        }
    }
}

/**
 * One font of a document as [MyanmarGlyphText] reads it. [brokenToUnicode]:
 * its ToUnicode CMap is wrong (Word) and its glyph texts come from the font.
 * [zawgyi]: it draws Zawgyi; this can turn true while its glyphs are read.
 */
internal open class GlyphFont(val name: String, val brokenToUnicode: Boolean = false, zawgyi: Boolean = false) {
    @Volatile
    var zawgyi: Boolean = zawgyi
        internal set
}
