package com.openlauncher.app.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.util.SystemBrightness
import kotlinx.coroutines.delay

/**
 * Screen brightness card: vertical layout with three preset buttons —
 * full sun (high) on top, half sun (medium) in the middle, moon (low) at
 * the bottom — and a readout under them. The preset percentages are set in
 * the card's context menu (long-press). When AUTO is enabled the view model
 * writes the level itself (sun position + cloud cover); the buttons stay
 * usable as a manual override until the next auto change.
 */
@Composable
fun BrightnessWidget(
    highPct: Int,
    mediumPct: Int,
    lowPct: Int,
    auto: Boolean,
    accent: Color,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentPct by remember { mutableIntStateOf(SystemBrightness.currentPct(context)) }
    var sysAuto    by remember { mutableStateOf(SystemBrightness.systemAutoEnabled(context)) }

    // Physical knobs and the view-model's auto loop change brightness behind our
    // back — poll so the readout and button highlight track the real level.
    LaunchedEffect(Unit) {
        while (true) {
            currentPct = SystemBrightness.currentPct(context)
            sysAuto    = SystemBrightness.systemAutoEnabled(context)
            delay(1000)
        }
    }

    fun applyPct(pct: Int) {
        if (!SystemBrightness.setPct(context, pct)) {
            // WRITE_SETTINGS not granted yet — send the user to the system grant page
            if (!SystemBrightness.canWrite(context)) {
                runCatching { context.startActivity(SystemBrightness.grantIntent(context)) }
            }
            return
        }
        currentPct = SystemBrightness.currentPct(context)
        sysAuto    = false
    }

    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val borderColor  = if (isDayMode) Color(0xFFE5E7EB) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)

    val readout = when {
        sysAuto        -> "SYS AUTO"
        currentPct < 0 -> "—"
        auto           -> "AUTO $currentPct%"
        else           -> "$currentPct%"
    }

    fun isCurrent(presetPct: Int) =
        currentPct >= 0 && kotlin.math.abs(currentPct - presetPct) <= 2

    // Top padding = 22.dp leaves room for the card header label (same as VOLUME)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PresetButton(
            icon    = Icons.Default.WbSunny,
            contentDescription = "High brightness",
            active  = isCurrent(highPct),
            accent  = accent,
            contentColor = contentColor,
            borderColor = borderColor,
            enabled = !isEditing, // edit mode must stay free for long-press dragging
            onClick = { applyPct(highPct) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        PresetButton(
            icon    = Icons.Default.BrightnessMedium,
            contentDescription = "Medium brightness",
            active  = isCurrent(mediumPct),
            accent  = accent,
            contentColor = contentColor,
            borderColor = borderColor,
            enabled = !isEditing,
            onClick = { applyPct(mediumPct) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        PresetButton(
            icon    = Icons.Default.Nightlight,
            contentDescription = "Low brightness",
            active  = isCurrent(lowPct),
            accent  = accent,
            contentColor = contentColor,
            borderColor = borderColor,
            enabled = !isEditing,
            onClick = { applyPct(lowPct) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        Text(
            text          = readout,
            color         = accent,
            fontSize      = 10.sp,
            fontFamily    = FontFamily.Monospace,
            fontWeight    = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            textAlign     = TextAlign.Center
        )
    }
}

@Composable
private fun PresetButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
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
            .border(1.dp, if (pressed || active) accent else borderColor, RoundedCornerShape(3.dp))
            .clip(RoundedCornerShape(3.dp))
            .background(if (pressed || active) accent.copy(alpha = 0.12f) else Color.Transparent)
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
            tint               = if (pressed || active) accent else contentColor,
            modifier           = Modifier.size(26.dp)
        )
    }
}
