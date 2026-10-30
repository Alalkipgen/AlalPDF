package com.alalkipgen.alalpdf.reader

import com.tom_roush.fontbox.ttf.GlyphDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MyanmarGlyphMapTest {
    @Test
    fun hashIsTheSameAsGhashPy() {
        // hashlib.sha256(b"g1|1|2|1,0,0;0,10,-3;1,5,5").hexdigest()[:16]
        val triangle = Outline(endPoints = intArrayOf(2), points = listOf(Point(0x11, 0, 0), Point(0x20, 10, -3), Point(0x37, 5, 5)))
        assertEquals("g1|1|2|1,0,0;0,10,-3;1,5,5", MyanmarGlyphHash.key(triangle))
        assertEquals("798058ce86f1ddf4", MyanmarGlyphHash.hex(MyanmarGlyphHash.of(triangle)!!))
        val twoContours = Outline(
            endPoints = intArrayOf(2, 3),
            points = listOf(Point(1, 0, 0), Point(0, 10, -3), Point(1, 5, 5), Point(1, 7, 7)),
        )
        assertEquals("7bb7c96c0108ad55", MyanmarGlyphHash.hex(MyanmarGlyphHash.of(twoContours)!!))
    }

    @Test
    fun onlySimpleGlyphsWithoutContoursHaveNoHash() {
        assertNull(MyanmarGlyphHash.of(Outline(intArrayOf(), emptyList())))
        // ghash.py hashes a composite glyph even when its parts are empty.
        val emptyComposite = Outline(intArrayOf(), emptyList(), composite = true)
        assertEquals("44b696a4e3d78c6c", MyanmarGlyphHash.hex(MyanmarGlyphHash.of(emptyComposite)!!))
    }

    @Test
    fun hexKeepsLeadingZerosAndParsesBack() {
        assertEquals("000d287e0aef0345", MyanmarGlyphHash.hex(0x000d287e0aef0345L))
        assertEquals(-1L, MyanmarGlyphHash.parse("ffffffffffffffff"))
        assertNull(MyanmarGlyphHash.parse("1ffffffffffffffff"))
        assertNull(MyanmarGlyphHash.parse("xyz"))
    }

    @Test
    fun mapRowsAreCodePointsOrNoText() {
        val rows = listOf(
            "# comment",
            "000d287e0aef0345\tAA66",
            "0015fb2b0d5a4bd5\t1039 1010 103D",
            "ffffffffffffffff\t-",
            "",
            "0000000000000001",
            "0000000000000002\tzz",
        )
        val map = MyanmarGlyphMap.parse(rows.joinToString("\n").reader().buffered())
        assertEquals(3, map.size)
        assertEquals("\uAA66", map[0x000d287e0aef0345L])
        assertEquals("\u1039\u1010\u103D", map[0x0015fb2b0d5a4bd5L])
        assertEquals("", map[-1L])
        assertNull(map[1L])
        assertNull(map[2L])
    }

    private class Point(val flags: Int, val x: Int, val y: Int)

    private class Outline(
        private val endPoints: IntArray,
        private val points: List<Point>,
        private val composite: Boolean = false,
    ) : GlyphDescription {
        override fun getEndPtOfContours(i: Int): Int = endPoints[i]
        override fun getFlags(i: Int): Byte = points[i].flags.toByte()
        override fun getXCoordinate(i: Int): Short = points[i].x.toShort()
        override fun getYCoordinate(i: Int): Short = points[i].y.toShort()
        override fun isComposite(): Boolean = composite
        override fun getPointCount(): Int = points.size
        override fun getContourCount(): Int = endPoints.size
        override fun resolve() = Unit
    }
}
