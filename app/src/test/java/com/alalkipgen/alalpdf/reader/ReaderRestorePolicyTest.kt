package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderRestorePolicyTest {
    @Test
    fun `does not clamp saved page while page count is loading`() {
        assertNull(ReaderRestorePolicy.targetPage(savedPage = 36, pageCount = 0))
        assertFalse(ReaderRestorePolicy.canPersistSelection(pageCount = 0, restoreApplied = false))
    }

    @Test
    fun `restores the saved page when count arrives`() {
        assertEquals(36, ReaderRestorePolicy.targetPage(savedPage = 36, pageCount = 100))
        assertTrue(ReaderRestorePolicy.canPersistSelection(pageCount = 100, restoreApplied = true))
    }

    @Test
    fun `clamps only after the final page count is known`() {
        assertEquals(9, ReaderRestorePolicy.targetPage(savedPage = 36, pageCount = 10))
        assertEquals(0, ReaderRestorePolicy.targetPage(savedPage = -5, pageCount = 10))
    }
}