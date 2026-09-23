package com.alalkipgen.alalpdf.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScrollPolicyTest {
    @Test
    fun duringFling_onlyTheFocusedNeighbourhoodRenders() {
        assertTrue(ReaderScrollPolicy.runsWhileScrolling(isPage = true, distance = 0))
        assertTrue(ReaderScrollPolicy.runsWhileScrolling(isPage = true, distance = 1))
        assertFalse(ReaderScrollPolicy.runsWhileScrolling(isPage = true, distance = 2))
    }

    @Test
    fun duringFling_thumbnailsNeverRun() {
        assertFalse(ReaderScrollPolicy.runsWhileScrolling(isPage = false, distance = 0))
    }

    @Test
    fun staleRequestsAreDroppedOutsideTheKeepWindow() {
        assertFalse(ReaderScrollPolicy.isStale(page = 12, focusedPage = 10, keepDistance = 2))
        assertTrue(ReaderScrollPolicy.isStale(page = 13, focusedPage = 10, keepDistance = 2))
        assertTrue(ReaderScrollPolicy.isStale(page = 7, focusedPage = 10, keepDistance = 2))
    }

    @Test
    fun queueLengthIsBounded() {
        assertTrue(ReaderScrollPolicy.MAX_QUEUE_LENGTH in 8..256)
    }
}
