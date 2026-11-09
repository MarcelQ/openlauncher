package com.openlauncher.app.ui.screen

import android.content.res.Configuration
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.ClockStyle
import com.openlauncher.app.data.GridPage
import com.openlauncher.app.data.MAX_COLS
import com.openlauncher.app.data.MAX_PAGES
import com.openlauncher.app.data.MAX_ROWS
import com.openlauncher.app.data.MIN_COLS
import com.openlauncher.app.data.MIN_ROWS
import com.openlauncher.app.data.ResizeCorner
import com.openlauncher.app.data.WidgetInstance
import com.openlauncher.app.data.computeCornerResize
import com.openlauncher.app.data.computeWidgetMove
import com.openlauncher.app.data.instancesOnPage
import com.openlauncher.app.data.resizePageLayout
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.ui.theme.LocalDayMode
import com.openlauncher.app.ui.widget.*
import java.util.Calendar
import com.openlauncher.app.util.LocationData

private val WIDGET_RADIUS = RoundedCornerShape(0.dp)

// Corner-drag resize handles (edit mode): 22dp touch target, 12dp visible square
private val RESIZE_HANDLE_SIZE = 22.dp
private val RESIZE_HANDLE_DOT  = 12.dp

// Card header labels (the small "VOLUME" caption) are hidden below this cell
// height; VolumeWidget/BrightnessWidget switch to compact layouts around the
// same size, so keep these thresholds roughly in sync.
private val HEADER_LABEL_MIN_H = 90.dp

private data class WidgetTypeInfo(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val description: String
)

private val ALL_WIDGET_TYPES = listOf(
    WidgetTypeInfo("CLOCK",       "CLOCK",       Icons.Default.AccessTime,  "Time & date"),
    WidgetTypeInfo("WEATHER",     "WEATHER",     Icons.Default.Cloud,       "Current conditions"),
    WidgetTypeInfo("NOW_PLAYING", "NOW PLAYING", Icons.Default.MusicNote,   "Media controls"),
    WidgetTypeInfo("TELEMETRY",   "COMPASS",     Icons.Default.Explore,     "Speed & heading"),
    WidgetTypeInfo("ALTIMETER",   "ALTIMETER",   Icons.Default.FlightTakeoff, "Roll, pitch & altitude"),
    WidgetTypeInfo("SPEEDOMETER", "SPEED",       Icons.Default.Speed,         "GPS speed"),
    WidgetTypeInfo("VITALS",      "VITALS",      Icons.Default.Dns,           "Head Unit Health / Vitals"),
    WidgetTypeInfo("TRIP_TRACKER", "TRIP TRACKER", Icons.Default.Map,          "Trip logs & stats"),
    WidgetTypeInfo("SOUNDBOARD",  "SOUNDBOARD",  Icons.Default.Piano,         "Custom sound pads"),
    WidgetTypeInfo("VOLUME",      "VOLUME",      Icons.Default.VolumeUp,      "Media volume control"),
    WidgetTypeInfo("BRIGHTNESS",  "BRIGHTNESS",  Icons.Default.WbSunny,       "Screen brightness presets"),
    WidgetTypeInfo("TPMS",        "TIRE PRESSURE", Icons.Default.Usb,         "Tyre pressure (USB receiver)")
)

/** True when a new 1×1 card could land on [pageIndex] — free cell, or room freed by shrinking. */
private fun canAddOnPage(settings: AppSettings, pageIndex: Int): Boolean {
    val dims = settings.pages.getOrNull(pageIndex) ?: return false
    val onPage = settings.widgets.filter { it.page == pageIndex }
    val occupied = buildSet<Pair<Int, Int>> {
        onPage.forEach { w ->
            for (dx in 0 until w.spanX) for (dy in 0 until w.spanY) add(w.gridX + dx to w.gridY + dy)
        }
    }
    val hasFreeCell = (0 until dims.rows).any { r ->
        (0 until dims.cols).any { c -> (c to r) !in occupied }
    }
    // Also true if any card on the page spans >1 cell and can be shrunk to make room
    val hasShrinkable = onPage.any { it.spanX * it.spanY > 1 }
    return hasFreeCell || hasShrinkable
}

private fun cardLabel(type: String): String = when (type) {
    "CLOCK"       -> "CLOCK"
    "WEATHER"     -> "WEATHER"
    "NOW_PLAYING" -> "NOW PLAYING"
    "TELEMETRY"   -> "COMPASS"
    "ALTIMETER"   -> "ALTIMETER"
    "SPEEDOMETER" -> "SPEED"
    "TRIP_TRACKER" -> "TRIP"
    "SOUNDBOARD"  -> "SOUND"
    "TPMS"        -> "TIRE PRESSURE"
    else          -> type
}

@Composable
fun HomeScreen(
    settings: AppSettings,
    weather: WeatherState?,
    nowPlaying: NowPlayingState?,
    location: LocationData?,
    bearing: Float,
    isWifi: Boolean,
    isData: Boolean,
    isDayMode: Boolean = false,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onLaunchCarPlay: () -> Unit,
    onLaunchAndroidAuto: () -> Unit,
    onAssignCarPlay: () -> Unit,
    onAssignAndroidAuto: () -> Unit,
    onClearCarPlay: () -> Unit,
    onClearAndroidAuto: () -> Unit,
    onAssignPip: () -> Unit,
    onClearPip: () -> Unit,
    onLaunchPip: () -> Unit,
    onTapNowPlaying: () -> Unit,
    onResizeWidget: (id: String, gridX: Int, gridY: Int, spanX: Int, spanY: Int) -> Unit,
    onMoveWidget: (id: String, gridX: Int, gridY: Int) -> Unit,
    onAddWidget: (type: String, page: Int) -> Unit,
    onRemoveWidget: (id: String) -> Unit,
    onMoveWidgetToPage: (id: String, page: Int) -> Unit,
    onAddPage: () -> Unit,
    onRemovePage: (page: Int) -> Unit,
    onSetPageSize: (page: Int, cols: Int, rows: Int) -> Unit,
    onSetClockStyle: (ClockStyle) -> Unit,
    onSetVitalsAsBars: (Boolean) -> Unit = {},
    onSetSpeedometerDigitalOnly: (Boolean) -> Unit = {},
    onUpdateSoundPad: (index: Int, pad: com.openlauncher.app.data.SoundPadConfig) -> Unit = { _, _ -> },
    hardwareRadio: com.openlauncher.app.viewmodel.LauncherViewModel.HardwareRadioState? = null,
    tpms: com.openlauncher.app.tpms.TpmsSnapshot = com.openlauncher.app.tpms.TpmsSnapshot(),
    onTpmsConnect: () -> Unit = {},
    onLaunchHardwareRadio: () -> Unit = {},
    onStopHardwareRadio: () -> Unit = {},
    onRadioSeekUp: () -> Unit = {},
    onRadioSeekDown: () -> Unit = {},
    onRadioCycleFm: () -> Unit = {},
    onRadioSwitchAm: () -> Unit = {},
    onRadioTune: (band: String, freq: Float) -> Unit = { _, _ -> },
    onAssignRadio: () -> Unit = {},
    onSetBrightnessLevel: (slot: String, pct: Int) -> Unit = { _, _ -> },
    onSetBrightnessAuto: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val accent       = Color(settings.accentColor)
    val gap          = 6.dp
    val hasWallpaper = settings.wallpaperUri.isNotEmpty()
    val widgetBg     = when {
        isDayMode    -> Color(0xFFFFFFFF)
        hasWallpaper -> Color(0xCC000000)
        else         -> Color.Black.copy(alpha = 0.35f)
    }
    val widgetBorder = when {
        isDayMode    -> Color(0xFFCCCCCC)
        hasWallpaper -> Color(0x22FFFFFF)
        else         -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    }
    val headerTextColor   = if (isDayMode) Color(0xFF111111) else accent
    val statusIconColor   = if (isDayMode) Color(0xFF444444) else Color(0xFF666666)
    val controlIconColor  = if (isDayMode) Color(0xFF666666) else Color(0xFF444444)

    var contextMenuId by remember { mutableStateOf<String?>(null) }

    val configuration    = LocalConfiguration.current
    val isLandscape      = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var editMode         by remember { mutableStateOf(false) }
    var widgetLibraryOpen by remember { mutableStateOf(false) }
    var gridDialogPage  by remember { mutableStateOf<Int?>(null) }

    // Swipeable home screens. pageCount is a lambda so the pager follows
    // pages added/removed in edit mode without recreating pager state.
    val pagerState = rememberPagerState(pageCount = { settings.pages.size.coerceAtLeast(1) })
    val currentPage = pagerState.currentPage.coerceIn(0, settings.pages.size - 1)

    Column(modifier = modifier.fillMaxSize()) {

        // ── Header ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text          = settings.vehicleName.uppercase(),
                style         = MaterialTheme.typography.titleLarge,
                color         = headerTextColor,
                letterSpacing = 3.sp,
                fontSize      = 14.sp
            )
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(visible = isWifi, enter = fadeIn(), exit = fadeOut()) {
                Icon(Icons.Default.Wifi, "WiFi", tint = statusIconColor, modifier = Modifier.size(16.dp))
            }
            if (isWifi) Spacer(Modifier.width(6.dp))
            AnimatedVisibility(visible = isData, enter = fadeIn(), exit = fadeOut()) {
                Icon(Icons.Default.SignalCellularAlt, "Data", tint = statusIconColor, modifier = Modifier.size(16.dp))
            }
            if (isLandscape) {
                Spacer(Modifier.width(8.dp))
                if (editMode) {
                    IconButton(
                        onClick  = { widgetLibraryOpen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Default.Dashboard,
                            contentDescription = "Widget library",
                            tint               = controlIconColor,
                            modifier           = Modifier.size(15.dp)
                        )
                    }
                    Spacer(Modifier.width(2.dp))
                }
                IconButton(
                    onClick  = { editMode = !editMode },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector        = Icons.Default.Edit,
                        contentDescription = "Edit widgets",
                        tint               = if (editMode) accent else controlIconColor,
                        modifier           = Modifier.size(15.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF141414))

        // ── Paged widget grids ──────────────────────────────────────────────
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(
                state    = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { pageIndex ->
                val page = settings.pages.getOrElse(pageIndex) { GridPage() }
                WidgetPageGrid(
                    settings      = settings,
                    pageIndex     = pageIndex,
                    page          = page,
                    gap           = gap,
                    accent        = accent,
                    widgetBg      = widgetBg,
                    widgetBorder  = widgetBorder,
                    isDayMode     = isDayMode,
                    editMode      = editMode,
                    weather       = weather,
                    nowPlaying    = nowPlaying,
                    location      = location,
                    bearing       = bearing,
                    tpms          = tpms,
                    hardwareRadio = hardwareRadio,
                    onMoveWidget  = onMoveWidget,
                    onResizeWidget = onResizeWidget,
                    onOpenMenu    = { contextMenuId = it },
                    onPlayPause   = onPlayPause,
                    onNext        = onNext,
                    onPrev        = onPrev,
                    onLaunchCarPlay     = onLaunchCarPlay,
                    onLaunchAndroidAuto = onLaunchAndroidAuto,
                    onTapNowPlaying     = onTapNowPlaying,
                    onLaunchHardwareRadio = onLaunchHardwareRadio,
                    onStopHardwareRadio   = onStopHardwareRadio,
                    onRadioSeekUp         = onRadioSeekUp,
                    onRadioSeekDown       = onRadioSeekDown,
                    onRadioCycleFm        = onRadioCycleFm,
                    onRadioSwitchAm       = onRadioSwitchAm,
                    onRadioTune           = onRadioTune,
                    onAssignRadio         = onAssignRadio,
                    onTpmsConnect         = onTpmsConnect,
                    onLaunchPip           = onLaunchPip,
                    onUpdateSoundPad      = onUpdateSoundPad
                )
            }

            // Page dots + edit-mode page/grid controls, floating bottom-center
            if (settings.pages.size > 1 || editMode) {
                PageIndicatorBar(
                    modifier     = Modifier.align(Alignment.BottomCenter),
                    pagerState   = pagerState,
                    currentPage  = currentPage,
                    pages        = settings.pages,
                    widgets      = settings.widgets,
                    editMode     = editMode,
                    accent       = accent,
                    isDayMode    = isDayMode,
                    onAddPage    = onAddPage,
                    onRemovePage = onRemovePage,
                    onGridDialog = { gridDialogPage = currentPage }
                )
            }
        }
    }

    // ── Widget context menu (long-press any card) ────────────────────────────
    contextMenuId?.let { id ->
        val instance = settings.widgets.find { it.id == id }
        if (instance == null) {
            contextMenuId = null
        } else {
            WidgetContextMenu(
                widgetId            = instance.id,
                widgetType          = instance.type,
                pageIndex           = instance.page.coerceIn(0, settings.pages.size - 1),
                pages               = settings.pages,
                accent              = accent,
                clockStyle          = settings.clockStyle,
                vitalsAsBars        = settings.vitalsAsBars,
                speedometerDigitalOnly = settings.speedometerDigitalOnly,
                carPlayPackage      = settings.carPlayPackage,
                androidAutoPackage  = settings.androidAutoPackage,
                pipAppPackage       = settings.pipAppPackage,
                isDayMode           = isDayMode,
                onRemove            = { contextMenuId = null; onRemoveWidget(id) },
                onMoveToPage        = { p -> contextMenuId = null; onMoveWidgetToPage(id, p) },
                onAssignCarPlay     = { contextMenuId = null; onAssignCarPlay() },
                onAssignAndroidAuto = { contextMenuId = null; onAssignAndroidAuto() },
                onClearCarPlay      = { contextMenuId = null; onClearCarPlay() },
                onClearAndroidAuto  = { contextMenuId = null; onClearAndroidAuto() },
                onAssignPip         = { contextMenuId = null; onAssignPip() },
                onClearPip          = { contextMenuId = null; onClearPip() },
                onSetClockStyle     = { onSetClockStyle(it) },
                onSetVitalsAsBars   = { onSetVitalsAsBars(it) },
                onSetSpeedometerDigitalOnly = { onSetSpeedometerDigitalOnly(it) },
                onTpmsConnect          = { contextMenuId = null; onTpmsConnect() },
                brightnessAuto           = settings.brightnessAuto,
                brightnessHighPct        = settings.brightnessHighPct,
                brightnessMediumPct      = settings.brightnessMediumPct,
                brightnessLowPct         = settings.brightnessLowPct,
                onSetBrightnessAuto      = { onSetBrightnessAuto(it) },
                onSetBrightnessLevel     = { slot, pct -> onSetBrightnessLevel(slot, pct) },
                onDismiss           = { contextMenuId = null }
            )
        }
    }

    // ── Per-page grid size dialog ────────────────────────────────────────────
    gridDialogPage?.let { pageIndex ->
        val page = settings.pages.getOrNull(pageIndex)
        if (page == null) {
            gridDialogPage = null
        } else {
            GridSizeDialog(
                pageIndex = pageIndex,
                page      = page,
                onPage    = instancesOnPage(settings.widgets, pageIndex),
                accent    = accent,
                isDayMode = isDayMode,
                onDismiss = { gridDialogPage = null },
                onConfirm = { cols, rows ->
                    onSetPageSize(pageIndex, cols, rows)
                    gridDialogPage = null
                }
            )
        }
    }

    // ── Widget library (adds to the page you are currently looking at) ───────
    if (widgetLibraryOpen) {
        WidgetLibraryDialog(
            settings   = settings,
            pageIndex  = currentPage,
            accent     = accent,
            isDayMode  = isDayMode,
            onAdd      = { type -> onAddWidget(type, currentPage) },
            onDismiss  = { widgetLibraryOpen = false }
        )
    }
}

// ── One page's widget grid ───────────────────────────────────────────────────

@Composable
private fun WidgetPageGrid(
    settings: AppSettings,
    pageIndex: Int,
    page: GridPage,
    gap: androidx.compose.ui.unit.Dp,
    accent: Color,
    widgetBg: Color,
    widgetBorder: Color,
    isDayMode: Boolean,
    editMode: Boolean,
    weather: WeatherState?,
    nowPlaying: NowPlayingState?,
    location: LocationData?,
    bearing: Float,
    tpms: com.openlauncher.app.tpms.TpmsSnapshot,
    hardwareRadio: com.openlauncher.app.viewmodel.LauncherViewModel.HardwareRadioState?,
    onMoveWidget: (id: String, gridX: Int, gridY: Int) -> Unit,
    onResizeWidget: (id: String, gridX: Int, gridY: Int, spanX: Int, spanY: Int) -> Unit,
    onOpenMenu: (id: String) -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onLaunchCarPlay: () -> Unit,
    onLaunchAndroidAuto: () -> Unit,
    onTapNowPlaying: () -> Unit,
    onLaunchHardwareRadio: () -> Unit,
    onStopHardwareRadio: () -> Unit,
    onRadioSeekUp: () -> Unit,
    onRadioSeekDown: () -> Unit,
    onRadioCycleFm: () -> Unit,
    onRadioSwitchAm: () -> Unit,
    onRadioTune: (band: String, freq: Float) -> Unit,
    onAssignRadio: () -> Unit,
    onTpmsConnect: () -> Unit,
    onLaunchPip: () -> Unit,
    onUpdateSoundPad: (index: Int, pad: com.openlauncher.app.data.SoundPadConfig) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(gap)
    ) {
        val cellW = (maxWidth  - gap * (page.cols - 1)) / page.cols
        val cellH = (maxHeight - gap * (page.rows - 1)) / page.rows
        val density = LocalDensity.current
        val cellStepXPx = with(density) { (cellW + gap).toPx() }
        val cellStepYPx = with(density) { (cellH + gap).toPx() }

        // WEATHER stays in the set even with no data: the commit path
        // (LauncherViewModel.moveWidgetConfig) computes against saved instances
        // only, so dropping it here would make the drop ghost and the committed
        // layout disagree. With no data the cell renders fully transparent.
        val visible = instancesOnPage(settings.widgets, pageIndex)

        // ── Drag state ───────────────────────────────────────────────────
        var draggingId   by remember { mutableStateOf<String?>(null) }
        var dragOffsetPx by remember { mutableStateOf(Offset.Zero) }

        // ── Corner-resize state ──────────────────────────────────────────
        var resizingId    by remember { mutableStateOf<String?>(null) }
        var resizeCorner  by remember { mutableStateOf(ResizeCorner.BOTTOM_END) }
        var resizeDeltaPx by remember { mutableStateOf(Offset.Zero) }

        // Compute snap target for the widget being dragged (uses original spanX)
        val draggingOriginal = if (draggingId != null) visible.find { it.id == draggingId } else null
        val targetGridX = draggingOriginal?.let {
            (it.gridX + (dragOffsetPx.x / cellStepXPx).roundToInt()).coerceIn(0, page.cols - it.spanX)
        }
        val targetGridY = draggingOriginal?.let {
            (it.gridY + (dragOffsetPx.y / cellStepYPx).roundToInt()).coerceIn(0, page.rows - it.spanY)
        }

        // Compute proposed layout (push preview) while dragging
        val proposedLayout = if (draggingOriginal != null && targetGridX != null && targetGridY != null)
            computeWidgetMove(visible, draggingOriginal.id, targetGridX, targetGridY, page.cols, page.rows)
        else null

        // Live corner-resize preview: the handle gesture keeps resizeDeltaPx current;
        // the engine snaps to cells, clamps to bounds and never overlaps neighbours.
        val proposedResize = resizingId?.let { id ->
            computeCornerResize(
                visible, id, resizeCorner,
                (resizeDeltaPx.x / cellStepXPx).roundToInt(),
                (resizeDeltaPx.y / cellStepYPx).roundToInt(),
                page.cols, page.rows
            )
        }

        // Drop ghost — rendered before widgets so it appears beneath them
        if (draggingOriginal != null && targetGridX != null && targetGridY != null) {
            val gX = (cellW + gap) * targetGridX
            val gY = (cellH + gap) * targetGridY
            val gW = cellW * draggingOriginal.spanX + gap * (draggingOriginal.spanX - 1)
            val gH = cellH * draggingOriginal.spanY + gap * (draggingOriginal.spanY - 1)
            Box(
                modifier = Modifier
                    .absoluteOffset(x = gX, y = gY)
                    .size(gW, gH)
                    .background(accent.copy(alpha = 0.08f))
                    .border(1.dp, accent.copy(alpha = 0.5f), WIDGET_RADIUS)
            )
        }

        // Displacement ghosts — show where pushed widgets will land
        if (proposedLayout != null && draggingOriginal != null) {
            proposedLayout
                .filter { it.id != draggingOriginal.id }
                .forEach { proposed ->
                    val original = visible.find { it.id == proposed.id } ?: return@forEach
                    if (proposed.gridX != original.gridX || proposed.gridY != original.gridY) {
                        val dX = (cellW + gap) * proposed.gridX
                        val dY = (cellH + gap) * proposed.gridY
                        val dW = cellW * proposed.spanX + gap * (proposed.spanX - 1)
                        val dH = cellH * proposed.spanY + gap * (proposed.spanY - 1)
                        Box(
                            modifier = Modifier
                                .absoluteOffset(x = dX, y = dY)
                                .size(dW, dH)
                                .border(1.dp, Color.White.copy(alpha = 0.25f), WIDGET_RADIUS)
                        )
                    }
                }
        }

        visible.forEach { w ->
            // While a corner drag is active, preview the proposed geometry live
            val geom   = proposedResize?.takeIf { it.id == w.id } ?: w
            val xOff   = (cellW + gap) * geom.gridX
            val yOff   = (cellH + gap) * geom.gridY
            val width  = cellW * geom.spanX + gap * (geom.spanX - 1)
            val height = cellH * geom.spanY + gap * (geom.spanY - 1)

            val isDragging = draggingId == w.id
            // Weather with no data reserves its cell but draws nothing
            // (still visible in edit mode so it can be moved/removed)
            val isGhost    = w.type == "WEATHER" && weather == null && !editMode
            val dragDpX    = if (isDragging) with(density) { dragOffsetPx.x.toDp() } else 0.dp
            val dragDpY    = if (isDragging) with(density) { dragOffsetPx.y.toDp() } else 0.dp

            @OptIn(ExperimentalFoundationApi::class)
            Box(
                modifier = Modifier
                    .absoluteOffset(x = xOff + dragDpX, y = yOff + dragDpY)
                    .size(width, height)
                    .zIndex(if (isDragging) 1f else 0f)
                    .clip(WIDGET_RADIUS)
                    .background(if (isGhost) Color.Transparent else widgetBg)
                    .border(
                        width = if (editMode) 1.5.dp else 1.dp,
                        color = when {
                            editMode -> accent.copy(alpha = 0.45f)
                            isGhost  -> Color.Transparent
                            else     -> widgetBorder
                        },
                        shape = WIDGET_RADIUS
                    )
                    .combinedClickable(
                        indication        = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick           = { if (editMode) onOpenMenu(w.id) },
                        onLongClick       = { if (!editMode) onOpenMenu(w.id) }
                    )
                    .then(
                        if (editMode) Modifier.pointerInput(editMode, w.id, w.gridX, w.gridY) {
                            var hasSignificantDrag = false
                            // Touch-slop gate: without it, sub-pixel jitter during a
                            // long-press counts as a drag and the context menu never opens
                            val slop = viewConfiguration.touchSlop
                            detectDragGesturesAfterLongPress(
                                onDragStart = { _ ->
                                    draggingId         = w.id
                                    dragOffsetPx       = Offset.Zero
                                    hasSignificantDrag = false
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragOffsetPx      += dragAmount
                                    if (!hasSignificantDrag && dragOffsetPx.getDistance() > slop) {
                                        hasSignificantDrag = true
                                    }
                                },
                                onDragEnd = {
                                    if (hasSignificantDrag) {
                                        val newX = (w.gridX + (dragOffsetPx.x / cellStepXPx).roundToInt())
                                            .coerceIn(0, page.cols - w.spanX)
                                        val newY = (w.gridY + (dragOffsetPx.y / cellStepYPx).roundToInt())
                                            .coerceIn(0, page.rows - w.spanY)
                                        onMoveWidget(w.id, newX, newY)
                                    } else {
                                        onOpenMenu(w.id)
                                    }
                                    draggingId   = null
                                    dragOffsetPx = Offset.Zero
                                },
                                onDragCancel = {
                                    draggingId   = null
                                    dragOffsetPx = Offset.Zero
                                }
                            )
                        } else Modifier
                    )
            ) {
                when (w.type) {
                    "CLOCK" -> ClockWidget(
                        style      = settings.clockStyle,
                        accent     = accent,
                        isDayMode  = isDayMode,
                        modifier   = Modifier.fillMaxSize()
                    )
                    "WEATHER" -> WeatherWidget(
                        state      = weather,
                        accent     = accent,
                        metric     = settings.unitSystem.name == "METRIC",
                        isDayMode  = isDayMode,
                        modifier   = Modifier.fillMaxSize()
                    )
                    "NOW_PLAYING" -> NowPlayingWidget(
                        state               = nowPlaying,
                        accent              = accent,
                        carPlayPackage      = settings.carPlayPackage,
                        androidAutoPackage  = settings.androidAutoPackage,
                        onPlayPause         = onPlayPause,
                        onNext              = onNext,
                        onPrev              = onPrev,
                        onLaunchCarPlay     = onLaunchCarPlay,
                        onLaunchAndroidAuto = onLaunchAndroidAuto,
                        onTapToOpenApp      = onTapNowPlaying,
                        modifier            = Modifier.fillMaxSize(),
                        isEditing           = editMode,
                        isDayMode           = isDayMode,
                        hardwareRadio         = hardwareRadio,
                        onLaunchHardwareRadio = onLaunchHardwareRadio,
                        onStopHardwareRadio   = onStopHardwareRadio,
                        onRadioSeekUp         = onRadioSeekUp,
                        onRadioSeekDown       = onRadioSeekDown,
                        onRadioCycleFm        = onRadioCycleFm,
                        onRadioSwitchAm       = onRadioSwitchAm,
                        onRadioTune           = onRadioTune,
                        onAssignRadio         = onAssignRadio
                    )
                    "TELEMETRY" -> TelemetryWidget(
                        location  = location,
                        bearing   = (bearing + settings.compassOffset + 360f) % 360f,
                        accent    = accent,
                        isDayMode = isDayMode,
                        modifier  = Modifier.fillMaxSize()
                    )
                    "ALTIMETER" -> AltimeterWidget(
                        location  = location,
                        isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                        accent    = accent,
                        isDayMode = isDayMode,
                        modifier  = Modifier.fillMaxSize()
                    )
                    "SPEEDOMETER" -> SpeedometerWidget(
                        location  = location,
                        isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                        accent    = accent,
                        isDayMode = isDayMode,
                        digitalOnly = settings.speedometerDigitalOnly,
                        modifier  = Modifier.fillMaxSize()
                    )
                    "VITALS" -> VitalsWidget(
                        accent    = accent,
                        isDayMode = isDayMode,
                        asBars    = settings.vitalsAsBars,
                        modifier  = Modifier.fillMaxSize()
                    )
                    "TRIP_TRACKER" -> TripTrackerWidget(
                        location  = location,
                        isMetric  = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                        accent    = accent,
                        isDayMode = isDayMode,
                        modifier  = Modifier.fillMaxSize()
                    )
                    "SOUNDBOARD" -> SoundboardWidget(
                        pads      = settings.soundboardPads,
                        accent    = accent,
                        isDayMode = isDayMode,
                        isEditing = editMode,
                        onUpdatePad = onUpdateSoundPad,
                        modifier  = Modifier.fillMaxSize()
                    )
                    "VOLUME" -> VolumeWidget(
                        accent    = accent,
                        isDayMode = isDayMode,
                        isEditing = editMode,
                        modifier  = Modifier.fillMaxSize()
                    )
                    "BRIGHTNESS" -> BrightnessWidget(
                        highPct   = settings.brightnessHighPct,
                        mediumPct = settings.brightnessMediumPct,
                        lowPct    = settings.brightnessLowPct,
                        auto      = settings.brightnessAuto,
                        accent    = accent,
                        isDayMode = isDayMode,
                        isEditing = editMode,
                        modifier  = Modifier.fillMaxSize()
                    )
                    "TPMS" -> TpmsWidget(
                        snapshot       = tpms,
                        unit           = settings.tpmsPressureUnit,
                        lowPressureKpa = settings.tpmsLowPressureKpa,
                        accent         = accent,
                        isMetric       = settings.unitSystem == com.openlauncher.app.data.UnitSystem.METRIC,
                        isDayMode      = isDayMode,
                        isEditing      = editMode,
                        alarmEnabled   = settings.tpmsAlarmEnabled,
                        onConnect      = onTpmsConnect,
                        modifier       = Modifier.fillMaxSize()
                    )
                }

                // Label — hide when album art fills the widget background, and
                // on dense grids where the cell is too short for the caption
                val showLabel = height >= HEADER_LABEL_MIN_H && !(
                    w.type == "NOW_PLAYING" && nowPlaying?.albumArt != null && nowPlaying.title.isNotEmpty()
                )
                if (showLabel) {
                    val labelColor = when {
                        isGhost -> Color.Transparent
                        w.type == "NOW_PLAYING" && nowPlaying?.albumArt != null && nowPlaying.title.isNotEmpty() -> Color.Transparent
                        isDayMode -> Color(0xFF999999)
                        else      -> Color(0xFF3A3A3A)
                    }
                    Text(
                        text          = cardLabel(w.type),
                        style         = MaterialTheme.typography.labelSmall,
                        color         = labelColor,
                        letterSpacing = 2.sp,
                        fontSize      = 8.sp,
                        modifier      = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = 7.dp)
                    )
                }
            }
        }

        // ── Corner resize handles (edit mode only) ───────────────────────
        // Drawn as siblings of the cards so the card clip never eats them.
        // zIndex(2f) puts them above cards in draw AND hit order.
        if (editMode) {
            visible.forEach { w ->
                val geom   = proposedResize?.takeIf { it.id == w.id } ?: w
                val left   = (cellW + gap) * geom.gridX
                val top    = (cellH + gap) * geom.gridY
                val right  = left + cellW * geom.spanX + gap * (geom.spanX - 1)
                val bottom = top + cellH * geom.spanY + gap * (geom.spanY - 1)
                for (corner in ResizeCorner.entries) {
                    val cx = if (corner == ResizeCorner.TOP_START || corner == ResizeCorner.BOTTOM_START) left else right
                    val cy = if (corner == ResizeCorner.TOP_START || corner == ResizeCorner.TOP_END)     top  else bottom
                    Box(
                        modifier = Modifier
                            .zIndex(2f)
                            .absoluteOffset(cx - RESIZE_HANDLE_SIZE / 2, cy - RESIZE_HANDLE_SIZE / 2)
                            .size(RESIZE_HANDLE_SIZE)
                            // Keyed only on drag-stable values: the live preview moves
                            // the handle mid-drag but must never restart the gesture.
                            .pointerInput(editMode, w.id, corner, w.gridX, w.gridY, w.spanX, w.spanY) {
                                awaitEachGesture {
                                    val down = awaitFirstDown()
                                    // Consume so the card click / long-press-drag below
                                    // never fires for touches that start on a handle.
                                    down.consume()
                                    resizingId    = w.id
                                    resizeCorner  = corner
                                    resizeDeltaPx = Offset.Zero
                                    var delta = Offset.Zero
                                    while (true) {
                                        val event  = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        event.changes.forEach { it.consume() }
                                        if (change == null || !change.pressed) break
                                        delta         = change.position - down.position
                                        resizeDeltaPx = delta
                                    }
                                    val dx = (delta.x / cellStepXPx).roundToInt()
                                    val dy = (delta.y / cellStepYPx).roundToInt()
                                    if (dx != 0 || dy != 0) {
                                        val r = computeCornerResize(visible, w.id, corner, dx, dy, page.cols, page.rows)
                                        if (r != null) onResizeWidget(r.id, r.gridX, r.gridY, r.spanX, r.spanY)
                                    }
                                    resizingId    = null
                                    resizeDeltaPx = Offset.Zero
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(RESIZE_HANDLE_DOT)
                                .background(accent)
                        )
                    }
                }
            }
        }
    }
}

// ── Page indicator + edit-mode page controls ─────────────────────────────────

@Composable
private fun PageIndicatorBar(
    modifier: Modifier = Modifier,
    pagerState: PagerState,
    currentPage: Int,
    pages: List<GridPage>,
    widgets: List<WidgetInstance>,
    editMode: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onAddPage: () -> Unit,
    onRemovePage: (Int) -> Unit,
    onGridDialog: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val pageEmpty = instancesOnPage(widgets, currentPage).isEmpty()
    val dimColor  = if (isDayMode) Color(0xFFADB5BD) else Color(0x66FFFFFF)

    Column(
        modifier = modifier.padding(bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (editMode) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                val canRemove = pages.size > 1 && pageEmpty
                PageChip(
                    label   = "−",
                    enabled = canRemove,
                    accent  = accent,
                    isDayMode = isDayMode,
                    onClick = { onRemovePage(currentPage) }
                )
                val dims = pages.getOrElse(currentPage) { GridPage() }
                PageChip(
                    label   = "PAGE ${currentPage + 1} · ${dims.cols}×${dims.rows}",
                    enabled = true,
                    accent  = accent,
                    isDayMode = isDayMode,
                    onClick = onGridDialog
                )
                PageChip(
                    label   = "+",
                    enabled = pages.size < MAX_PAGES,
                    accent  = accent,
                    isDayMode = isDayMode,
                    onClick = onAddPage
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(pages.size) { i ->
                val selected = i == currentPage
                Box(
                    modifier = Modifier
                        .size(if (selected) 9.dp else 6.dp)
                        .clip(CircleShape)
                        .background(if (selected) accent else dimColor)
                        .clickable { scope.launch { pagerState.animateScrollToPage(i) } }
                )
            }
        }
    }
}

@Composable
private fun PageChip(
    label: String,
    enabled: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (enabled) accent.copy(alpha = 0.6f) else if (isDayMode) Color(0xFFCED4DA) else Color(0x33FFFFFF)
    val textColor   = if (enabled) accent else if (isDayMode) Color(0xFFADB5BD) else Color(0x4DFFFFFF)
    val chipBg      = if (isDayMode) Color(0xE6FFFFFF) else Color(0xB3000000)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(chipBg)
            .border(1.dp, borderColor, RoundedCornerShape(3.dp))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text          = label,
            color         = textColor,
            fontSize      = 9.sp,
            letterSpacing = 1.sp,
            fontFamily    = FontFamily.Monospace
        )
    }
}

// ── Per-page grid size dialog ────────────────────────────────────────────────

@Composable
private fun GridSizeDialog(
    pageIndex: Int,
    page: GridPage,
    onPage: List<WidgetInstance>,
    accent: Color,
    isDayMode: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (cols: Int, rows: Int) -> Unit
) {
    var cols by remember(pageIndex) { mutableStateOf(page.cols) }
    var rows by remember(pageIndex) { mutableStateOf(page.rows) }

    val changed  = cols != page.cols || rows != page.rows
    // Mirrors LauncherViewModel.setPageSize's guard: refuse rather than lose cards
    val fits     = resizePageLayout(onPage, cols, rows) != null
    val canApply = changed && fits

    val dialogBg     = if (isDayMode) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.background
    val dialogText   = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val cancelColor  = if (isDayMode) Color(0xFF6C757D) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text          = "GRID SIZE — PAGE ${pageIndex + 1}",
                color         = dialogText,
                fontSize      = 11.sp,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SpanRow(label = "COLUMNS", value = cols, min = MIN_COLS, max = MAX_COLS, accent = accent, isDayMode = isDayMode) { cols = it }
                SpanRow(label = "ROWS",    value = rows, min = MIN_ROWS, max = MAX_ROWS, accent = accent, isDayMode = isDayMode) { rows = it }
                Text(
                    text          = "$cols × $rows = ${cols * rows} CELLS",
                    color         = if (isDayMode) Color(0xFF495057) else Color(0xFF666666),
                    fontSize      = 9.sp,
                    letterSpacing = 1.sp
                )
                if (changed && !fits) {
                    Text(
                        text          = "TOO SMALL — THE ${onPage.size} CARDS ON THIS PAGE WOULD NOT FIT. REMOVE OR MOVE SOME FIRST.",
                        color         = if (isDayMode) Color(0xFFE03131) else Color(0xFFFA5252),
                        fontSize      = 8.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { onConfirm(cols, rows) },
                enabled  = canApply
            ) {
                Text("APPLY",
                    color     = if (canApply) accent else (if (isDayMode) Color(0xFFADB5BD) else Color(0xFF333333)),
                    fontSize  = 11.sp,
                    letterSpacing = 1.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = cancelColor, fontSize = 11.sp, letterSpacing = 1.sp)
            }
        },
        containerColor    = dialogBg,
        titleContentColor = dialogText,
        textContentColor  = dialogText
    )
}

// ── Widget context menu (long-press any card) ────────────────────────────────

@Composable
private fun WidgetContextMenu(
    widgetId: String,
    widgetType: String,
    pageIndex: Int,
    pages: List<GridPage>,
    accent: Color,
    clockStyle: ClockStyle,
    vitalsAsBars: Boolean,
    speedometerDigitalOnly: Boolean,
    carPlayPackage: String = "",
    androidAutoPackage: String = "",
    pipAppPackage: String = "",
    isDayMode: Boolean,
    onRemove: () -> Unit,
    onMoveToPage: (Int) -> Unit,
    onAssignCarPlay: () -> Unit,
    onAssignAndroidAuto: () -> Unit,
    onClearCarPlay: () -> Unit,
    onClearAndroidAuto: () -> Unit,
    onAssignPip: () -> Unit,
    onClearPip: () -> Unit,
    onSetClockStyle: (ClockStyle) -> Unit,
    onSetVitalsAsBars: (Boolean) -> Unit,
    onSetSpeedometerDigitalOnly: (Boolean) -> Unit,
    onTpmsConnect: () -> Unit = {},
    brightnessAuto: Boolean = false,
    brightnessHighPct: Int = 100,
    brightnessMediumPct: Int = 55,
    brightnessLowPct: Int = 15,
    onSetBrightnessAuto: (Boolean) -> Unit = {},
    onSetBrightnessLevel: (String, Int) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    val menuBg    = if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF111111)
    val menuBorder = if (isDayMode) Color(0xFFDDE1E5) else Color(0xFF1E1E1E)
    val menuDivider = if (isDayMode) Color(0xFFF1F3F5) else Color(0xFF1A1A1A)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(menuBg)
                .border(1.dp, menuBorder, RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp)
                .width(200.dp)
        ) {
            val inactiveMenuTint = if (isDayMode) Color(0xFF777777) else Color(0xFF555555)
            // Page moves — one row per other page (up to MAX_PAGES, so bounded)
            if (pages.size > 1) {
                pages.indices.filter { it != pageIndex }.forEach { target ->
                    HorizontalDivider(color = menuDivider)
                    ContextRow(
                        label   = "MOVE TO PAGE ${target + 1}",
                        icon    = Icons.Default.SwapHoriz,
                        tint    = accent,
                        onClick = { onMoveToPage(target) },
                        isDayMode = isDayMode
                    )
                }
            }
            HorizontalDivider(color = menuDivider)
            ContextRow("REMOVE", Icons.Default.Delete, Color(0xFF884444), onRemove, isDayMode = isDayMode)
            if (widgetType == "CLOCK") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIGITAL",
                    icon    = Icons.Default.Schedule,
                    tint    = if (clockStyle == ClockStyle.DIGITAL) accent else inactiveMenuTint,
                    onClick = { onSetClockStyle(ClockStyle.DIGITAL); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "ANALOG",
                    icon    = Icons.Default.Watch,
                    tint    = if (clockStyle == ClockStyle.ANALOG) accent else inactiveMenuTint,
                    onClick = { onSetClockStyle(ClockStyle.ANALOG); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetType == "VITALS") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIAL GAUGES",
                    icon    = Icons.Default.Adjust,
                    tint    = if (!vitalsAsBars) accent else inactiveMenuTint,
                    onClick = { onSetVitalsAsBars(false); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "BARS VIEW",
                    icon    = Icons.Default.FormatAlignLeft,
                    tint    = if (vitalsAsBars) accent else inactiveMenuTint,
                    onClick = { onSetVitalsAsBars(true); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetType == "SPEEDOMETER") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIAL TRACK",
                    icon    = Icons.Default.Speed,
                    tint    = if (!speedometerDigitalOnly) accent else inactiveMenuTint,
                    onClick = { onSetSpeedometerDigitalOnly(false); onDismiss() },
                    isDayMode = isDayMode
                )
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label   = "DIGITAL ONLY",
                    icon    = Icons.Default.Dialpad,
                    tint    = if (speedometerDigitalOnly) accent else inactiveMenuTint,
                    onClick = { onSetSpeedometerDigitalOnly(true); onDismiss() },
                    isDayMode = isDayMode
                )
            }
            if (widgetType == "TPMS") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label     = "CONNECT USB RECEIVER",
                    icon      = Icons.Default.Usb,
                    tint      = accent,
                    onClick   = { onTpmsConnect() },
                    isDayMode = isDayMode
                )
            }
            if (widgetType == "BRIGHTNESS") {
                HorizontalDivider(color = menuDivider)
                ContextRow(
                    label     = if (brightnessAuto) "AUTO: SUN + WEATHER" else "AUTO: OFF",
                    icon      = Icons.Default.BrightnessAuto,
                    tint      = if (brightnessAuto) accent else inactiveMenuTint,
                    onClick   = { onSetBrightnessAuto(!brightnessAuto) },
                    isDayMode = isDayMode
                )
                // Preset level steppers — menu stays open so the values are visible
                HorizontalDivider(color = menuDivider)
                BrightnessLevelRow("HIGH", brightnessHighPct, accent, isDayMode) { onSetBrightnessLevel("HIGH", it) }
                HorizontalDivider(color = menuDivider)
                BrightnessLevelRow("MED",  brightnessMediumPct, accent, isDayMode) { onSetBrightnessLevel("MEDIUM", it) }
                HorizontalDivider(color = menuDivider)
                BrightnessLevelRow("LOW",  brightnessLowPct, accent, isDayMode) { onSetBrightnessLevel("LOW", it) }
            }
            if (widgetType == "NOW_PLAYING") {
                HorizontalDivider(color = menuDivider)
                ContextRow("ASSIGN CARPLAY APP",      Icons.Default.PhoneAndroid,  accent, onAssignCarPlay, isDayMode = isDayMode)
                if (carPlayPackage.isNotEmpty()) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow("CLEAR CARPLAY APP", Icons.Default.PhoneAndroid, Color(0xFF884444), onClearCarPlay, isDayMode = isDayMode)
                }
                HorizontalDivider(color = menuDivider)
                ContextRow("ASSIGN ANDROID AUTO APP", Icons.Default.DirectionsCar, accent, onAssignAndroidAuto, isDayMode = isDayMode)
                if (androidAutoPackage.isNotEmpty()) {
                    HorizontalDivider(color = menuDivider)
                    ContextRow("CLEAR ANDROID AUTO APP", Icons.Default.DirectionsCar, Color(0xFF884444), onClearAndroidAuto, isDayMode = isDayMode)
                }
            }

        }
    }
}

@Composable
private fun ContextRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    onClick: () -> Unit,
    isDayMode: Boolean = false
) {
    val finalTint = if (isDayMode) {
        if (tint == Color(0xFF884444)) {
            tint
        } else if (tint == Color(0xFF777777)) {
            Color(0xFF888888)
        } else {
            Color(0xFF111111)
        }
    } else {
        tint
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = finalTint, modifier = Modifier.size(16.dp))
        Text(label, color = finalTint, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

/** − / + stepper for one BRIGHTNESS preset level (5% steps), kept inside the context menu. */
@Composable
private fun BrightnessLevelRow(
    label: String,
    pct: Int,
    accent: Color,
    isDayMode: Boolean,
    onChange: (Int) -> Unit
) {
    val textColor  = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val dimColor   = if (isDayMode) Color(0xFF777777) else Color(0xFF555555)
    val disabledC  = if (isDayMode) Color(0xFFCED4DA) else Color(0xFF333333)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text          = label,
            color         = dimColor,
            fontSize      = 9.sp,
            letterSpacing = 1.sp,
            modifier      = Modifier.width(38.dp)
        )
        IconButton(
            onClick  = { if (pct > 1) onChange((pct - 5).coerceAtLeast(1)) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Default.Remove, null,
                tint     = if (pct > 1) textColor else disabledC,
                modifier = Modifier.size(14.dp)
            )
        }
        Text(
            text          = "$pct%",
            color         = textColor,
            fontSize      = 11.sp,
            fontFamily    = FontFamily.Monospace,
            textAlign     = TextAlign.Center,
            modifier      = Modifier.width(38.dp)
        )
        IconButton(
            onClick  = { if (pct < 100) onChange((pct + 5).coerceAtMost(100)) },
            modifier = Modifier.size(28.dp)
        ) {
            Icon(
                Icons.Default.Add, null,
                tint     = if (pct < 100) accent else disabledC,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun SpanRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    accent: Color,
    isDayMode: Boolean,
    onChange: (Int) -> Unit
) {
    val textColor   = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val dimColor    = if (isDayMode) Color(0xFF495057) else Color(0xFF666666)
    val disabledC   = if (isDayMode) Color(0xFFCED4DA) else Color(0xFF333333)
    val inactiveBg  = if (isDayMode) Color(0xFFE9ECEF) else Color(0xFF2A2A2A)
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text          = label,
            color         = dimColor,
            fontSize      = 10.sp,
            letterSpacing = 1.sp,
            modifier      = Modifier.width(64.dp)
        )
        IconButton(
            onClick  = { if (value > min) onChange(value - 1) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Remove, null,
                tint     = if (value > min) textColor else disabledC,
                modifier = Modifier.size(16.dp)
            )
        }
        Text(
            text      = "$value",
            color     = textColor,
            fontSize  = 16.sp,
            textAlign = TextAlign.Center,
            modifier  = Modifier.width(24.dp)
        )
        IconButton(
            onClick  = { if (value < max) onChange(value + 1) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                Icons.Default.Add, null,
                tint     = if (value < max) accent else disabledC,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(max.coerceAtMost(6)) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 10.dp)
                        .background(
                            if (i < value) accent.copy(alpha = 0.7f) else inactiveBg,
                            RoundedCornerShape(1.dp)
                        )
                )
            }
        }
    }
}

// ── Widget Library ────────────────────────────────────────────────────────────

@Composable
private fun WidgetLibraryDialog(
    settings: AppSettings,
    pageIndex: Int,
    accent: Color,
    isDayMode: Boolean,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dialogBg    = if (isDayMode) Color(0xFFEEEEEE) else Color(0xFF0C0C0C)
    val dialogBorder = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1E1E1E)
    val titleColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF555555)
    val closeColor  = if (isDayMode) Color(0xFF495057) else Color(0xFF444444)

    val dims   = settings.pages.getOrElse(pageIndex) { GridPage() }
    val canAdd = canAddOnPage(settings, pageIndex)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(dialogBg)
                .border(1.dp, dialogBorder, RoundedCornerShape(4.dp))
                .padding(16.dp)
                .widthIn(min = 320.dp, max = 520.dp)
        ) {
            Row(
                modifier          = Modifier.fillMaxWidth().padding(bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text          = "WIDGET LIBRARY — PAGE ${pageIndex + 1} OF ${settings.pages.size} · ${dims.cols}×${dims.rows}",
                    color         = titleColor,
                    fontSize      = 9.sp,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, null, tint = closeColor, modifier = Modifier.size(14.dp))
                }
            }

            LazyVerticalGrid(
                columns               = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                items(ALL_WIDGET_TYPES) { info ->
                    val count = settings.widgets.count { it.type == info.id }
                    WidgetLibraryCard(
                        info      = info,
                        count     = count,
                        canAdd    = canAdd,
                        accent    = accent,
                        isDayMode = isDayMode,
                        onAdd     = { onAdd(info.id) }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text          = if (canAdd)
                    "TAPPING A CARD ADDS IT TO PAGE ${pageIndex + 1} — DUPLICATES ARE ALLOWED. DRAG A CORNER HANDLE TO RESIZE, LONG-PRESS A CARD TO MOVE OR REMOVE IT."
                else
                    "ALL ${dims.cols * dims.rows} CELLS ON PAGE ${pageIndex + 1} ARE OCCUPIED — LONG-PRESS A CARD TO MOVE OR REMOVE IT, OR PICK A BIGGER GRID.",
                color         = when {
                    !canAdd   -> if (isDayMode) Color(0xFFE03131) else Color(0xFFFA5252)
                    isDayMode -> Color(0xFF495057)
                    else      -> Color(0xFF3A3A3A)
                },
                fontSize      = 8.sp,
                letterSpacing = 1.sp,
                modifier      = Modifier.fillMaxWidth(),
                textAlign     = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WidgetLibraryCard(
    info: WidgetTypeInfo,
    count: Int,
    canAdd: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onAdd: () -> Unit
) {
    val isActive   = count > 0
    val enabled    = canAdd
    val cardBorder = if (isActive) accent else if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)
    val cardBg     = if (isActive) accent.copy(alpha = 0.15f) else if (isDayMode) Color(0xFFFFFFFF) else Color(0xFF0E0E0E)
    val iconTint   = if (isActive) accent else if (isDayMode) Color(0xFF495057) else Color(0xFF333333)
    val labelColor = if (isActive) accent else if (isDayMode) Color(0xFF212529) else Color(0xFF3A3A3A)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(cardBg)
            .border(1.dp, cardBorder, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onAdd)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(info.icon, null, tint = iconTint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            text          = info.label,
            color         = labelColor,
            fontSize      = 7.sp,
            letterSpacing = 1.sp,
            textAlign     = TextAlign.Center,
            maxLines      = 2,
            lineHeight    = 9.sp
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text          = when {
                isActive -> "×$count ON HOME"
                !canAdd  -> "FULL"
                else     -> "ADD"
            },
            color         = when {
                isActive -> accent.copy(alpha = 0.75f)
                !canAdd  -> if (isDayMode) Color(0xFFADB5BD) else Color(0xFF282828)
                else     -> if (isDayMode) Color(0xFF495057) else Color(0xFF3A3A3A)
            },
            fontSize      = 6.sp,
            letterSpacing = 1.sp,
            textAlign     = TextAlign.Center
        )
    }
}
