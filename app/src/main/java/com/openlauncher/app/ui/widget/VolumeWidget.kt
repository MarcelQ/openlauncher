package com.openlauncher.app.ui.widget

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Media volume control card: vertical layout with the increase button on top
 * and the decrease button on the bottom, current level readout between them.
 * On dense grids (cells below [COMPACT_BELOW_H]) it flips to a horizontal
 * −/+ layout so the card stays usable inside a 1×1 cell of a 4×3 or 6×4 page.
 * Drives STREAM_MUSIC through AudioManager — no special permission needed,
 * which keeps it safe on old aftermarket head units (minSdk 21).
 */
private val COMPACT_BELOW_H = 110.dp

@Composable
fun VolumeWidget(
    accent: Color,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val maxVolume = remember(audioManager) {
        runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0 }
            .getOrDefault(0)
    }
    var volume by remember {
        mutableIntStateOf(runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0 }.getOrDefault(0))
    }

    fun adjust(direction: Int) {
        val am = audioManager ?: return
        runCatching {
            // flags = 0: no system volume panel — the card itself is the feedback
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        volume = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(volume)
    }

    // Head units change volume with physical knobs/keys too — poll so the
    // readout tracks changes that don't come from this widget's buttons.
    LaunchedEffect(Unit) {
        while (true) {
            audioManager?.let { am ->
                volume = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(volume)
            }
            delay(500)
        }
    }

    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val borderColor  = if (isDayMode) Color(0xFFE5E7EB) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Capture the constraints locally: the scope receiver is not available
        // inside the Row/Column content lambdas below.
        val boxH = maxHeight
        val compact = boxH < COMPACT_BELOW_H
        // 22.dp leaves room for the card header label in the full layout; compact
        // cells at/above 90.dp still show it (HomeScreen's threshold), below that
        // the label is hidden and padding can shrink.
        val topPad  = when {
            !compact       -> 22.dp
            boxH >= 90.dp  -> 18.dp
            else           -> 6.dp
        }
        val iconSize = when {
            !compact            -> 28.dp
            boxH >= 90.dp       -> 24.dp
            else                -> 20.dp
        }

        if (!compact) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, end = 10.dp, top = topPad, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                VolumeButton(
                    icon    = Icons.Default.VolumeUp,
                    contentDescription = "Volume up",
                    accent  = accent,
                    contentColor = contentColor,
                    borderColor = borderColor,
                    iconSize = iconSize,
                    enabled = !isEditing, // edit mode must stay free for long-press dragging
                    onClick = { adjust(AudioManager.ADJUST_RAISE) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Text(
                    text          = "$volume / $maxVolume",
                    color         = accent,
                    fontSize      = 11.sp,
                    fontFamily    = FontFamily.Monospace,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )

                VolumeButton(
                    icon    = Icons.Default.VolumeDown,
                    contentDescription = "Volume down",
                    accent  = accent,
                    contentColor = contentColor,
                    borderColor = borderColor,
                    iconSize = iconSize,
                    enabled = !isEditing,
                    onClick = { adjust(AudioManager.ADJUST_LOWER) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        } else {
            // Compact: − and + side by side so they fit a small square cell;
            // level readout only when there's vertical room for it
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 8.dp, end = 8.dp, top = topPad, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VolumeButton(
                    icon    = Icons.Default.VolumeDown,
                    contentDescription = "Volume down",
                    accent  = accent,
                    contentColor = contentColor,
                    borderColor = borderColor,
                    iconSize = iconSize,
                    enabled = !isEditing,
                    onClick = { adjust(AudioManager.ADJUST_LOWER) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                )
                if (boxH >= 64.dp) {
                    Text(
                        text          = "$volume",
                        color         = accent,
                        fontSize      = if (boxH >= 90.dp) 11.sp else 9.sp,
                        fontFamily    = FontFamily.Monospace,
                        fontWeight    = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        textAlign     = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier      = Modifier.width(if (boxH >= 90.dp) 34.dp else 26.dp)
                    )
                }
                VolumeButton(
                    icon    = Icons.Default.VolumeUp,
                    contentDescription = "Volume up",
                    accent  = accent,
                    contentColor = contentColor,
                    borderColor = borderColor,
                    iconSize = iconSize,
                    enabled = !isEditing,
                    onClick = { adjust(AudioManager.ADJUST_RAISE) },
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
private fun VolumeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    accent: Color,
    contentColor: Color,
    borderColor: Color,
    iconSize: androidx.compose.ui.unit.Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .border(1.dp, if (pressed) accent else borderColor, RoundedCornerShape(3.dp))
            .clip(RoundedCornerShape(3.dp))
            .background(if (pressed) accent.copy(alpha = 0.12f) else Color.Transparent)
            .then(
                if (enabled)
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication        = null,
                        onClick           = onClick
                    )
                else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = contentDescription,
            tint               = if (pressed) accent else contentColor,
            modifier           = Modifier.size(iconSize)
        )
    }
}
