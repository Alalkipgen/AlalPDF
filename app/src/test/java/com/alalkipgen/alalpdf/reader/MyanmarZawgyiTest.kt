package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which fonts are Zawgyi; MyanmarTextAssemblerTest checks that their glyphs keep their order. */
class MyanmarZawgyiTest {
    @Test
    fun zawgyiFontNames() {
        for (name in listOf("ABCDEF+Zawgyi-One", "ZawgyiOne2008", "Zawgyi One Bold", "zawgyi_one")) {
            assertTrue(name, MyanmarGlyphText.isZawgyiName(name))
        }
        // "ZAWGYI+" is a subset prefix.
        for (name in listOf("NotoSansMyanmar-Regular", "MyanmarText", "Pyidaungsu", "ZAWGYI+Arial", null)) {
            assertFalse("$name", MyanmarGlyphText.isZawgyiName(name))
        }
    }

    @Test
    fun zawgyiCodePoints() {
        for (codePoint in listOf(0x1033, 0x1034, 0x1060, 0x1064, 0x108F, 0x1097)) {
            assertEquals(codePoint, MyanmarGlyphText.zawgyiCodePoint("က" + String(Character.toChars(codePoint))))
        }
        for (text in listOf("မြန်မာ", "သင်္ချာ", "ဗုဒ္ဓ", "ဿ၏", "\u105F", "\u1098", "Zawgyi")) {
            assertNull(text, MyanmarGlyphText.zawgyiCodePoint(text))
        }
    }

    @Test
    fun aGlyphTextWithAZawgyiCodePointMarksItsFont() {
        val lines = ArrayList<String>()
        val glyphs = MyanmarGlyphText(log = { lines += it })
        val unicode = GlyphFont("NotoSansMyanmar-Regular")
        assertEquals("ကျွန်ုပ်", glyphs.text(unicode, 1, "ကျွန်ုပ်"))
        assertFalse(unicode.zawgyi)
        for ((name, text) in listOf("A" to "\u108F", "B" to "\u1064")) {
            val font = GlyphFont(name)
            // The text stays as it is.
            assertEquals(text, glyphs.text(font, 1, text))
            assertTrue(name, font.zawgyi)
        }
        assertEquals(2, glyphs.counts().zawgyiFonts)
        assertEquals(
            listOf("A: Zawgyi (code point U+108F), kept as drawn", "B: Zawgyi (code point U+1064), kept as drawn"),
            lines,
        )
    }
}
