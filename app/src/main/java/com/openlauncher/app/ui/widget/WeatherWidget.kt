package com.openlauncher.app.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.WeatherState

/**
 * Weather card. The optional info lines (today's high/low, real feel, wind +
 * gusts) are each gated by a settings flag, and every line additionally
 * disappears when the chosen provider cannot supply the value — met.no has no
 * gust data, for example, so gustKmh stays null end-to-end.
 *
 * Tapping the card (handled by HomeScreen, not here) opens the forecast dialog.
 */
@Composable
fun WeatherWidget(
    state: WeatherState?,
    accent: Color,
    metric: Boolean,
    showHighLow: Boolean = true,
    showRealFeel: Boolean = true,
    showWind: Boolean = true,
    isDayMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val contentColor = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val subColor     = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)

    Box(modifier = modifier) {
        if (state != null) {
            // Slightly smaller icon/temperature than before the info lines were
            // added, so a 1x1 card in a 2-row landscape grid still fits everything.
            Column(
                modifier            = Modifier.fillMaxSize().padding(start = 14.dp, end = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.Bottom,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text     = state.conditionIcon,
                    fontSize = 30.sp
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text       = state.temperatureDisplay(metric),
                    color      = contentColor,
                    fontSize   = 28.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 1.sp
                )
                Text(
                    text          = state.conditionLabel.uppercase(),
                    color         = subColor,
                    fontSize      = 9.sp,
                    letterSpacing = 1.sp
                )

                // ── optional detail lines (flag on AND provider has the data) ──
                val hi = state.tempMaxC
                val lo = state.tempMinC
                if (showHighLow && hi != null && lo != null) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text          = "H ${state.tempFahrenheitOrCelsius(hi, metric)}   L ${state.tempFahrenheitOrCelsius(lo, metric)}",
                        color         = subColor,
                        fontSize      = 8.sp,
                        letterSpacing = 1.sp
                    )
                }
                val feels = state.apparentTempC
                if (showRealFeel && feels != null) {
                    Text(
                        text          = "FEELS ${state.tempFahrenheitOrCelsius(feels, metric)}",
                        color         = subColor,
                        fontSize      = 8.sp,
                        letterSpacing = 1.sp
                    )
                }
                if (showWind) {
                    val conv  = if (metric) 1.0 else 0.621371
                    val unit  = if (metric) "KM/H" else "MPH"
                    val wind  = "WIND ${Math.round(state.windspeedKmh * conv)} $unit"
                    val gust  = state.gustKmh?.let { "  GUST ${Math.round(it * conv)}" } ?: ""
                    Text(
                        text          = "$wind$gust",
                        color         = subColor,
                        fontSize      = 8.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}
