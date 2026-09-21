package com.alalkipgen.alalpdf.reader

data class PdfPageText(val page: Int, val text: String)

/** One hit inside a page. [matchIndex] distinguishes repeats on the same page. */
data class PdfSearchResult(val page: Int, val excerpt: String, val matchIndex: Int = 0)

/**
 * Search helper.
 *
 * The old implementation called `indexOf` once per page, so a page containing
 * a word ten times reported a single hit, and Zawgyi documents matched nothing
 * at all because the query was Unicode.
 */
object PdfTextSearch {

    private const val MAX_MATCHES_PER_PAGE = 25
    private const val EXCERPT_BEFORE = 40
    private const val EXCERPT_AFTER = 60

    fun matches(page: Int, pageText: String, query: String): List<PdfSearchResult> {
        val needle = MyanmarText.searchKey(query.trim())
        if (needle.isEmpty()) return emptyList()
        val haystack = MyanmarText.searchKey(pageText)
        if (haystack.isEmpty()) return emptyList()

        val results = ArrayList<PdfSearchResult>()
        var cursor = haystack.indexOf(needle, 0, ignoreCase = true)
        while (cursor >= 0 && results.size < MAX_MATCHES_PER_PAGE) {
            val from = (cursor - EXCERPT_BEFORE).coerceAtLeast(0)
            val to = (cursor + needle.length + EXCERPT_AFTER).coerceAtMost(haystack.length)
            results.add(
                PdfSearchResult(
                    page = page,
                    excerpt = haystack.substring(from, to).replace('\n', ' ').trim(),
                    matchIndex = results.size,
                )
            )
            cursor = haystack.indexOf(needle, cursor + needle.length, ignoreCase = true)
        }
        return results
    }
}
