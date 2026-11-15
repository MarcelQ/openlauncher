package com.openlauncher.app.ui.widget

import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.NowPlayingCustomAction
import com.openlauncher.app.model.NowPlayingQueueItem
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.findShuffleActionIndex
import com.openlauncher.app.model.shuffleStateFromTitle
import com.openlauncher.app.service.MediaListenerService
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun NowPlayingWidget(
    state: NowPlayingState?,
    accent: Color,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onTapToOpenApp: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    // Grid footprint of this card — a big enough card shows the queue list
    // inline instead of in a dialog
    spanColumns: Int = 1,
    spanRows: Int = 1,
    // Tap card = play/pause, swipe = prev/next, hides the play + next buttons
    tapSwipeControls: Boolean = false,
    modifier: Modifier = Modifier,
    isEditing: Boolean = false,
    isDayMode: Boolean = false,
    hardwareRadio: com.openlauncher.app.viewmodel.LauncherViewModel.HardwareRadioState? = null,
    onLaunchHardwareRadio: () -> Unit = {},
    onStopHardwareRadio: () -> Unit = {},
    onRadioSeekUp: () -> Unit = {},
    onRadioSeekDown: () -> Unit = {},
    onRadioCycleFm: () -> Unit = {},
    onRadioSwitchAm: () -> Unit = {},
    onRadioTune: (band: String, freq: Float) -> Unit = { _, _ -> },
    onAssignRadio: () -> Unit = {}
) {
    val context     = LocalContext.current
    val isConnected by MediaListenerService.isConnected.collectAsState()
    val hasContent  = state != null && state.title.isNotEmpty()

    var selectedSource by rememberSaveable { mutableStateOf("Any Player") }

    // Auto-switch to radio view when hardware radio is detected.
    // Keyed on presence (not the state object) so freq/RDS updates don't
    // keep forcing the radio view after the user picks "Any Player".
    val hasHardwareRadio = hardwareRadio != null
    LaunchedEffect(hasHardwareRadio) {
        if (hasHardwareRadio) selectedSource = "FM/AM Radio"
        else if (selectedSource == "FM/AM Radio") selectedSource = "Any Player"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
    ) {
        // 1. CONDITIONAL VIEW TOGGLE
        if (selectedSource == "FM/AM Radio") {
            // Real-tuner radio deck — mirrors the MCU or the radio app's session
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)
            ) {
                RadioDeck(
                    accent = accent,
                    isDayMode = isDayMode,
                    hardwareRadio = hardwareRadio,
                    onLaunchHardwareRadio = onLaunchHardwareRadio,
                    onStopHardwareRadio = onStopHardwareRadio,
                    onRadioSeekUp = onRadioSeekUp,
                    onRadioSeekDown = onRadioSeekDown,
                    onRadioCycleFm = onRadioCycleFm,
                    onRadioSwitchAm = onRadioSwitchAm,
                    onRadioTune = onRadioTune,
                    onAssignRadio = onAssignRadio,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            // Standard Elegant Modern Media Player
            StandardMinimalPlayer(
                state = state,
                accent = accent,
                hasContent = hasContent,
                isEditing = isEditing,
                isDayMode = isDayMode,
                isConnected = isConnected,
                onPlayPause = onPlayPause,
                onNext = onNext,
                onPrev = onPrev,
                onTapToOpenApp = onTapToOpenApp,
                onVolumeDown = onVolumeDown,
                onVolumeUp = onVolumeUp,
                spanColumns = spanColumns,
                spanRows = spanRows,
                tapSwipeControls = tapSwipeControls,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 2. FLOATING MULTI-SOURCE SELECTOR (Top-Right, always overlayed)
        var menuExpanded by remember { mutableStateOf(false) }
        val selectorIconColor = if (isDayMode) Color.Black.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
        
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp, top = 4.dp)
        ) {
            IconButton(
                onClick = { menuExpanded = true },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "Source Selector",
                    tint = selectorIconColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            val dropdownBg   = if (isDayMode) Color(0xFFF0F0F0) else MaterialTheme.colorScheme.background
            val dropdownText = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.background(dropdownBg)
            ) {
                DropdownMenuItem(
                    text = { Text("Any Player", color = dropdownText, fontSize = 11.sp) },
                    onClick = {
                        selectedSource = "Any Player"
                        menuExpanded = false
                    },
                    leadingIcon = { Icon(Icons.Default.MusicNote, null, tint = accent, modifier = Modifier.size(14.dp)) }
                )
                DropdownMenuItem(
                    text = { Text("FM/AM Radio", color = dropdownText, fontSize = 11.sp) },
                    onClick = {
                        selectedSource = "FM/AM Radio"
                        menuExpanded = false
                    },
                    leadingIcon = { Icon(Icons.Default.Radio, null, tint = accent, modifier = Modifier.size(14.dp)) }
                )
            }
        }
    }
}


/**
 * Radio deck backed by a REAL tuner only — either the vendor MCU (full control,
 * canTune = true) or the radio app's MediaSession (seek/open/stop, read-only
 * band). No simulated static, no demo stations: when no source exists the deck
 * says so and offers to assign the unit's radio app.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RadioDeck(
    accent: Color,
    isDayMode: Boolean,
    hardwareRadio: com.openlauncher.app.viewmodel.LauncherViewModel.HardwareRadioState?,
    onLaunchHardwareRadio: () -> Unit,
    onStopHardwareRadio: () -> Unit,
    onRadioSeekUp: () -> Unit,
    onRadioSeekDown: () -> Unit,
    onRadioCycleFm: () -> Unit,
    onRadioSwitchAm: () -> Unit,
    onRadioTune: (band: String, freq: Float) -> Unit,
    onAssignRadio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val dimColor     = if (isDayMode) Color(0xFF444444) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    val borderColor  = if (isDayMode) Color(0xFF777777) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)

    if (hardwareRadio == null) {
        // No real tuner detected — be honest about it instead of simulating one
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Radio, null, tint = dimColor, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                "NO RADIO SOURCE",
                color = contentColor.copy(alpha = 0.85f),
                fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "Start your head unit's radio app — or assign it below so Open Launcher can mirror and control it",
                color = dimColor, fontSize = 7.sp, fontFamily = FontFamily.Monospace,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 10.sp,
                modifier = Modifier.padding(horizontal = 28.dp)
            )
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .height(26.dp)
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    .clip(RoundedCornerShape(3.dp))
                    .clickable { onAssignRadio() }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "ASSIGN RADIO APP",
                    color = accent, fontSize = 7.5.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
        }
        return
    }

    // Power is a local veil over the live source: stopping doesn't always clear
    // the vendor state, so a fresh emission flips it back on (radio re-engaged)
    var isManuallyOff by remember { mutableStateOf(false) }
    LaunchedEffect(hardwareRadio) {
        if (isManuallyOff) isManuallyOff = false
    }
    val powerOn = !isManuallyOff

    val displayBand = hardwareRadio.band.uppercase()
    val freqClean   = hardwareRadio.freq.replace(Regex("[^0-9.]"), "")
    val freqFloat   = freqClean.toFloatOrNull()
    val displayFreq = freqClean.ifEmpty { hardwareRadio.freq }
    val displayUnit = if (hardwareRadio.isAm) "kHz" else "MHz"

    val chipInactiveBg = if (isDayMode) Color(0xFFE0E0E0) else Color(0xFF1A1A1A)
    val chipActiveBg   = if (isDayMode) Color(0xFF222222) else Color(0xFFDDDDDD)
    val chipActiveText = if (isDayMode) Color.White else Color(0xFF111111)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ── Row 1: band (chips when switchable, read-only chip otherwise) + power
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hardwareRadio.canTune) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("FM1", "FM2", "FM3", "AM").forEach { b ->
                        val active = displayBand == b
                        Box(
                            modifier = Modifier
                                .height(22.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (active) chipActiveBg else chipInactiveBg)
                                .border(1.dp, if (active) borderColor else borderColor.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                                .clickable { if (b == "AM") onRadioSwitchAm() else onRadioCycleFm() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                b,
                                color = if (active) chipActiveText else dimColor,
                                fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            } else {
                // Session-mirrored tuner: band is whatever the radio app reports
                Box(
                    modifier = Modifier
                        .height(22.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(chipActiveBg)
                        .border(1.dp, borderColor, RoundedCornerShape(3.dp))
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        displayBand,
                        color = chipActiveText,
                        fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(if (powerOn) chipActiveBg else chipInactiveBg)
                    .border(1.5.dp, borderColor, CircleShape)
                    .clickable {
                        if (powerOn) { onStopHardwareRadio(); isManuallyOff = true }
                        else { onLaunchHardwareRadio(); isManuallyOff = false }
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew, "PWR",
                    tint = if (powerOn) chipActiveText else dimColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // ── Row 2: live frequency + station/RDS line ──────────────────────────
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    displayFreq,
                    color = if (powerOn) contentColor else contentColor.copy(alpha = 0.3f),
                    fontSize = 34.sp, fontWeight = FontWeight.Light,
                    fontFamily = FontFamily.Monospace, letterSpacing = 0.sp
                )
                Text(
                    displayUnit,
                    color = dimColor, fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(bottom = 5.dp)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = (if (!powerOn) "RADIO OFF" else hardwareRadio.stationName ?: "LIVE").uppercase(),
                    color = if (powerOn) accent else dimColor.copy(alpha = 0.5f),
                    fontSize = 8.sp, fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (powerOn) {
                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(accent))
                }
            }
        }

        // ── Row 3: seek + open ────────────────────────────────────────────────
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RadioFlatButton(
                label = "◄ SEEK", enabled = powerOn, active = false,
                accent = accent, borderColor = borderColor, dimColor = dimColor,
                modifier = Modifier.weight(1f),
                onClick = onRadioSeekDown
            )
            RadioFlatButton(
                label = "OPEN", enabled = true, active = false,
                accent = accent, borderColor = borderColor, dimColor = dimColor,
                modifier = Modifier.weight(1f),
                onClick = onLaunchHardwareRadio
            )
            RadioFlatButton(
                label = "SEEK ►", enabled = powerOn, active = false,
                accent = accent, borderColor = borderColor, dimColor = dimColor,
                modifier = Modifier.weight(1f),
                onClick = onRadioSeekUp
            )
        }

        // ── Presets: only when the backend supports direct frequency tuning ──
        if (hardwareRadio.canTune) {
            var fmPresets by rememberSaveable { mutableStateOf(listOf(88.5f, 91.5f, 98.1f, 101.9f, 104.3f, 107.5f)) }
            var amPresets by rememberSaveable { mutableStateOf(listOf(540f, 680f, 820f, 1040f, 1260f, 1420f)) }
            val isFm           = !hardwareRadio.isAm
            val currentPresets = if (isFm) fmPresets else amPresets
            val tolerance      = if (isFm) 0.15f else 5f

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (pIdx in 0 until 6) {
                    val presetFreq = currentPresets[pIdx]
                    val isTuned    = freqFloat != null && abs(freqFloat - presetFreq) < tolerance
                    val presetBg = when {
                        isTuned && isDayMode -> Color(0xFF222222)
                        isTuned              -> accent.copy(alpha = 0.12f)
                        else                 -> Color.Transparent
                    }
                    val presetBorderColor = when {
                        isTuned && isDayMode -> Color(0xFF222222)
                        isTuned              -> accent
                        isDayMode            -> Color(0xFFCCCCCC)
                        else                 -> Color(0xFF1D2024)
                    }
                    val presetNumColor = when {
                        isTuned && isDayMode -> Color.White
                        isTuned              -> accent
                        isDayMode            -> Color(0xFF444444)
                        else                 -> Color(0xFF777777)
                    }
                    val presetFreqColor = when {
                        isTuned && isDayMode -> Color.White.copy(alpha = 0.9f)
                        isTuned              -> accent.copy(alpha = 0.9f)
                        isDayMode            -> Color(0xFF666666)
                        else                 -> Color(0xFF777777).copy(alpha = 0.7f)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(28.dp)
                            .border(1.dp, presetBorderColor, RoundedCornerShape(2.dp))
                            .clip(RoundedCornerShape(2.dp))
                            .background(presetBg)
                            .combinedClickable(
                                enabled = powerOn,
                                onClick = { onRadioTune(displayBand, presetFreq) },
                                onLongClick = {
                                    // Store the REAL current frequency on this preset
                                    freqFloat?.let { f ->
                                        if (isFm) fmPresets = fmPresets.toMutableList().also { it[pIdx] = f }
                                        else      amPresets = amPresets.toMutableList().also { it[pIdx] = f }
                                    }
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            Text("${pIdx + 1}", color = presetNumColor, fontSize = 7.sp,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                            Text(
                                if (isFm) "%.1f".format(presetFreq) else "%.0f".format(presetFreq),
                                color = presetFreqColor, fontSize = 6.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioFlatButton(
    label: String,
    enabled: Boolean,
    active: Boolean,
    accent: Color,
    borderColor: Color,
    dimColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(28.dp)
            .border(1.dp, if (active) accent else borderColor, RoundedCornerShape(3.dp))
            .clip(RoundedCornerShape(3.dp))
            .background(if (active) accent.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = when {
                !enabled -> dimColor.copy(alpha = 0.35f)
                active   -> accent
                else     -> dimColor
            },
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun StandardMinimalPlayer(
    state: NowPlayingState?,
    accent: Color,
    hasContent: Boolean,
    isEditing: Boolean,
    isDayMode: Boolean,
    isConnected: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onTapToOpenApp: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    spanColumns: Int,
    spanRows: Int,
    tapSwipeControls: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Live level of the system music stream, shown as a number between the
    // volume buttons. Polled rather than event-driven because external
    // changes (hardware keys, phone calls) must refresh it too; the read is
    // a cheap binder-free local call.
    val audioManager = remember {
        context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager
    }
    var volPct by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        while (true) {
            volPct = audioManager?.let {
                volumePercent(
                    it.getStreamVolume(AudioManager.STREAM_MUSIC),
                    it.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                )
            } ?: -1
            delay(800)
        }
    }
    
    // UI Theme colors
    val idleIconColor = if (isDayMode) Color(0xFF555555) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
    val idleTextColor = if (isDayMode) Color(0xFF555555) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)
    val contentTextColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val subTextColor = if (isDayMode) Color(0xFF666666) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.30f)

    Box(modifier = modifier) {
        if (!hasContent) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = idleIconColor, modifier = Modifier.size(24.dp))
                    Text("NO MEDIA PLAYING", color = idleTextColor, fontSize = 7.sp, letterSpacing = 1.sp)
                }

                // Volume is the SYSTEM music stream, so the buttons stay useful
                // even while no media session is alive to show a track. The
                // rocker sits at the bottom edge with oversized hit areas and
                // the current level as a number between the buttons.
                VolumeControlRow(
                    volPct       = volPct,
                    iconColor    = idleIconColor,
                    numberColor  = idleTextColor,
                    buttonBg     = if (isDayMode) Color(0xFF000000).copy(alpha = 0.06f)
                                   else Color(0xFFFFFFFF).copy(alpha = 0.07f),
                    onVolumeDown = onVolumeDown,
                    onVolumeUp   = onVolumeUp,
                    isEditing    = isEditing,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        } else {
            // Non-null playing track state
            val nonNullState = state!!
            var positionMs by remember { mutableLongStateOf(nonNullState.controller?.playbackState?.position ?: 0L) }
            var showQueue by remember { mutableStateOf(false) }
            // Shuffle: only offered when the source app actually advertises a
            // shuffle custom action on its session (see MediaListenerService).
            val shuffleAction = nonNullState.customActions
                .getOrNull(findShuffleActionIndex(nonNullState.customActions))
            // The platform never reports shuffle state, but many apps carry it in
            // the action title ("Shuffle on"/"Shuffle: off"). Prefer that real
            // state so the button lights up when shuffle is genuinely active; the
            // local mirror is only a fallback for titles that say nothing.
            val shuffleTitleState = shuffleAction?.let { shuffleStateFromTitle(it.title) }
            var shuffleToggled by remember(shuffleAction?.action) { mutableStateOf(false) }
            val shuffleOn = shuffleTitleState ?: shuffleToggled
            val durationMs = nonNullState.controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

            LaunchedEffect(nonNullState.isPlaying, nonNullState.title) {
                while (nonNullState.isPlaying) {
                    positionMs = nonNullState.controller?.playbackState?.position ?: positionMs
                    delay(500)
                }
            }

            // Draw Album Art as background with smooth blur overlay if present
            val hasAlbumArt = nonNullState.albumArt != null
            val useDarkTheme = hasAlbumArt || !isDayMode

            // Adaptive title contrast: sample the album-art thumbnail and pick black
            // or white text so the title stays readable on bright cover art. The
            // card's 25% black dim overlay is folded into the decision threshold.
            val artLuminance = remember(nonNullState.albumArt) {
                sampleArtPixels(nonNullState.albumArt)?.let { averageLuminance(it) }
            }
            val currentTextColor = when {
                hasAlbumArt && artLuminance != null ->
                    if (useBlackTextOnArtwork(artLuminance)) Color(0xFF111111) else Color.White
                hasAlbumArt -> Color.White
                isDayMode -> Color(0xFF111111)
                else -> MaterialTheme.colorScheme.onBackground
            }
            val currentSubTextColor = if (hasAlbumArt) currentTextColor.copy(alpha = 0.6f) else if (isDayMode) Color(0xFF666666) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            val currentProgressColor = if (useDarkTheme) accent else if (isDayMode) Color(0xFF111111) else accent
            val currentProgressTrack = currentTextColor.copy(alpha = 0.15f)
            val currentIconColor = currentTextColor.copy(alpha = 0.75f)
            val currentPlayBgColor = if (useDarkTheme) accent.copy(alpha = 0.9f) else if (isDayMode) Color(0xFF111111) else accent.copy(alpha = 0.9f)
            val currentPlayIconColor = if (useDarkTheme) Color.Black else Color.White

            if (hasAlbumArt) {
                // Prefer the full-resolution art URI when the source app provides
                // one — the metadata bitmap is often a downscaled notification
                // thumbnail that looks soft stretched across the widget. Falls back
                // to the bitmap if the URI fails to load, and renders with high
                // filter quality so upscaling stays smooth either way.
                coil.compose.AsyncImage(
                    model = nonNullState.artUri ?: nonNullState.albumArt,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
                    error = nonNullState.albumArt?.let {
                        androidx.compose.ui.graphics.painter.BitmapPainter(it.asImageBitmap())
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // 25% dimming layer overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.25f))
                )
            }

            // Top scrim so the title stays readable against busy artwork (and
            // over the card's own background in the dark theme). Tinted to be
            // the opposite of the adaptive title color: dark scrim under light
            // text, light scrim under dark text.
            val scrimTint = if (currentTextColor == Color(0xFF111111)) Color.White else Color.Black
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to scrimTint.copy(alpha = 0.55f),
                            1f to scrimTint.copy(alpha = 0f)
                        )
                    )
            )

            // Tap-to-pause + swipe transport (Settings ▸ Tap & Swipe Controls).
            // The overlay sits BEHIND the content Column: taps on real controls
            // (buttons, title) are caught by them first; anything landing on
            // bare card area becomes play/pause, and a horizontal drag becomes
            // prev/next. Consumed here, so a swipe on this card never turns
            // into a home-screen page flip.
            if (tapSwipeControls && !isEditing) {
                val swipeSlopPx = with(LocalDensity.current) { 36.dp.toPx() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(tapSwipeControls, nonNullState.controller) {
                            detectTapGestures { onPlayPause() }
                        }
                        .pointerInput(tapSwipeControls, nonNullState.controller) {
                            var totalDrag = 0f
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    when (swipeTransportAction(totalDrag, swipeSlopPx)) {
                                        1 -> onNext()
                                        -1 -> onPrev()
                                    }
                                },
                                onDragCancel = { totalDrag = 0f }
                            ) { change, dragAmountX ->
                                // detectHorizontalDragGestures already hands us
                                // the horizontal delta as a Float.
                                change.consume()
                                totalDrag += dragAmountX
                            }
                        }
                )
            }

            val showInlineQueue = showQueue && inlineQueueAllowed(spanColumns, spanRows)
            if (showInlineQueue) {
                // Large cards (≥4×4 cells): the queue list replaces the player
                // UI in place — no dialog needed for the track list.
                QueueInlineView(
                    state        = nonNullState,
                    accent       = accent,
                    textColor    = currentTextColor,
                    subTextColor = currentSubTextColor,
                    iconColor    = currentIconColor,
                    isEditing    = isEditing,
                    onBack       = { showQueue = false }
                )
            } else Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Track info (top — clickable to open app)
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .let { if (!isEditing) it.clickable { onTapToOpenApp() } else it }
                ) {
                    Text(
                        text = nonNullState.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = currentTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp
                    )
                    Text(
                        text = nonNullState.artist.ifEmpty { "Unknown" },
                        style = MaterialTheme.typography.bodySmall,
                        color = currentSubTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp
                    )
                }

                // Progress + controls (bottom)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (durationMs > 0) {
                        LinearProgressIndicator(
                            progress = { (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(2.dp),
                            color = currentProgressColor,
                            trackColor = currentProgressTrack
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(formatMs(positionMs), style = MaterialTheme.typography.labelSmall, color = currentSubTextColor.copy(alpha = 0.75f), fontSize = 9.sp)
                            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = currentSubTextColor.copy(alpha = 0.75f), fontSize = 9.sp)
                        }
                    }

                    // Transport row. Every control is a hit box sized for
                    // in-car tapping (48 dp and up, no glove-unfriendly 36 dp
                    // targets): shuffle (only when the source advertises a
                    // shuffle action), prev, play/pause, next and an
                    // icon-only queue opener.
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (shuffleAction != null) {
                            MediaHitBox(
                                size      = 48.dp,
                                accent    = accent,
                                active    = shuffleOn,
                                isEditing = isEditing,
                                onClick = {
                                    // Optimistic mirror until the app's action
                                    // title confirms the real state.
                                    shuffleToggled = !shuffleOn
                                    try {
                                        nonNullState.controller?.transportControls
                                            ?.sendCustomAction(shuffleAction.action, shuffleAction.extras)
                                    } catch (_: Exception) { }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = if (shuffleOn) "Shuffle on" else "Shuffle off",
                                    tint = if (shuffleOn) accent else currentIconColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        MediaHitBox(
                            size = 56.dp, accent = accent, isEditing = isEditing,
                            onClick = onPrev
                        ) {
                            Icon(Icons.Default.SkipPrevious, "Prev", tint = currentIconColor, modifier = Modifier.size(30.dp))
                        }
                        // With Tap & Swipe Controls the gesture layer replaces
                        // play/pause (tap) and next (swipe) — prev stays visible
                        // for a single obvious back jump.
                        if (!tapSwipeControls) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(currentPlayBgColor)
                            ) {
                                IconButton(onClick = { if (!isEditing) onPlayPause() }, modifier = Modifier.size(64.dp)) {
                                    Icon(
                                        imageVector = if (nonNullState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (nonNullState.isPlaying) "Pause" else "Play",
                                        tint = currentPlayIconColor,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                            MediaHitBox(
                                size = 56.dp, accent = accent, isEditing = isEditing,
                                onClick = onNext
                            ) {
                                Icon(Icons.Default.SkipNext, "Next", tint = currentIconColor, modifier = Modifier.size(30.dp))
                            }
                        }
                        // Offline queue view: the queue is served by the OS from
                        // the source app's session — no internet involved. The
                        // icon-only button appears only when the source exposes
                        // a queue at all.
                        if (nonNullState.queue.size >= 2) {
                            MediaHitBox(
                                size      = 48.dp,
                                accent    = accent,
                                active    = showQueue,
                                isEditing = isEditing,
                                onClick   = { showQueue = !showQueue }
                            ) {
                                Icon(
                                    Icons.Default.QueueMusic, "Queue",
                                    tint = if (showQueue) accent else currentIconColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    // Volume pinned to the very bottom of the card: oversized
                    // rocker (each button fills the row height) with the system
                    // music level as a number between the buttons. Drives the
                    // system stream, so it works even with no live session.
                    VolumeControlRow(
                        volPct       = volPct,
                        iconColor    = currentIconColor,
                        numberColor  = currentTextColor,
                        buttonBg     = currentTextColor.copy(alpha = 0.08f),
                        onVolumeDown = onVolumeDown,
                        onVolumeUp   = onVolumeUp,
                        isEditing    = isEditing,
                        modifier     = Modifier.fillMaxWidth()
                    )
                }
            }

            // Small cards get the queue as a dialog; big cards (≥4×4) already
            // replaced the player UI with the inline list above.
            if (showQueue && !showInlineQueue) {
                NowPlayingQueueSheet(
                    state = nonNullState,
                    accent = accent,
                    isDayMode = isDayMode,
                    onDismiss = { showQueue = false }
                )
            }
        }
    }
}

/**
 * Square touch target sized for in-car tapping. The icon is centered inside a
 * fully clickable box — Material's default 48 dp insets alone leave the old
 * 36–40 dp icon buttons too small for a moving vehicle.
 */
@Composable
private fun MediaHitBox(
    size: androidx.compose.ui.unit.Dp,
    accent: Color,
    isEditing: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) accent.copy(alpha = 0.20f) else Color.Transparent)
            .let { if (isEditing) it else it.clickable { onClick() } },
        contentAlignment = Alignment.Center
    ) { content() }
}

/**
 * Volume rocker: two buttons that each fill the whole row height (and half
 * the width minus the readout), with the current system music level as a
 * number between them — the layout head-unit drivers expect.
 */
@Composable
private fun VolumeControlRow(
    volPct: Int,
    iconColor: Color,
    numberColor: Color,
    buttonBg: Color,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    isEditing: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.height(52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(buttonBg)
                .let { if (isEditing) it else it.clickable { onVolumeDown() } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.VolumeDown, "Volume down", tint = iconColor, modifier = Modifier.size(26.dp))
        }
        Text(
            text = if (volPct >= 0) "$volPct" else "—",
            color = numberColor,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.width(44.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(6.dp))
                .background(buttonBg)
                .let { if (isEditing) it else it.clickable { onVolumeUp() } },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.VolumeUp, "Volume up", tint = iconColor, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * Full-card queue list for large cards (≥4×4 grid cells): replaces the
 * player UI in place instead of opening a dialog. Data comes from the OS
 * media session — fully offline — and tapping a row jumps the source app to
 * that queue item. The list stays open after a jump so several tracks can
 * be browsed in sequence.
 */
@Composable
private fun QueueInlineView(
    state: NowPlayingState,
    accent: Color,
    textColor: Color,
    subTextColor: Color,
    iconColor: Color,
    isEditing: Boolean,
    onBack: () -> Unit
) {
    val queue = state.queue
    val currentIndex = queueHighlightIndex(state.activeQueueItemId, queue, state.title)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = iconColor, modifier = Modifier.size(20.dp))
            }
            Text(
                "QUEUE · ${queue.size} TRACKS",
                color = textColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            itemsIndexed(queue) { index, item ->
                val active = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) accent.copy(alpha = 0.18f) else Color.Transparent)
                        .let {
                            if (isEditing) it else it.clickable {
                                try {
                                    state.controller?.transportControls?.skipToQueueItem(item.id)
                                } catch (_: Exception) { }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "%02d".format(index + 1),
                        color = if (active) accent else subTextColor,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.title,
                            color = if (active) textColor else textColor.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                        )
                        if (item.subtitle.isNotEmpty()) {
                            Text(
                                item.subtitle,
                                color = subTextColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Queue sheet for small cards. Everything is served by the OS from the
 * source app's MediaSession — no network involved — and tapping a row jumps
 * the source app to that queue item via its TransportControls.
 */
@Composable
private fun NowPlayingQueueSheet(
    state: NowPlayingState,
    accent: Color,
    isDayMode: Boolean,
    onDismiss: () -> Unit
) {
    val queue = state.queue
    val currentIndex = queueHighlightIndex(state.activeQueueItemId, queue, state.title)
    val titleColor = if (isDayMode) Color(0xFF111111) else Color.White
    val rowColor = if (isDayMode) Color(0xFF444444) else Color(0xFFB8C4D0)
    val subColor = if (isDayMode) Color(0xFF777777) else Color(0xFF6B7684)
    val numColor = if (isDayMode) Color(0xFF888888) else Color(0xFF55606C)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = if (isDayMode) Color(0xFFFAFAFA) else Color(0xFF14181D),
        title = {
            Text(
                "QUEUE · ${queue.size} TRACKS",
                color = titleColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                itemsIndexed(queue) { index, item ->
                    val active = index == currentIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (active) accent.copy(alpha = 0.18f) else Color.Transparent)
                            .clickable {
                                try {
                                    state.controller?.transportControls?.skipToQueueItem(item.id)
                                } catch (_: Exception) { }
                                onDismiss()
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "%02d".format(index + 1),
                            color = if (active) accent else numColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                color = if (active) titleColor else rowColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
                            )
                            if (item.subtitle.isNotEmpty()) {
                                Text(
                                    item.subtitle,
                                    color = subColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    )
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

// ── Artwork contrast helpers (pure parts JVM-tested in NowPlayingContrastTest) ──
/** Rec. 709 luma of the sampled artwork pixels, 0 (black) .. 1 (white). */
internal fun averageLuminance(argb: IntArray): Float {
    if (argb.isEmpty()) return 0f
    var sum = 0.0
    for (c in argb) {
        val r = (c shr 16 and 0xFF) / 255.0
        val g = (c shr 8 and 0xFF) / 255.0
        val b = (c and 0xFF) / 255.0
        sum += 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
    return (sum / argb.size).toFloat()
}

/** Switch to black title text once even the dimmed artwork stays bright; the
 *  0.75 factor folds in the card's 25%-black overlay drawn over the artwork. */
internal fun useBlackTextOnArtwork(luminance: Float): Boolean = luminance * 0.75f > 0.5f

/** Downscale the cover art to a 12×12 patch and read its raw ARGB pixels. */
private fun sampleArtPixels(art: Bitmap?): IntArray? {
    if (art == null || art.width == 0 || art.height == 0) return null
    return try {
        val small = Bitmap.createScaledBitmap(art, 12, 12, true)
        IntArray(144).also { small.getPixels(it, 0, 12, 0, 0, 12, 12) }
    } catch (_: Exception) {
        null // recycle race with the media session, etc. — fall back to white text
    }
}

// ── Queue highlight helper (pure part JVM-tested in NowPlayingQueueTest) ─────
/**
 * Which queue row represents the currently playing track. Prefer the
 * session's activeQueueItemId; apps that leave it unset (-1) or stale fall
 * back to a case-insensitive title match. Returns -1 when nothing matches.
 */
internal fun queueHighlightIndex(
    activeQueueItemId: Long,
    queue: List<NowPlayingQueueItem>,
    playingTitle: String?
): Int {
    if (activeQueueItemId >= 0) {
        val byId = queue.indexOfFirst { it.id == activeQueueItemId }
        if (byId >= 0) return byId
    }
    val title = playingTitle?.trim()?.lowercase()
    if (title.isNullOrEmpty()) return -1
    return queue.indexOfFirst { it.title.trim().lowercase() == title }
}

// ── Layout / volume helpers (pure parts JVM-tested in NowPlayingQueueTest) ───
/**
 * Cards occupying at least 4 columns AND 4 rows of the home-screen grid are
 * large enough to show the queue list inline, replacing the player UI,
 * instead of opening the small dialog.
 */
internal fun inlineQueueAllowed(spanColumns: Int, spanRows: Int): Boolean =
    spanColumns >= 4 && spanRows >= 4

/** System music stream level as a whole percentage; 0 when no stream exists. */
internal fun volumePercent(current: Int, max: Int): Int =
    if (max <= 0) 0 else ((current.toFloat() / max) * 100f).roundToInt().coerceIn(0, 100)

/**
 * Which transport action a horizontal drag means: +1 next (swipe left),
 * -1 previous (swipe right), 0 while under the slop threshold.
 */
internal fun swipeTransportAction(deltaPx: Float, slopPx: Float): Int = when {
    deltaPx <= -slopPx -> 1
    deltaPx >= slopPx  -> -1
    else               -> 0
}

