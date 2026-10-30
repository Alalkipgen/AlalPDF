package com.alalkipgen.alalpdf.reader

import com.tom_roush.fontbox.ttf.GlyphDescription
import java.security.MessageDigest

/**
 * Outline hash "g1" of a TrueType glyph, the key of [MyanmarGlyphMap].
 *
 * It must stay byte for byte the same as `tools/myanmar-glyph-map/ghash.py`,
 * which built the map:
 *
 *     s = "g1|" + contourCount + "|" + endPts joined by "," + "|" +
 *         for each point "onCurve,x,y" joined by ";"     (onCurve = flags & 1)
 *     hash = the first 8 bytes of SHA-256(UTF-8 s)
 *
 * Composite glyphs are hashed by their resolved points, so a subset that
 * keeps the original glyph outlines, as Word writes, gives the same hash as
 * the full font. The hash is a big-endian [Long]; [hex] gives the 16
 * lowercase hex digits used in the map file.
 */
internal object MyanmarGlyphHash {
    /** The hash of [glyph], or null for a simple glyph without contours (a space). */
    fun of(glyph: GlyphDescription): Long? {
        if (!glyph.isComposite && glyph.contourCount == 0) return null
        return hash(key(glyph))
    }

    /** The string that is hashed. */
    fun key(glyph: GlyphDescription): String = buildString {
        val contours = glyph.contourCount
        append("g1|").append(contours).append('|')
        for (contour in 0 until contours) {
            if (contour > 0) append(',')
            append(glyph.getEndPtOfContours(contour))
        }
        append('|')
        for (point in 0 until glyph.pointCount) {
            if (point > 0) append(';')
            append(glyph.getFlags(point).toInt() and ON_CURVE).append(',')
            append(glyph.getXCoordinate(point).toInt()).append(',')
            append(glyph.getYCoordinate(point).toInt())
        }
    }

    /** The first 8 bytes of SHA-256([key]) as a big-endian number. */
    fun hash(key: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        var value = 0L
        for (index in 0 until Long.SIZE_BYTES) value = (value shl Byte.SIZE_BITS) or (digest[index].toLong() and 0xFF)
        return value
    }

    /** [hash] as 16 lowercase hex digits, as written in the map file. */
    fun hex(hash: Long): String = java.lang.Long.toHexString(hash).padStart(HEX_DIGITS, '0')

    /** The hash written as [hex], or null when [text] is not 1 to 16 hex digits. */
    fun parse(text: String): Long? {
        if (text.isEmpty() || text.length > HEX_DIGITS) return null
        return runCatching { java.lang.Long.parseUnsignedLong(text, 16) }.getOrNull()
    }

    private const val ON_CURVE = 1
    private const val HEX_DIGITS = 16
}
