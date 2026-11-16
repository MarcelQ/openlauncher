package com.openlauncher.app.ui.components

import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DefaultShortcutIcon
import com.openlauncher.app.data.GpsStrength
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.ui.theme.LocalDayMode
import kotlin.math.roundToInt

private val ICON_SIZE   = 22.dp
private val SLOT_SIZE   = 52.dp
// Connectivity status boxes: same touch-friendly clip but tighter than the
// app slots so the five-icon cluster fits the 543.dp-tall unit screen
private val STATUS_SIZE = 40.dp

private class StatusEntry(
    val icon: ImageVector,
    val desc: String,
    val tint: Color,
    val onClick: () -> Unit
)

@Composable
fun Sidebar(
    currentDest: NavDestination,
    settings: AppSettings,
    // Connectivity status icons: above the Settings button on the vertical rail,
    // on the end opposite the nav cluster on the bottom bar
    isWifi: Boolean = false,
    isData: Boolean = false,
    isBluetooth: Boolean? = null,
    isHotspot: Boolean? = null,
    // GPS fix quality — its icon is always shown and encodes the strength
    gpsStrength: GpsStrength = GpsStrength.OFF,
    // Tapping a status icon opens Android's own connectivity panel/settings
    onWifiStatusClick: () -> Unit = {},
    onHotspotStatusClick: () -> Unit = {},
    onBluetoothStatusClick: () -> Unit = {},
    onDataStatusClick: () -> Unit = {},
    onGpsStatusClick: () -> Unit = {},
    installedIconFor: (String) -> Drawable?,
    onNavigate: (NavDestination) -> Unit,
    onShortcutClick: (Int) -> Unit,
    onShortcutLongPress: (Int) -> Unit,
    onShortcutRemove: (Int) -> Unit,
    onShortcutSetIcon: (Int, DefaultShortcutIcon?) -> Unit,
    onReorder: (from: Int, to: Int) -> Unit,
    // Dedicated CarPlay / Android Auto buttons — assignment and visibility
    // live in Settings ▸ Vehicle; here they render fixed before the first
    // app shortcut slot
    onCarPlayLaunch: () -> Unit = {},
    onAndroidAutoLaunch: () -> Unit = {},
    onRearrangeRequested: () -> Unit = {},
    isHorizontal: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isDayMode    = LocalDayMode.current
    val accent       = Color(settings.accentColor)
    val sidebarBg    = if (isDayMode) Color(0xFFE0E0E0) else Color.Black.copy(alpha = 0.4f)
    val iconInactive = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.3f)
    val dividerColor = if (isDayMode) Color(0xFFCCCCCC) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    val density      = LocalDensity.current
    val slotSizePx   = with(density) { SLOT_SIZE.toPx() }

    var actionSheetSlot by remember { mutableStateOf<Int?>(null) }
    var iconPickerSlot  by remember { mutableStateOf<Int?>(null) }

    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetPx  by remember { mutableFloatStateOf(0f) }

    fun dragTargetIndex(): Int = if (draggingIndex < 0) -1 else
        (draggingIndex + (dragOffsetPx / slotSizePx).roundToInt())
            .coerceIn(0, settings.shortcuts.size - 1)

    fun slotTranslation(index: Int): Float {
        if (draggingIndex < 0 || index == draggingIndex) return 0f
        val from = draggingIndex
        val to   = dragTargetIndex()
        return when {
            from < to && index in (from + 1)..to -> -slotSizePx
            from > to && index in to until from  ->  slotSizePx
            else -> 0f
        }
    }

    // The vertical rail renders slots bottom-up (slot 1 closest to the app-list
    // button at the bottom) so the most-used slot sits nearest the thumb while
    // driving. Indices stay DATA indices — only the presentation is inverted,
    // so drag maths accumulates screen deltas in data direction (sign-flipped
    // in ShortcutSlot) and neighbour shifts flip visually below.
    val inverted = !isHorizontal

    val shortcutsContent: @Composable () -> Unit = {
        val order = if (inverted) settings.shortcuts.indices.reversed().toList()
                    else          settings.shortcuts.indices.toList()
        order.forEach { index ->
            val shortcut      = settings.shortcuts[index]
            val isDragging    = (index == draggingIndex)
            val sign          = if (inverted) -1f else 1f
            val translation   = sign * (if (isDragging) dragOffsetPx else slotTranslation(index))

            ShortcutSlot(
                shortcut        = shortcut,
                accent          = accent,
                resolvedIcon    = if (shortcut.packageName.isNotEmpty())
                                      installedIconFor(shortcut.packageName) else null,
                isDragging      = isDragging,
                dragTranslation = translation,
                isHorizontal    = isHorizontal,
                inverted        = inverted,
                onClick         = { onShortcutClick(index) },
                onLongPress     = {
                    if (shortcut.packageName.isNotEmpty()) {
                        actionSheetSlot = index
                    } else {
                        onShortcutLongPress(index)
                    }
                },
                onDragStart = {
                    draggingIndex = index
                    dragOffsetPx  = 0f
                },
                onDragDelta = { d -> dragOffsetPx += d },
                onDragEnd   = {
                    val from = draggingIndex
                    val to   = dragTargetIndex()
                    if (from >= 0 && to != from) onReorder(from, to)
                    draggingIndex = -1
                    dragOffsetPx  = 0f
                }
            )
        }
    }

    // CarPlay / Android Auto: fixed dedicated buttons, shown only when assigned
    // in Settings AND the per-button toggle is on. On the vertical rail they sit
    // in the thumb-end cluster between the lowest slot and the Apps button; on
    // the bottom bar they precede the shortcut slots. Not draggable/removable
    // here on purpose — Settings owns them.
    val vehicleSlots: @Composable () -> Unit = {
        if (settings.sidebarCarPlayButton && settings.carPlayPackage.isNotEmpty()) {
            DedicatedSlot(Icons.Default.PhoneAndroid, "CarPlay", isHorizontal, onCarPlayLaunch)
        }
        if (settings.sidebarAndroidAutoButton && settings.androidAutoPackage.isNotEmpty()) {
            DedicatedSlot(Icons.Default.DirectionsCar, "Android Auto", isHorizontal, onAndroidAutoLaunch)
        }
    }

    val appsButton: @Composable () -> Unit = {
        NavButton(
            icon         = Icons.Default.Apps,
            label        = "Apps",
            isActive     = currentDest == NavDestination.APP_LIBRARY,
            accent       = accent,
            iconInactive = iconInactive,
            isHorizontal = isHorizontal,
            onClick      = { onNavigate(NavDestination.APP_LIBRARY) }
        )
    }
    val settingsButton: @Composable () -> Unit = {
        NavButton(
            icon         = Icons.Default.Settings,
            label        = "Settings",
            isActive     = currentDest == NavDestination.SETTINGS,
            accent       = accent,
            iconInactive = iconInactive,
            isHorizontal = isHorizontal,
            onClick      = { onNavigate(NavDestination.SETTINGS) },
            // Long-press opens the home-screen rearrange menu (the Edit button
            // was removed from the home header, so this is its entry point)
            onLongClick  = { onRearrangeRequested() }
        )
    }
    val homeButton: @Composable () -> Unit = {
        NavButton(
            icon         = Icons.Default.Home,
            label        = "Home",
            isActive     = currentDest == NavDestination.HOME,
            accent       = accent,
            iconInactive = iconInactive,
            isHorizontal = isHorizontal,
            onClick      = { onNavigate(NavDestination.HOME) }
        )
    }

    // Connectivity status icons, moved from the removed home-screen header.
    // All five are ALWAYS rendered: the off states use a distinct glyph at a
    // dim tint, so the rail never reshuffles as radios toggle. Tapping one
    // hands off to Android's own settings panel/page for that radio.
    // Driver-first order (vertical rail: bottom→top; bottom bar: nav-cluster
    // end→far end): Wi-Fi, personal hotspot, Bluetooth, cellular, GPS.
    // `reversed` renders that list far-end-first — the vertical rail reads
    // top→bottom, so it always reverses; the bottom bar reverses when its
    // nav cluster sits on the right edge.
    val statusIcons: @Composable (Boolean) -> Unit = { reversed ->
        val statusTint = if (isDayMode) Color(0xFF444444) else Color(0xFF666666)
        val inactiveTint = statusTint.copy(alpha = 0.35f)
        @Composable
        fun statusBox(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
            Box(
                modifier = Modifier
                    .size(STATUS_SIZE)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, desc, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
        // null = state unreadable (permission missing) — render as inactive
        val btOn = isBluetooth == true
        val hotspotOn = isHotspot == true
        val gpsIcon: ImageVector; val gpsDesc: String; val gpsTint: Color
        when (gpsStrength) {
            GpsStrength.OFF    -> { gpsIcon = Icons.Default.LocationOff;       gpsDesc = "GPS off";       gpsTint = inactiveTint }
            GpsStrength.WEAK   -> { gpsIcon = Icons.Default.LocationSearching; gpsDesc = "GPS searching"; gpsTint = statusTint }
            GpsStrength.FAIR   -> { gpsIcon = Icons.Default.MyLocation;        gpsDesc = "GPS fair";      gpsTint = statusTint }
            GpsStrength.STRONG -> { gpsIcon = Icons.Default.MyLocation;        gpsDesc = "GPS strong";    gpsTint = if (isDayMode) Color(0xFF111111) else Color(0xFFE0E0E0) }
        }
        val entries = listOf(
            StatusEntry(
                if (isWifi) Icons.Default.Wifi else Icons.Default.WifiOff,
                if (isWifi) "WiFi on" else "WiFi off",
                if (isWifi) statusTint else inactiveTint
            ) { onWifiStatusClick() },
            StatusEntry(
                if (hotspotOn) Icons.Default.WifiTethering else Icons.Default.WifiTetheringError,
                when {
                    isHotspot == null -> "Personal hotspot status unknown"
                    hotspotOn         -> "Personal hotspot on"
                    else              -> "Personal hotspot off"
                },
                if (hotspotOn) statusTint else inactiveTint
            ) { onHotspotStatusClick() },
            StatusEntry(
                if (btOn) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                when {
                    isBluetooth == null -> "Bluetooth status unknown"
                    btOn                -> "Bluetooth on"
                    else                -> "Bluetooth off"
                },
                if (btOn) statusTint else inactiveTint
            ) { onBluetoothStatusClick() },
            StatusEntry(
                if (isData) Icons.Default.SignalCellularAlt else Icons.Default.SignalCellularOff,
                if (isData) "Data on" else "Data off",
                if (isData) statusTint else inactiveTint
            ) { onDataStatusClick() },
            StatusEntry(gpsIcon, gpsDesc, gpsTint) { onGpsStatusClick() }
        )
        for (entry in if (reversed) entries.asReversed() else entries) {
            statusBox(entry.icon, entry.desc, entry.tint, entry.onClick)
        }
    }

    if (isHorizontal) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(sidebarBg)
        ) {
            // Shortcuts centred, inset past the edge-pinned nav buttons and
            // scrollable — an unbounded row ran beneath the nav buttons and off
            // both screen edges once enough slots were added
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .padding(horizontal = 150.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                vehicleSlots()
                shortcutsContent()
            }

            // Nav buttons pinned to one edge, Home always outermost
            Row(
                modifier = Modifier
                    .align(if (settings.bottomBarShortcutsRight) Alignment.CenterEnd else Alignment.CenterStart)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!settings.bottomBarShortcutsRight) {
                    NavButton(Icons.Default.Home,     "Home",     currentDest == NavDestination.HOME,        accent, iconInactive, true, onClick = { onNavigate(NavDestination.HOME) })
                    NavButton(Icons.Default.Settings, "Settings", currentDest == NavDestination.SETTINGS,    accent, iconInactive, true, onClick = { onNavigate(NavDestination.SETTINGS) }, onLongClick = { onRearrangeRequested() })
                    NavButton(Icons.Default.Apps,     "Apps",     currentDest == NavDestination.APP_LIBRARY, accent, iconInactive, true, onClick = { onNavigate(NavDestination.APP_LIBRARY) })
                } else {
                    NavButton(Icons.Default.Apps,     "Apps",     currentDest == NavDestination.APP_LIBRARY, accent, iconInactive, true, onClick = { onNavigate(NavDestination.APP_LIBRARY) })
                    NavButton(Icons.Default.Settings, "Settings", currentDest == NavDestination.SETTINGS,    accent, iconInactive, true, onClick = { onNavigate(NavDestination.SETTINGS) }, onLongClick = { onRearrangeRequested() })
                    NavButton(Icons.Default.Home,     "Home",     currentDest == NavDestination.HOME,        accent, iconInactive, true, onClick = { onNavigate(NavDestination.HOME) })
                }
            }

            // Connectivity status icons on the end opposite the nav cluster
            Row(
                modifier            = Modifier
                    .align(if (settings.bottomBarShortcutsRight) Alignment.CenterStart else Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(horizontal = 10.dp),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Nav cluster on the right => this cluster sits at the far left,
                // so the driver-end icon (Wi-Fi) must read rightmost: reverse
                statusIcons(settings.bottomBarShortcutsRight)
            }
        }
    } else {
        Column(
            modifier = modifier
                .width(56.dp)
                .fillMaxHeight()
                .background(sidebarBg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Fixed head (top of screen): connectivity status icons, reading
            // far-end-first (Wi-Fi lowest, nearest the thumb cluster)
            Column(
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                statusIcons(true)
            }

            // Slots scroll in the space between the fixed clusters, pinned to
            // the bottom so slot 1 rests just above the vehicle buttons
            // (slot 1..N read bottom-up — see `inverted`)
            Box(
                modifier         = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 6.dp, bottom = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    shortcutsContent()
                }
            }

            // Thumb-end cluster, bottom-up: Home outermost (thumb), then
            // Settings, Apps, CarPlay, Android Auto, then the slots
            HorizontalDivider(color = dividerColor)
            vehicleSlots()
            appsButton()
            settingsButton()
            homeButton()
            Spacer(Modifier.height(4.dp))
        }
    }

    // ── Action sheet dialog ──────────────────────────────────────────────────
    actionSheetSlot?.let { slot ->
        ShortcutActionDialog(
            accent      = accent,
            onChangeApp = {
                actionSheetSlot = null
                onShortcutLongPress(slot)
            },
            onCustomizeIcon = {
                actionSheetSlot = null
                iconPickerSlot = slot
            },
            onRemove = {
                actionSheetSlot = null
                onShortcutRemove(slot)
            },
            onDismiss = { actionSheetSlot = null }
        )
    }

    // ── Icon picker dialog ───────────────────────────────────────────────────
    iconPickerSlot?.let { slot ->
        IconPickerDialog(
            accent          = accent,
            hasNativeIcon   = settings.shortcuts.getOrNull(slot)?.packageName?.isNotEmpty() == true,
            currentOverride = settings.shortcuts.getOrNull(slot)?.customIconOverride,
            onPick  = { icon ->
                iconPickerSlot = null
                onShortcutSetIcon(slot, icon)
            },
            onDismiss = { iconPickerSlot = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutSlot(
    shortcut: ShortcutConfig,
    accent: Color,
    resolvedIcon: Drawable?,
    isDragging: Boolean,
    dragTranslation: Float,
    isHorizontal: Boolean,
    inverted: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit
) {
    val currentOnClick      by rememberUpdatedState(onClick)
    val currentOnLongPress  by rememberUpdatedState(onLongPress)
    val currentOnDragStart  by rememberUpdatedState(onDragStart)
    val currentOnDragDelta  by rememberUpdatedState(onDragDelta)
    val currentOnDragEnd    by rememberUpdatedState(onDragEnd)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(
                if (isHorizontal) Modifier.fillMaxHeight().width(SLOT_SIZE)
                else              Modifier.fillMaxWidth().height(SLOT_SIZE)
            )
            .zIndex(if (isDragging) 1f else 0f)
            .graphicsLayer {
                if (isHorizontal) translationX = dragTranslation else translationY = dragTranslation
                alpha = if (isDragging) 0.55f else 1f
            }
            .combinedClickable(
                onClick     = { currentOnClick() },
                onLongClick = { }
            )
            .pointerInput(isHorizontal, inverted) {
                var longPressTriggered = false
                var hasSignificantDrag = false
                var totalDrag          = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { _ ->
                        longPressTriggered = true
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        // Inverted (vertical) rail: screen-down is DATA-index-up,
                        // so accumulate the logic delta sign-flipped
                        val delta = if (isHorizontal) dragAmount.x
                                    else if (inverted) -dragAmount.y
                                    else dragAmount.y
                        totalDrag += delta
                        if (!hasSignificantDrag && kotlin.math.abs(totalDrag) > viewConfiguration.touchSlop) {
                            hasSignificantDrag = true
                            currentOnDragStart()
                        }
                        if (hasSignificantDrag) currentOnDragDelta(delta)
                    },
                    onDragEnd = {
                        if (longPressTriggered && !hasSignificantDrag) currentOnLongPress()
                        if (hasSignificantDrag) currentOnDragEnd()
                        longPressTriggered = false
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    },
                    onDragCancel = {
                        if (hasSignificantDrag) currentOnDragEnd()
                        longPressTriggered = false
                        hasSignificantDrag = false
                        totalDrag          = 0f
                    }
                )
            }
    ) {
        val iconInactive = if (LocalDayMode.current) Color(0xFF777777) else Color(0xFF3A3A3A)
        val override = shortcut.customIconOverride
        when {
            override != null && override != DefaultShortcutIcon.NONE -> {
                Icon(
                    imageVector        = override.toIcon(),
                    contentDescription = shortcut.label,
                    tint               = iconInactive,
                    modifier           = Modifier.size(ICON_SIZE)
                )
            }
            resolvedIcon != null -> {
                // Cache per icon — every slot recomposes each drag frame, and an
                // un-remembered toBitmap allocated a fresh bitmap per slot per frame
                val bmp = remember(resolvedIcon) { resolvedIcon.toBitmap(44, 44) }
                Icon(
                    painter            = BitmapPainter(bmp.asImageBitmap()),
                    contentDescription = shortcut.label,
                    tint               = Color.Unspecified,
                    modifier           = Modifier.size(26.dp)
                )
            }
            shortcut.isDefault -> {
                Icon(
                    imageVector        = shortcut.defaultIcon.toIcon(),
                    contentDescription = shortcut.label,
                    tint               = iconInactive,
                    modifier           = Modifier.size(ICON_SIZE)
                )
            }
            else -> {
                Icon(
                    imageVector        = Icons.Default.Add,
                    contentDescription = "Add shortcut",
                    tint               = if (LocalDayMode.current) Color(0xFFBBBBBB) else Color(0xFF252525),
                    modifier           = Modifier.size(ICON_SIZE)
                )
            }
        }
    }
}

/**
 * Fixed dedicated rail button (CarPlay / Android Auto). Same slot metrics
 * and icon style as the app shortcuts so the rail stays visually uniform.
 */
@Composable
private fun DedicatedSlot(
    icon: ImageVector,
    label: String,
    isHorizontal: Boolean,
    onClick: () -> Unit
) {
    val iconInactive = if (LocalDayMode.current) Color(0xFF777777) else Color(0xFF3A3A3A)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(
                if (isHorizontal) Modifier.fillMaxHeight().width(SLOT_SIZE)
                else              Modifier.fillMaxWidth().height(SLOT_SIZE)
            )
            .clickable(onClick = onClick)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = iconInactive,
            modifier           = Modifier.size(ICON_SIZE)
        )
    }
}

@Composable
private fun ShortcutActionDialog(
    accent: Color,
    onChangeApp: () -> Unit,
    onCustomizeIcon: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp)
                .width(180.dp)
        ) {
            ActionRow("CHANGE APP",     Icons.Default.SwapHoriz, accent, onChangeApp)
            HorizontalDivider(color = Color(0xFF1A1A1A))
            ActionRow("CUSTOMIZE ICON", Icons.Default.Palette,   accent, onCustomizeIcon)
            HorizontalDivider(color = Color(0xFF1A1A1A))
            ActionRow("REMOVE",         Icons.Default.Delete,     Color(0xFF993333), onRemove)
        }
    }
}

@Composable
private fun ActionRow(label: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, color = tint, fontSize = 10.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun IconPickerDialog(
    accent: Color,
    hasNativeIcon: Boolean,
    currentOverride: DefaultShortcutIcon?,
    onPick: (DefaultShortcutIcon?) -> Unit,
    onDismiss: () -> Unit
) {
    val vectorOptions = DefaultShortcutIcon.entries.filter { it != DefaultShortcutIcon.NONE }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF111111))
                .border(1.dp, Color(0xFF1E1E1E), RoundedCornerShape(4.dp))
                .padding(12.dp)
        ) {
            Text(
                "CHOOSE ICON",
                color         = Color(0xFF888888),
                fontSize      = 9.sp,
                letterSpacing = 2.sp,
                modifier      = Modifier.padding(bottom = 10.dp)
            )

            if (hasNativeIcon) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (currentOverride == null) accent.copy(alpha = 0.15f) else Color.Transparent)
                        .clickable { onPick(null) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.Apps, null, tint = if (currentOverride == null) accent else Color(0xFF666666), modifier = Modifier.size(18.dp))
                    Text(
                        "NATIVE APP ICON",
                        color         = if (currentOverride == null) accent else Color(0xFF888888),
                        fontSize      = 9.sp,
                        letterSpacing = 1.sp
                    )
                }
                HorizontalDivider(color = Color(0xFF1A1A1A), modifier = Modifier.padding(vertical = 6.dp))
            }

            LazyVerticalGrid(
                columns               = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement   = Arrangement.spacedBy(5.dp),
                modifier              = Modifier.heightIn(max = 300.dp)
            ) {
                items(vectorOptions) { iconOption ->
                    val isSelected = currentOverride == iconOption
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isSelected) accent.copy(alpha = 0.18f) else Color(0xFF1A1A1A))
                            .clickable { onPick(iconOption) }
                    ) {
                        Icon(
                            imageVector        = iconOption.toIcon(),
                            contentDescription = iconOption.name,
                            tint               = if (isSelected) accent else Color(0xFF888888),
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NavButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    accent: Color,
    iconInactive: Color,
    isHorizontal: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val isDayMode = LocalDayMode.current
    val activeIconColor = if (isDayMode) Color(0xFF111111) else Color.White
    val activeBg = if (isDayMode) Color(0xFF000000).copy(alpha = 0.08f) else Color.White.copy(alpha = 0.06f)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(
                if (isHorizontal) Modifier.fillMaxHeight().width(SLOT_SIZE)
                else              Modifier.fillMaxWidth().height(SLOT_SIZE)
            )
            .background(if (isActive) activeBg else Color.Transparent)
            .then(
                if (onLongClick != null)
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                else
                    Modifier.clickable(onClick = onClick)
            )
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = if (isActive) activeIconColor else iconInactive,
            modifier           = Modifier.size(ICON_SIZE)
        )
    }
}

fun DefaultShortcutIcon.toIcon(): ImageVector = when (this) {
    // Navigation & vehicle
    DefaultShortcutIcon.RADIO       -> Icons.Default.Radio
    DefaultShortcutIcon.CAMERA      -> Icons.Default.CameraAlt
    DefaultShortcutIcon.PHONE       -> Icons.Default.Phone
    DefaultShortcutIcon.MAP         -> Icons.Default.Map
    DefaultShortcutIcon.NAVIGATION  -> Icons.Default.Navigation
    DefaultShortcutIcon.CAR         -> Icons.Default.DirectionsCar
    DefaultShortcutIcon.GAS_STATION -> Icons.Default.LocalGasStation
    DefaultShortcutIcon.DASHBOARD   -> Icons.Default.Speed
    // Audio & media
    DefaultShortcutIcon.MUSIC       -> Icons.Default.MusicNote
    DefaultShortcutIcon.SPEAKER     -> Icons.Default.Speaker
    DefaultShortcutIcon.HEADSET     -> Icons.Default.Headset
    DefaultShortcutIcon.EQUALIZER   -> Icons.Default.Equalizer
    DefaultShortcutIcon.VOLUME_UP   -> Icons.Default.VolumeUp
    // Connectivity
    DefaultShortcutIcon.BLUETOOTH   -> Icons.Default.Bluetooth
    DefaultShortcutIcon.WIFI        -> Icons.Default.Wifi
    // Lighting & climate
    DefaultShortcutIcon.LIGHTBULB   -> Icons.Default.Lightbulb
    DefaultShortcutIcon.BRIGHTNESS  -> Icons.Default.BrightnessHigh
    DefaultShortcutIcon.AC          -> Icons.Default.AcUnit
    DefaultShortcutIcon.THERMOSTAT  -> Icons.Default.Thermostat
    // General utility
    DefaultShortcutIcon.TV          -> Icons.Default.Tv
    DefaultShortcutIcon.VIDEOCAM    -> Icons.Default.Videocam
    DefaultShortcutIcon.STAR        -> Icons.Default.Star
    DefaultShortcutIcon.MESSAGE     -> Icons.Default.Message
    DefaultShortcutIcon.TIMER       -> Icons.Default.Timer
    DefaultShortcutIcon.LOCK        -> Icons.Default.Lock
    DefaultShortcutIcon.SETTINGS    -> Icons.Default.Settings
    DefaultShortcutIcon.FAVORITE    -> Icons.Default.Favorite
    // Web / location
    DefaultShortcutIcon.GLOBE       -> Icons.Default.Language
    DefaultShortcutIcon.NONE        -> Icons.Default.Apps
}
