package com.alalkipgen.alalpdf.reader

/**
 * Pure policy for the cold-start restore gate.
 *
 * A saved index cannot be validated until pageCount is known. Returning null
 * is intentional: page zero must not be treated as a valid fallback while the
 * PDF is still opening.
 */
internal object ReaderRestorePolicy {
    fun targetPage(savedPage: Int, pageCount: Int): Int? =
        if (pageCount <= 0) null else savedPage.coerceIn(0, pageCount - 1)

    fun canPersistSelection(pageCount: Int, restoreApplied: Boolean): Boolean =
        pageCount > 0 && restoreApplied
}