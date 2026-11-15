package com.openlauncher.app.ui.widget

import com.openlauncher.app.model.NowPlayingQueueItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the Now Playing queue sheet's current-track highlight and
 * the inline-queue / volume-readout helpers (pure helpers in
 * NowPlayingWidget.kt — no framework needed).
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
        assertEquals(-1, queueHighlightIndex(99L, queue, "Not in this queue"))
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

    // ── inline-queue layout decision + volume readout ──────────────────────────

    @Test
    fun `queue opens inline only from 4 columns and 4 rows up`() {
        assertEquals(false, inlineQueueAllowed(spanColumns = 3, spanRows = 4))
        assertEquals(false, inlineQueueAllowed(spanColumns = 4, spanRows = 3))
        assertEquals(false, inlineQueueAllowed(spanColumns = 6, spanRows = 2))
        assertEquals(true, inlineQueueAllowed(spanColumns = 4, spanRows = 4))
        assertEquals(true, inlineQueueAllowed(spanColumns = 5, spanRows = 4))
        assertEquals(true, inlineQueueAllowed(spanColumns = 6, spanRows = 4))
    }

    @Test
    fun `volume percentage rounds to the nearest whole percent`() {
        assertEquals(0, volumePercent(current = 0, max = 15))
        assertEquals(100, volumePercent(current = 15, max = 15))
        // 8/15 = 53.33% → 53
        assertEquals(53, volumePercent(current = 8, max = 15))
        // 7/20 = 35%
        assertEquals(35, volumePercent(current = 7, max = 20))
    }

    @Test
    fun `volume percentage is safe with an unusable max and out-of-range reads`() {
        assertEquals(0, volumePercent(current = 3, max = 0))
        assertEquals(0, volumePercent(current = 3, max = -1))
        // Some head-unit audio HALs report above-max or negative values.
        assertEquals(100, volumePercent(current = 99, max = 15))
        assertEquals(0, volumePercent(current = -2, max = 15))
    }

    @Test
    fun `swipe maps to transport actions past the slop threshold`() {
        // Swipe left (negative dx) past slop -> next (+1)
        assertEquals(1, swipeTransportAction(deltaPx = -120f, slopPx = 60f))
        // Swipe right -> previous (-1)
        assertEquals(-1, swipeTransportAction(deltaPx = 120f, slopPx = 60f))
        // Exactly at the threshold counts.
        assertEquals(1, swipeTransportAction(deltaPx = -60f, slopPx = 60f))
        // Under slop (or a tap) does nothing.
        assertEquals(0, swipeTransportAction(deltaPx = -59f, slopPx = 60f))
        assertEquals(0, swipeTransportAction(deltaPx = 12f, slopPx = 60f))
        assertEquals(0, swipeTransportAction(deltaPx = 0f, slopPx = 60f))
    }
}
