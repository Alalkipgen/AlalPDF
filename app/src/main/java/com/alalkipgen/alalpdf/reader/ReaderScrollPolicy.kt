package com.alalkipgen.alalpdf.reader

import kotlin.math.abs

/**
 * Scheduling rules for the reader's render queue while the list is moving.
 *
 * A fling is the one moment where doing *less* work looks better. Previously
 * every page that scrolled past composed a list item, and every list item
 * asked for a render or a thumbnail. Those requests all survived the fling and
 * were still being rasterized long after the page had left the screen, which
 * is what made the scroll stutter and then stall.
 *
 * While the list is scrolling only the page under the finger (and its
 * immediate neighbour) is worth native work. Everything else waits for the
 * list to settle, where it is either still relevant or dropped for free.
 */
internal object ReaderScrollPolicy {
    /** Pages this far from the focused page still render during a fling. */
    const val SCROLL_RENDER_DISTANCE = 1

    /** Hard ceiling on queued work so a long fling cannot grow the queue forever. */
    const val MAX_QUEUE_LENGTH = 48

    /** True when a queued request is still worth running during a fling. */
    fun runsWhileScrolling(isPage: Boolean, distance: Int): Boolean =
        isPage && distance <= SCROLL_RENDER_DISTANCE

    /** True when a queued request has drifted too far from the viewport to matter. */
    fun isStale(page: Int, focusedPage: Int, keepDistance: Int): Boolean =
        abs(page - focusedPage) > keepDistance
}
