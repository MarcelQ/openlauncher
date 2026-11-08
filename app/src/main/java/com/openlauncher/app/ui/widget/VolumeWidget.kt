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
 * Drives STREAM_MUSIC through AudioManager — no special permission needed,
 * which keeps it safe on old aftermarket head units (minSdk 21).
 */
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

    // Top padding = 22.dp leaves room for the card header label (same as SOUNDBOARD)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VolumeButton(
            icon    = Icons.Default.VolumeUp,
            contentDescription = "Volume up",
            accent  = accent,
            contentColor = contentColor,
            borderColor = borderColor,
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
            enabled = !isEditing,
            onClick = { adjust(AudioManager.ADJUST_LOWER) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
    }
}

@Composable
private fun VolumeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    accent: Color,
    contentColor: Color,
    borderColor: Color,
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
            modifier           = Modifier.size(28.dp)
        )
    }
}
