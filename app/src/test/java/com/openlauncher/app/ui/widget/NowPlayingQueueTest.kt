package com.openlauncher.app.ui.widget

import com.openlauncher.app.model.NowPlayingQueueItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the Now Playing playlist sheet's current-track highlight
 * (pure helper queueHighlightIndex in NowPlayingWidget.kt — no framework needed).
 */
class NowPlayingQueueTest {

    private val queue = listOf(
        NowPlayingQueueItem(id = 10, title = "First Track", subtitle = "Artist A"),
        NowPlayingQueueItem(id = 20, title = "Second Track", subtitle = "Artist B"),
        NowPlayingQueueItem(id = 30, title = "Third Track", subtitle = "")
    )

    @Test
    fun `active queue item id wins when it is present`() {
        // Title deliberately disagrees with the id — the id must be authoritative.
        assertEquals(2, queueHighlightIndex(30L, queue, "First Track"))
    }

    @Test
    fun `stale id falls back to title match`() {
        // Some apps report an activeQueueItemId that is not (yet) in the queue.
        assertEquals(1, queueHighlightIndex(25L, queue, "Second Track"))
    }

    @Test
    fun `missing id (-1) uses title match only`() {
        assertEquals(0, queueHighlightIndex(-1L, queue, "First Track"))
    }

    @Test
    fun `title match is case-insensitive and ignores surrounding whitespace`() {
        assertEquals(1, queueHighlightIndex(-1L, queue, "  second track \t"))
    }

    @Test
    fun `nothing matches returns -1`() {
        assertEquals(-1, queueHighlightIndex(99L, queue, "Not in this playlist"))
        assertEquals(-1, queueHighlightIndex(-1L, queue, "Nope"))
    }

    @Test
    fun `absent or blank playing title never highlights via title`() {
        assertEquals(-1, queueHighlightIndex(-1L, queue, null))
        assertEquals(-1, queueHighlightIndex(-1L, queue, ""))
        assertEquals(-1, queueHighlightIndex(-1L, queue, "   "))
        // …but a live id still works with no title at all.
        assertEquals(2, queueHighlightIndex(30L, queue, null))
    }

    @Test
    fun `empty queue yields -1`() {
        assertEquals(-1, queueHighlightIndex(10L, emptyList(), "First Track"))
    }
}
