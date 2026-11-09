package com.openlauncher.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the multi-page grid engine (placement, page resize,
 * duplicate ids, legacy migration). No Android/Compose involved — the
 * GridLayout.kt file deliberately stays framework-free.
 */
class GridLayoutTest {

    private fun w(id: String, page: Int = 0, x: Int, y: Int, sx: Int = 1, sy: Int = 1) =
        WidgetInstance(id = id, type = id.substringBefore('~'), page = page, gridX = x, gridY = y, spanX = sx, spanY = sy)

    // ── instance ids ─────────────────────────────────────────────────────────

    @Test
    fun firstInstanceKeepsBareTypeName() {
        assertEquals("VOLUME", nextInstanceId(emptyList(), "VOLUME"))
        assertEquals("VOLUME", nextInstanceId(listOf(w("CLOCK", x = 0, y = 0)), "VOLUME"))
    }

    @Test
    fun duplicatesGetNumericSuffixes() {
        val existing = listOf(w("VOLUME", x = 0, y = 0), w("VOLUME~2", x = 1, y = 0))
        assertEquals("VOLUME~3", nextInstanceId(existing, "VOLUME"))
        // Gaps are filled without renumbering existing cards
        val withGap  = listOf(w("VOLUME", x = 0, y = 0), w("VOLUME~3", x = 1, y = 0))
        assertEquals("VOLUME~2", nextInstanceId(withGap, "VOLUME"))
    }

    @Test
    fun idsStayUniqueAcrossPages() {
        val existing = listOf(w("VOLUME", page = 0, x = 0, y = 0), w("VOLUME~2", page = 2, x = 0, y = 0))
        assertEquals("VOLUME~3", nextInstanceId(existing, "VOLUME"))
    }

    // ── free area search respects per-page dimensions ────────────────────────

    @Test
    fun freeAreaHonoursPageBounds() {
        val page = listOf(w("A", x = 0, y = 0, sx = 2, sy = 2))
        // 2×2 page fully covered
        assertNull(firstFreeCell(page, cols = 2, rows = 2))
        // 3×2 page: (2,0) free
        assertEquals(Pair(2, 0), firstFreeCell(page, cols = 3, rows = 2))
        // a 1×2 span only fits in the third column of a 3×2 page
        assertEquals(Pair(2, 0), firstFreeArea(page, cols = 3, rows = 2, spanX = 1, spanY = 2))
    }

    @Test
    fun freeAreaWrapsToLaterRows() {
        val page = listOf(w("A", x = 0, y = 0), w("B", x = 1, y = 0), w("C", x = 2, y = 0))
        assertEquals(Pair(0, 1), firstFreeCell(page, cols = 3, rows = 2))
    }

    // ── move + push cascade with per-page dimensions ─────────────────────────

    @Test
    fun moveClampsIntoPageBounds() {
        val page = listOf(w("A", x = 0, y = 0), w("B", x = 1, y = 0))
        val moved = computeWidgetMove(page, "A", targetX = 99, targetY = 99, cols = 3, rows = 2)
        val a = moved.first { it.id == "A" }
        assertEquals(2, a.gridX)
        assertEquals(1, a.gridY)
    }

    @Test
    fun movePushesDisplacedCardIntoFreeCell() {
        // 3×2: A at (0,0), B at (1,0), free cells (2,0),(0,1),(1,1),(2,1)
        val page = listOf(w("A", x = 0, y = 0), w("B", x = 1, y = 0))
        val moved = computeWidgetMove(page, "B", targetX = 0, targetY = 0, cols = 3, rows = 2)
        assertEquals(Pair(0, 0), Pair(moved.first { it.id == "B" }.gridX, moved.first { it.id == "B" }.gridY))
        val a = moved.first { it.id == "A" }
        // A displaced — first free cell in row-major order is now (1,0) since B vacated it
        assertTrue(moved.none { x -> x.id != a.id && x.gridX == a.gridX && x.gridY == a.gridY })
        assertEquals(Pair(1, 0), Pair(a.gridX, a.gridY))
    }

    @Test
    fun multiCellPushCascadeUsesPageBounds() {
        // 4×3: 1×2 tall card pushed from row 2 to row 3 still fits
        val page = listOf(w("TALL", x = 0, y = 0, sy = 2), w("A", x = 0, y = 2, sy = 2))
        val moved = computeWidgetMove(page, "A", targetX = 0, targetY = 0, cols = 4, rows = 3)
        // A takes (0,0); TALL pushed — first free area for its 1×2 span
        val tall = moved.first { it.id == "TALL" }
        assertTrue(tall.gridY + tall.spanY <= 3)
        assertTrue(tall.gridX + tall.spanX <= 4)
    }

    // ── corner resize (drag handles in edit mode) ────────────────────────────

    private fun cells(widgets: List<WidgetInstance>) = buildSet {
        widgets.forEach { wd ->
            for (dx in 0 until wd.spanX) for (dy in 0 until wd.spanY) add(wd.gridX + dx to wd.gridY + dy)
        }
    }

    @Test
    fun cornerResizeReturnsNullForUnknownId() {
        val page = listOf(w("A", x = 0, y = 0))
        assertNull(computeCornerResize(page, "GHOST", ResizeCorner.BOTTOM_END, 1, 1, cols = 3, rows = 2))
    }

    @Test
    fun endCornerGrowsSpanOriginStays() {
        val page = listOf(w("A", x = 1, y = 1))
        val r = computeCornerResize(page, "A", ResizeCorner.BOTTOM_END, 1, 1, cols = 4, rows = 4)!!
        assertEquals((1 to 1), (r.gridX to r.gridY))
        assertEquals((2 to 2), (r.spanX to r.spanY))
    }

    @Test
    fun endCornerShrinks() {
        val page = listOf(w("A", x = 1, y = 1, sx = 2, sy = 2))
        val r = computeCornerResize(page, "A", ResizeCorner.BOTTOM_END, -1, -1, cols = 4, rows = 4)!!
        assertEquals((1 to 1), (r.gridX to r.gridY))
        assertEquals((1 to 1), (r.spanX to r.spanY))
    }

    @Test
    fun startCornerMovesOriginFarEdgeStays() {
        // TOP_START: right edge (x+sx = 4) and bottom edge (y+sy = 4) must stay put
        val page = listOf(w("A", x = 1, y = 1, sx = 2, sy = 2))
        val r = computeCornerResize(page, "A", ResizeCorner.TOP_START, -1, -1, cols = 4, rows = 4)!!
        assertEquals((0 to 0), (r.gridX to r.gridY))
        assertEquals((3 to 3), (r.spanX to r.spanY))
    }

    @Test
    fun bottomStartCornerMovesLeftAndBottomEdgesOnly() {
        // Grabbing the bottom-left corner: only the left and bottom edges follow
        // the finger; top and right stay put, so X moves but Y does not.
        val page = listOf(w("A", x = 1, y = 1, sx = 2, sy = 2))
        val r = computeCornerResize(page, "A", ResizeCorner.BOTTOM_START, -1, -1, cols = 4, rows = 4)!!
        assertEquals((0 to 1), (r.gridX to r.gridY))
        assertEquals((3 to 1), (r.spanX to r.spanY))
    }

    @Test
    fun endCornerClampsToPageBounds() {
        val page = listOf(w("A", x = 0, y = 0))
        val r = computeCornerResize(page, "A", ResizeCorner.BOTTOM_END, 99, 99, cols = 4, rows = 3)!!
        assertEquals((4 to 3), (r.spanX to r.spanY))
    }

    @Test
    fun startCornerClampsToPageBounds() {
        val page = listOf(w("A", x = 2, y = 1, sx = 2, sy = 2))
        val r = computeCornerResize(page, "A", ResizeCorner.TOP_START, -99, -99, cols = 4, rows = 3)!!
        assertEquals((0 to 0), (r.gridX to r.gridY))
        assertEquals((4 to 3), (r.spanX to r.spanY))
    }

    @Test
    fun startCornerCannotCrossItsOwnFarEdge() {
        // sanity: BOTTOM_END on this 1×1 card just grows the span
        val page = listOf(w("A", x = 1, y = 1))
        val grow = computeCornerResize(page, "A", ResizeCorner.BOTTOM_END, 5, 5, cols = 9, rows = 9)!!
        assertEquals((1 to 1), (grow.gridX to grow.gridY))
        assertEquals(6, grow.spanX)
        // dragging TOP_START far right/up: origin is clamped at the far edge (x+sx-1 = 1),
        // so the card shrinks to a minimum 1×1 instead of flipping inside out
        val back = computeCornerResize(page, "A", ResizeCorner.TOP_START, 5, 5, cols = 9, rows = 9)!!
        assertEquals((1 to 1), (back.gridX to back.gridY))
        assertEquals((1 to 1), (back.spanX to back.spanY))
    }

    @Test
    fun allCornersClampToMinimumOneCell() {
        val page = listOf(w("A", x = 1, y = 1, sx = 2, sy = 2))
        for (corner in ResizeCorner.entries) {
            val r = computeCornerResize(page, "A", corner, -5, -5, cols = 4, rows = 4)!!
            assertTrue(r.spanX >= 1)
            assertTrue(r.spanY >= 1)
            assertTrue(r.gridX >= 0 && r.gridY >= 0)
            assertTrue(r.gridX + r.spanX <= 4 && r.gridY + r.spanY <= 4)
        }
    }

    @Test
    fun endCornerExpansionStopsAtNeighbourNoPush() {
        // 4×3: neighbour N occupies (1..2, 0) — growing A right into it must be refused
        val page = listOf(w("A", x = 0, y = 0), w("N", x = 1, y = 0, sx = 2))
        val r = computeCornerResize(page, "A", ResizeCorner.BOTTOM_END, 2, 0, cols = 4, rows = 3)!!
        assertEquals((0 to 0), (r.gridX to r.gridY))
        assertEquals(1, r.spanX)
        // the resized rect must never share a cell with the neighbour
        val n = page.first { it.id == "N" }
        assertTrue(cells(listOf(r)).none { c ->
            c.first in n.gridX until n.gridX + n.spanX && c.second in n.gridY until n.gridY + n.spanY
        })
    }

    @Test
    fun expansionStopsAgainstNeighbourBelow() {
        val page = listOf(w("A", x = 0, y = 0), w("N", x = 0, y = 1))
        val r = computeCornerResize(page, "A", ResizeCorner.BOTTOM_END, 1, 1, cols = 4, rows = 3)!!
        assertEquals(2, r.spanX) // width still free to grow
        assertEquals(1, r.spanY) // height blocked by N
    }

    @Test
    fun backoffKeepsLargestClearArea() {
        // 4×4: A (2×2) dragged BOTTOM_END to a full-screen 4×4. N1 blocks column 3,
        // N2 blocks (2,3). No full-width OR full-height expansion survives, but a
        // 3×3 (area 9) does — the back-off must find it, not give up at 2×4 (area 8).
        val page = listOf(
            w("A", x = 0, y = 0, sx = 2, sy = 2),
            w("N1", x = 3, y = 0, sx = 1, sy = 4),
            w("N2", x = 2, y = 3, sx = 1, sy = 1)
        )
        val r = computeCornerResize(page, "A", ResizeCorner.BOTTOM_END, 2, 2, cols = 4, rows = 4)!!
        assertEquals((0 to 0), (r.gridX to r.gridY))
        assertEquals((3 to 3), (r.spanX to r.spanY))
    }

    @Test
    fun startCornerExpansionStopsAtNeighbour() {
        // neighbour M sits at (0,0); dragging A's TOP_START left would cover it → refused
        val page = listOf(w("M", x = 0, y = 0), w("A", x = 1, y = 0))
        val r = computeCornerResize(page, "A", ResizeCorner.TOP_START, -1, 0, cols = 3, rows = 2)!!
        assertEquals((1 to 0), (r.gridX to r.gridY))
        assertEquals(1, r.spanX)
    }

    @Test
    fun topEndDragGrowsUpButStopsUnderNeighbour() {
        // N occupies (2, 0); A's TOP_END corner at (3, 0) dragged up covers (2,0),(3,0) → refused
        val page = listOf(w("N", x = 2, y = 0), w("A", x = 2, y = 1, sx = 2, sy = 2))
        val r = computeCornerResize(page, "A", ResizeCorner.TOP_END, 0, -1, cols = 4, rows = 3)!!
        assertEquals((2 to 1), (r.gridX to r.gridY))
        assertEquals((2 to 2), (r.spanX to r.spanY))
    }

    @Test
    fun resizeResultNeverOverlapsNeighbours() {
        val page = listOf(w("A", x = 0, y = 0), w("N1", x = 2, y = 0, sx = 2), w("N2", x = 0, y = 2, sx = 2, sy = 1))
        for (corner in ResizeCorner.entries) {
            for (dx in -3..5) for (dy in -3..5) {
                val r = computeCornerResize(page, "A", corner, dx, dy, cols = 4, rows = 3)!!
                assertTrue(r.gridX >= 0 && r.gridY >= 0)
                assertTrue(r.gridX + r.spanX <= 4 && r.gridY + r.spanY <= 3)
                assertTrue(
                    page.filter { it.id != "A" }.none { n ->
                        cells(listOf(r)).any { c ->
                            c.first in n.gridX until n.gridX + n.spanX &&
                            c.second in n.gridY until n.gridY + n.spanY
                        }
                    }
                )
            }
        }
    }

    // ── page resize (setPageSize guard) ──────────────────────────────────────

    @Test
    fun resizeGrowKeepsCardsHome() {
        val page = listOf(w("A", x = 2, y = 1), w("B", x = 0, y = 0, sx = 2))
        val resized = resizePageLayout(page, newCols = 6, newRows = 2)
        assertNotNull(resized)
        // Every card kept its exact position when the page only grew
        assertEquals(page.map { it.id to (it.gridX to it.gridY) }.toSet(),
                     resized!!.map { it.id to (it.gridX to it.gridY) }.toSet())
    }

    @Test
    fun resizeShrinkReplacesOverflowingPositions() {
        // 4×2 page → 3×2: the card at column 3 must land somewhere valid
        val page = listOf(w("A", x = 0, y = 0), w("B", x = 1, y = 0), w("C", x = 2, y = 0), w("D", x = 3, y = 0))
        val resized = resizePageLayout(page, newCols = 3, newRows = 2)
        assertNotNull(resized)
        // Three cards fit row 0, the fourth wraps to row 1
        assertEquals(4, resized!!.size)
        assertEquals(
            setOf(0 to 0, 1 to 0, 2 to 0, 0 to 1),
            resized.map { it.gridX to it.gridY }.toSet()
        )
    }

    @Test
    fun resizeShrinkRefusesWhenCardsDoNotFit() {
        val page = listOf(w("A", x = 0, y = 0), w("B", x = 1, y = 0), w("C", x = 2, y = 0), w("D", x = 3, y = 0))
        // 3×2 = 6 cells hold four 1×1 fine; 2×2 = 4 cells also hold them exactly.
        assertNotNull(resizePageLayout(page, newCols = 3, newRows = 2))
        assertNotNull(resizePageLayout(page, newCols = 2, newRows = 2))
        // A fifth card cannot fit a 2×2 page → null so the caller refuses the resize
        val crowded = page + w("E", x = 0, y = 1)
        assertNull(resizePageLayout(crowded, newCols = 2, newRows = 2))
    }

    @Test
    fun resizeShrinkClampsOversizedSpans() {
        val page = listOf(w("BIG", x = 0, y = 0, sx = 4, sy = 2))
        val resized = resizePageLayout(page, newCols = 2, newRows = 2)
        assertNotNull(resized)
        assertEquals(2, resized!!.first().spanX)
        assertEquals(2, resized.first().spanY)
    }

    // ── pages helpers ────────────────────────────────────────────────────────

    @Test
    fun pageFilterSeparatesInstances() {
        val all = listOf(w("A", page = 0, x = 0, y = 0), w("B", page = 1, x = 0, y = 0), w("C", page = 1, x = 1, y = 0))
        assertEquals(listOf("B", "C"), instancesOnPage(all, 1).map { it.id })
        assertEquals(listOf("A"), instancesOnPage(all, 0).map { it.id })
    }

    // ── legacy migration ─────────────────────────────────────────────────────

    @Test
    fun migrateKeepsEnabledOnTypesAndDropsDisabled() {
        val legacy = listOf(
            LegacyWidget(id = "CLOCK",       gridX = 0, gridY = 0),
            LegacyWidget(id = "WEATHER",     gridX = 1, gridY = 0),
            LegacyWidget(id = "TELEMETRY",   gridX = 2, gridY = 0, spanY = 2),
            LegacyWidget(id = "NOW_PLAYING", gridX = 0, gridY = 1, spanX = 2),
            LegacyWidget(id = "VOLUME",      gridX = 1, gridY = 1, enabled = false),
            LegacyWidget(id = "TPMS",        gridX = 2, gridY = 1)
        )
        val (page, out) = migrateLegacyLayout(
            legacy,
            explicitOnTypes  = setOf("CLOCK", "WEATHER", "TELEMETRY", "NOW_PLAYING", "TPMS"),
            explicitOffTypes = setOf("VOLUME")
        )
        assertEquals(GridPage(3, 2), page)
        assertEquals(setOf("CLOCK", "WEATHER", "TELEMETRY", "NOW_PLAYING", "TPMS"), out.map { it.id }.toSet())
        // Positions carried over, everything on page 0
        val clock = out.first { it.id == "CLOCK" }
        assertEquals(0, clock.gridX)
        assertEquals(0, clock.page)
        // enabled=false entry (VOLUME) dropped even though its flag is not in off-set
        assertTrue(out.none { it.id == "VOLUME" })
    }

    @Test
    fun migrateAddsFlaggedTypesMissingFromLayout() {
        val legacy = listOf(LegacyWidget(id = "CLOCK", gridX = 0, gridY = 0))
        val (_, out) = migrateLegacyLayout(
            legacy,
            explicitOnTypes  = setOf("CLOCK", "VOLUME"),
            explicitOffTypes = emptySet()
        )
        val volume = out.first { it.type == "VOLUME" }
        assertEquals(1, volume.gridX) // first free cell on a 3×2 page
        assertEquals(1, volume.spanX)
        assertEquals(1, volume.spanY)
    }

    @Test
    fun migrateClampsOldCoordinatesIntoDefaultGrid() {
        // Defensive: garbage coordinates from a corrupted store
        val legacy = listOf(LegacyWidget(id = "CLOCK", gridX = 17, gridY = -4, spanX = 99))
        val (_, out) = migrateLegacyLayout(legacy, setOf("CLOCK"), emptySet())
        val clock = out.first()
        // span clamped to the whole default page width, then the position into it
        assertEquals(0, clock.gridX)
        assertEquals(0, clock.gridY)
        assertEquals(3, clock.spanX)
    }

    @Test
    fun defaultsMatchLegacyStockLayout() {
        // Stock 3×2 defaults must survive a resize guard round-trip unchanged
        val instances = defaultWidgetInstances()
        val page      = defaultPages().first()
        assertEquals(4, instances.size)
        assertNotNull(resizePageLayout(instances, page.cols, page.rows))
    }
}
