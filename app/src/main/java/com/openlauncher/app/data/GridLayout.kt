package com.openlauncher.app.data

// Layout primitives for the multi-page, customisable home-screen widget grid.
// Deliberately free of Compose/Android imports so the JVM unit tests in
// app/src/test can exercise the placement, resize and migration logic.

const val MAX_PAGES    = 6
const val MIN_COLS     = 2
const val MAX_COLS     = 6
const val MIN_ROWS     = 2
const val MAX_ROWS     = 4
const val DEFAULT_COLS = 3
const val DEFAULT_ROWS = 2

data class GridPage(
    val cols: Int = DEFAULT_COLS,
    val rows: Int = DEFAULT_ROWS
)

/**
 * One card on the grid. Unlike the old WidgetConfig model, instances are the
 * source of truth: a widget type can appear multiple times (on the same page
 * or across pages), and a type is "on" exactly while it has at least one
 * instance. `id` is globally unique — first instance keeps the bare type name
 * ("VOLUME"), later ones get "VOLUME~2", "VOLUME~3" … so old saved ids from
 * the singleton era keep working unchanged.
 */
data class WidgetInstance(
    val id: String,
    val type: String,      // "CLOCK" | "WEATHER" | … — drives which card renders
    val page: Int = 0,     // index into AppSettings.pages
    val gridX: Int = 0,
    val gridY: Int = 0,
    val spanX: Int = 1,
    val spanY: Int = 1
)

fun defaultPages() = listOf(GridPage())

fun defaultWidgetInstances() = listOf(
    WidgetInstance("CLOCK",       "CLOCK",       page = 0, gridX = 0, gridY = 0),
    WidgetInstance("WEATHER",     "WEATHER",     page = 0, gridX = 1, gridY = 0),
    WidgetInstance("TELEMETRY",   "TELEMETRY",   page = 0, gridX = 2, gridY = 0, spanX = 1, spanY = 2),
    WidgetInstance("NOW_PLAYING", "NOW_PLAYING", page = 0, gridX = 0, gridY = 1, spanX = 2, spanY = 1)
)

fun instancesOnPage(widgets: List<WidgetInstance>, page: Int): List<WidgetInstance> =
    widgets.filter { it.page == page }

fun nextInstanceId(existing: List<WidgetInstance>, type: String): String {
    if (existing.none { it.id == type }) return type
    var n = 2
    while (existing.any { it.id == "$type~$n" }) n++
    return "$type~$n"
}

fun firstFreeArea(
    occupiedBy: List<WidgetInstance>,
    cols: Int,
    rows: Int,
    spanX: Int,
    spanY: Int
): Pair<Int, Int>? {
    val occupied = buildSet<Pair<Int, Int>> {
        occupiedBy.forEach { w ->
            for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy)
        }
    }
    for (row in 0 until rows) for (col in 0 until cols) {
        if (col + spanX > cols || row + spanY > rows) continue
        if ((0 until spanX).all { dx -> (0 until spanY).all { dy -> (col + dx to row + dy) !in occupied } })
            return col to row
    }
    return null
}

fun firstFreeCell(occupiedBy: List<WidgetInstance>, cols: Int, rows: Int): Pair<Int, Int>? =
    firstFreeArea(occupiedBy, cols, rows, 1, 1)

private fun widgetsOverlap(a: WidgetInstance, b: WidgetInstance): Boolean =
    a.gridX < b.gridX + b.spanX && a.gridX + a.spanX > b.gridX &&
    a.gridY < b.gridY + b.spanY && a.gridY + a.spanY > b.gridY

/**
 * Moves [movingId] to ([targetX], [targetY]) within a page of [cols]×[rows] and
 * pushes any displaced widgets to the first available free cell, cascading until
 * all conflicts are resolved. Operates only on the supplied [layout] — callers
 * must pass only the instances that live on that page.
 */
fun computeWidgetMove(
    layout: List<WidgetInstance>,
    movingId: String,
    targetX: Int,
    targetY: Int,
    cols: Int,
    rows: Int
): List<WidgetInstance> {
    val moving = layout.find { it.id == movingId } ?: return layout
    val placed = moving.copy(
        gridX = targetX.coerceIn(0, cols - moving.spanX),
        gridY = targetY.coerceIn(0, rows - moving.spanY)
    )

    val others  = layout.filter { it.id != movingId }
    val result  = mutableListOf(placed)

    // Stable widgets that don't conflict go first; displaced ones are pushed afterwards
    val (stable, displaced) = others.partition { w -> result.none { widgetsOverlap(it, w) } }

    for (w in stable) result.add(w)

    for (w in displaced) {
        val pos = firstFreeArea(result, cols, rows, w.spanX, w.spanY)
        result.add(if (pos != null) w.copy(gridX = pos.first, gridY = pos.second) else w)
    }

    return result
}

/**
 * Re-flows the instances of a page onto a new [newCols]×[newRows] grid:
 * spans larger than the page are shrunk first, then cards are re-placed
 * greedily (largest first) at their old position when possible. Returns null
 * when the new page cannot hold all cards — callers should refuse the resize
 * and tell the user instead of silently dropping widgets.
 */
fun resizePageLayout(
    onPage: List<WidgetInstance>,
    newCols: Int,
    newRows: Int
): List<WidgetInstance>? {
    val shrunk = onPage.map {
        it.copy(
            spanX = it.spanX.coerceIn(1, newCols),
            spanY = it.spanY.coerceIn(1, newRows)
        )
    }
    val result = mutableListOf<WidgetInstance>()
    for (w in shrunk.sortedByDescending { it.spanX * it.spanY }) {
        // Try the old position first so cards mostly stay put; fall back to first free area
        val homeFree = result.none { widgetsOverlap(w, it) } &&
                       w.gridX + w.spanX <= newCols && w.gridY + w.spanY <= newRows
        val pos = if (homeFree) (w.gridX to w.gridY)
                  else firstFreeArea(result, newCols, newRows, w.spanX, w.spanY)
            ?: return null
        result.add(w.copy(gridX = pos.first, gridY = pos.second))
    }
    return result
}

// ── Migration from the pre-multi-page singleton model ─────────────────────────

/** Gson DTO for the old WIDGET_LAYOUT_JSON list; nullable fields default like the old data class. */
data class LegacyWidget(
    val id: String? = null,
    val gridX: Int? = null,
    val gridY: Int? = null,
    val spanX: Int? = null,
    val spanY: Int? = null,
    val enabled: Boolean? = null
)

/**
 * Converts the legacy single-grid layout + show-flags into one default 3×2 page
 * plus instances. Entries whose old `enabled` flag or `showX` setting was false
 * are dropped; types that were on but had no layout entry (older builds) get a
 * 1×1 card at the first free cell.
 */
fun migrateLegacyLayout(
    legacy: List<LegacyWidget>,
    explicitOnTypes: Set<String>,
    explicitOffTypes: Set<String>
): Pair<GridPage, List<WidgetInstance>> {
    val page = GridPage(DEFAULT_COLS, DEFAULT_ROWS)
    val out  = mutableListOf<WidgetInstance>()
    for (w in legacy) {
        val id = w.id ?: continue
        if (w.enabled == false) continue          // absent old `enabled` counts as true
        if (id in explicitOffTypes) continue      // showXxx was false
        val sx = (w.spanX ?: 1).coerceIn(1, DEFAULT_COLS)
        val sy = (w.spanY ?: 1).coerceIn(1, DEFAULT_ROWS)
        val gx = (w.gridX ?: 0).coerceIn(0, DEFAULT_COLS - sx)
        val gy = (w.gridY ?: 0).coerceIn(0, DEFAULT_ROWS - sy)
        out.add(WidgetInstance(id = id, type = id, page = 0, gridX = gx, gridY = gy, spanX = sx, spanY = sy))
    }
    for (t in explicitOnTypes) {
        if (out.any { it.type == t }) continue
        val pos = firstFreeCell(out, DEFAULT_COLS, DEFAULT_ROWS) ?: break
        out.add(WidgetInstance(id = nextInstanceId(out, t), type = t, page = 0, gridX = pos.first, gridY = pos.second))
    }
    return page to out
}
