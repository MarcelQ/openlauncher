package com.openlauncher.app.ui.widget

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.tpms.PressureUnit
import com.openlauncher.app.tpms.TirePosition
import com.openlauncher.app.tpms.TireReading
import com.openlauncher.app.tpms.TpmsConnection
import com.openlauncher.app.tpms.TpmsSnapshot
import kotlinx.coroutines.delay

/**
 * Tyre pressure card: the four road wheels as FL/FR over RL/RR tiles, each showing pressure
 * in the configured unit plus temperature and sensor battery.
 *
 * Honesty rules baked into the rendering:
 *  - a wheel that has never reported shows "—", never a plausible-looking number;
 *  - sensor battery shows "—" because the receiver's 8-byte serial frame carries no battery
 *    field (the sensor reports battery only in its radio burst);
 *  - readings older than [TireReading.STALE_AFTER_MS] are dimmed and dated, since a parked
 *    car's last-known pressures must not look live.
 *
 * When the link is down the card offers a CONNECT action, so the receiver can be brought up
 * from the home screen without opening settings.
 *
 * The low-pressure warning is the [TpmsSnapshot.alarmingWheels] decision, not a local
 * comparison: with [alarmEnabled] off nothing turns red, and a sensor that has never reported
 * (0 kPa / no signal) can never make the card warn. A warned tile breathes slowly in the alarm
 * colour instead of switching on a hard red block, which is meant to be noticeable in a driver's
 * peripheral vision without competing with the road.
 */
@Composable
fun TpmsWidget(
    snapshot: TpmsSnapshot,
    unit: PressureUnit,
    lowPressureKpa: Float,
    accent: Color,
    isMetric: Boolean = true,
    isDayMode: Boolean = false,
    isEditing: Boolean = false,
    alarmEnabled: Boolean = true,
    onConnect: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Ticking clock: readings age in place while the car is parked and nothing re-emits.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val dimColor     = if (isDayMode) Color(0xFF9AA0A6) else contentColor.copy(alpha = 0.38f)
    val alarmColor   = if (isDayMode) Color(0xFFB3261E) else Color(0xFFFF5A4D)
    val warnColor    = if (isDayMode) Color(0xFF8A6D3B) else Color(0xFFFFC145)
    val tileBorder   = if (isDayMode) Color(0xFFE5E7EB) else contentColor.copy(alpha = 0.12f)

    // Single source of truth for "make this wheel obvious right now": honours the alarm switch,
    // the sensor's own leak flag, and the 0 kPa / no-signal guards.
    val alarmWheels = snapshot.alarmingWheels(lowPressureKpa, alarmEnabled, now)
    val alarming    = alarmWheels.isNotEmpty()
    // One pulse for the whole card, so all warned tiles breathe in step.
    val pulse       = if (alarming) alarmPulse() else 1f

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 10.dp, end = 10.dp, top = 22.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Font sizing follows the cell, so a 1×1 card stays legible and a 2×2 card fills up.
            val scale = fontScaleFor(maxWidth / 2f, maxHeight / 2f)

            // Spare-wheel sensors are rare but real: show a fifth tile only if one reported.
            val spare = snapshot.reading(TirePosition.SPARE)
            val wheels = TirePosition.FOUR_WHEELS.map { it to snapshot.reading(it) }

            Column(modifier = Modifier.fillMaxSize()) {
                for (rowIdx in 0 until 2) {
                    Row(
                        modifier    = Modifier.weight(1f).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (colIdx in 0 until 2) {
                            val (pos, reading) = wheels[rowIdx * 2 + colIdx]
                            TpmsWheelTile(
                                position    = pos,
                                reading     = reading,
                                now         = now,
                                unit        = unit,
                                isMetric    = isMetric,
                                isAlarm     = pos in alarmWheels,
                                pulse       = pulse,
                                scale       = scale,
                                contentColor = contentColor,
                                dimColor    = dimColor,
                                alarmColor  = alarmColor,
                                warnColor   = warnColor,
                                accent      = accent,
                                tileBorder  = tileBorder,
                                modifier    = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
                if (spare != null) {
                    Row(modifier = Modifier.height(22.dp * scale).fillMaxWidth()) {
                        TpmsWheelTile(
                            position    = TirePosition.SPARE,
                            reading     = spare,
                            now         = now,
                            unit        = unit,
                            isMetric    = isMetric,
                            isAlarm     = TirePosition.SPARE in alarmWheels,
                            pulse       = pulse,
                            scale       = scale * 0.7f,
                            contentColor = contentColor,
                            dimColor    = dimColor,
                            alarmColor  = alarmColor,
                            warnColor   = warnColor,
                            accent      = accent,
                            tileBorder  = tileBorder,
                            modifier    = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        TpmsFooter(
            snapshot      = snapshot,
            now           = now,
            alarmWheels   = alarmWheels,
            pulse         = pulse,
            accent        = accent,
            alarmColor    = alarmColor,
            warnColor     = warnColor,
            dimColor      = dimColor,
            contentColor  = contentColor,
            isEditing     = isEditing,
            onConnect     = onConnect
        )
    }
}

/**
 * Height/width driven type scale. Reference cell (a 1×1 card on a 1280×720 head unit) is
 * about 115×60 dp per wheel tile, which is scale 1.0; larger spans scale up, and the floor
 * keeps a cramped 1×1 cell readable on a small screen.
 */
private fun fontScaleFor(tileWidth: Dp, tileHeight: Dp): Float {
    val byHeight = (tileHeight.value / 60f).coerceIn(0.8f, 2.4f)
    val byWidth  = (tileWidth.value  / 115f).coerceIn(0.8f, 2.4f)
    return minOf(byHeight, byWidth)
}

/**
 * Slow breathing fraction (0.35 → 1.0, ~1.2 s per half-cycle) used while a wheel is alarming.
 *
 * A single fraction for the whole card keeps every warned tile in step, and driving only alpha
 * values (never size or position) means the layout can't jitter while the numbers update.
 */
@Composable
private fun alarmPulse(): Float {
    if (!animationsEnabled()) return 1f
    val transition = rememberInfiniteTransition(label = "tpms-alarm")
    return transition.animateFloat(
        initialValue     = 0.35f,
        targetValue      = 1f,
        animationSpec    = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label            = "tpms-alarm-pulse"
    ).value
}

/**
 * False when the user has turned animations off in the system settings
 * (window_animation_scale / transition_animation_scale / animator_duration_scale all at 0), which
 * is common on slow aftermarket head units. In that case the alarm must be a steady colour at
 * full strength rather than something that never moves — and a Compose infinite transition on a
 * weak unit is exactly the kind of thing those users disable animations to avoid.
 */
@Composable
private fun animationsEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val resolver = context.contentResolver
        fun scale(key: String) = try {
            Settings.Global.getFloat(resolver, key, 1f)
        } catch (_: Settings.SettingNotFoundException) {
            1f
        }
        scale(Settings.Global.ANIMATOR_DURATION_SCALE) > 0f &&
            scale(Settings.Global.TRANSITION_ANIMATION_SCALE) > 0f &&
            scale(Settings.Global.WINDOW_ANIMATION_SCALE) > 0f
    }
}

@Composable
private fun TpmsWheelTile(
    position: TirePosition,
    reading: TireReading?,
    now: Long,
    unit: PressureUnit,
    isMetric: Boolean,
    isAlarm: Boolean,
    /** 0..1 breathing fraction from [alarmPulse]; 1f when not alarming or animations are off. */
    pulse: Float,
    scale: Float,
    contentColor: Color,
    dimColor: Color,
    alarmColor: Color,
    warnColor: Color,
    accent: Color,
    tileBorder: Color,
    modifier: Modifier = Modifier
) {
    val stale = reading?.isStale(now) == true

    val pressureColor = when {
        reading == null || stale -> dimColor
        isAlarm                  -> alarmColor
        reading.noSignal         -> warnColor
        else                     -> accent
    }
    val subColor = if (stale || reading == null) dimColor else contentColor.copy(alpha = 0.6f)

    // Warning is a slow breath, not a hard red block: the alarm colour only rides in on the
    // alpha of the border/fill and on the opacity of the whole tile, so the card stays legible
    // and the eye catches the movement rather than the colour.
    val tileAlpha = if (isAlarm) 0.45f + 0.55f * pulse else 1f

    Column(
        modifier = modifier
            .border(
                1.dp,
                if (isAlarm) alarmColor.copy(alpha = 0.25f + 0.30f * pulse) else tileBorder,
                RoundedCornerShape(3.dp)
            )
            .clip(RoundedCornerShape(3.dp))
            .background(
                when {
                    isAlarm -> alarmColor.copy(alpha = 0.04f + 0.08f * pulse)
                    else    -> Color.Transparent
                }
            )
            .graphicsLayer { alpha = tileAlpha }
            .padding(horizontal = 6.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text          = position.shortLabel,
            fontSize      = (8.5f * scale).sp,
            color         = if (reading == null) dimColor else contentColor.copy(alpha = 0.55f),
            letterSpacing = 1.5.sp,
            fontWeight    = FontWeight.Bold
        )
        Text(
            text          = if (reading == null || (reading.noSignal && reading.pressureKpa <= 0f))
                                "—"
                            else unit.format(reading.pressureKpa),
            fontSize      = (18f * scale).sp,
            color         = pressureColor,
            fontFamily    = FontFamily.Monospace,
            fontWeight    = FontWeight.Bold,
            maxLines      = 1
        )
        // Pressure unit is printed once per tile so a bar/psi/kPa switch can't be ambiguous.
        Text(
            text          = unit.label,
            fontSize      = (7.5f * scale).sp,
            color         = subColor,
            fontFamily    = FontFamily.Monospace,
            maxLines      = 1
        )
        Text(
            text          = subLine(reading, isMetric, stale, now),
            fontSize      = (8f * scale).sp,
            color         = subColor,
            fontFamily    = FontFamily.Monospace,
            maxLines      = 1
        )
    }
}

private fun subLine(
    reading: TireReading?,
    isMetric: Boolean,
    stale: Boolean,
    now: Long
): String {
    if (reading == null) return "—"
    if (reading.noSignal) return "NO SIGNAL"
    val temp = if (isMetric)
        "${reading.temperatureCelsius.toInt()}°"
    else
        "${(reading.temperatureCelsius * 9f / 5f + 32f).toInt()}°F"
    // Battery is unknown over the USB link — the frame has no such field. Show the slot,
    // never a made-up percentage.
    val batt = reading.batteryPercent?.let { "B$it%" } ?: "B—"
    val staleMark = if (stale) " ${reading.ageSeconds(now)}s" else ""
    return "$temp $batt$staleMark"
}

@Composable
private fun TpmsFooter(
    snapshot: TpmsSnapshot,
    now: Long,
    alarmWheels: List<TirePosition>,
    pulse: Float,
    accent: Color,
    alarmColor: Color,
    warnColor: Color,
    dimColor: Color,
    contentColor: Color,
    isEditing: Boolean,
    onConnect: () -> Unit
) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val statusText: String
            val statusColor: Color
            when (snapshot.status) {
                TpmsConnection.CONNECTED -> {
                    val age = if (snapshot.lastFrameAt == 0L) null
                              else ((now - snapshot.lastFrameAt) / 1000L).coerceAtLeast(0L)
                    statusText = if (age == null) "USB LINK — WAITING FOR SENSORS"
                                 else if (age <= 20) "USB LINK  ${age}s AGO"
                                 else "USB LINK  ${age / 60 + 1}m AGO"
                    statusColor = if (age != null && age > 20) warnColor else contentColor.copy(alpha = 0.55f)
                }
                TpmsConnection.CONNECTING   -> { statusText = "CONNECTING…"; statusColor = warnColor }
                TpmsConnection.ERROR        -> {
                    statusText = (snapshot.detail ?: "LINK ERROR").uppercase()
                    statusColor = alarmColor
                }
                TpmsConnection.DISCONNECTED -> {
                    statusText = snapshot.detail?.uppercase() ?: "USB RECEIVER NOT CONNECTED"
                    statusColor = dimColor
                }
            }
            Text(
                text          = statusText,
                fontSize      = 8.5.sp,
                color         = statusColor,
                fontFamily    = FontFamily.Monospace,
                letterSpacing = 1.sp,
                maxLines      = 1
            )
            if (alarmWheels.isNotEmpty()) {
                Text(
                    text          = "CHECK: " + alarmWheels.joinToString(" ") { it.shortLabel },
                    fontSize      = 8.5.sp,
                    color         = alarmColor.copy(alpha = 0.45f + 0.55f * pulse),
                    fontFamily    = FontFamily.Monospace,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // Only offer CONNECT when the link is actually down, and never while rearranging —
        // edit mode must stay free for long-press dragging.
        if (!isEditing && snapshot.status != TpmsConnection.CONNECTED && snapshot.status != TpmsConnection.CONNECTING) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .clickable { onConnect() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector        = Icons.Default.Usb,
                    contentDescription = "Connect USB TPMS receiver",
                    tint               = accent,
                    modifier           = Modifier.size(12.dp)
                )
                Text(
                    text          = "CONNECT",
                    fontSize      = 8.5.sp,
                    color         = accent,
                    fontFamily    = FontFamily.Monospace,
                    fontWeight    = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
