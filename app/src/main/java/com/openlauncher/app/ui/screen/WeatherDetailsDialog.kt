package com.openlauncher.app.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openlauncher.app.model.DayForecast
import com.openlauncher.app.model.HourForecast
import com.openlauncher.app.model.WeatherState
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Forecast dialog opened by tapping the weather card: page 1 is the
 * hour-by-hour outlook, page 2 the next days — swipeable (also switchable via
 * the two tabs). Tolerates nulls: providers like met.no do not deliver gusts
 * or precipitation probability, so those cells simply omit them.
 *
 * No java.time anywhere (minSdk 21): dates go through SimpleDateFormat/Calendar.
 */
@Composable
fun WeatherDetailsDialog(
    state: WeatherState,
    metric: Boolean,
    accent: Color,
    isDayMode: Boolean,
    onDismiss: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    val scope      = rememberCoroutineScope()

    val dialogBg   = if (isDayMode) Color(0xFFFFFFFF) else MaterialTheme.colorScheme.background
    val dialogText = if (isDayMode) Color(0xFF111111) else MaterialTheme.colorScheme.onBackground
    val subColor   = if (isDayMode) Color(0xFF888888) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
    val faintColor = if (isDayMode) Color(0xFFADB5BD) else Color(0xFF555555)

    val updated = if (state.fetchedAtMs > 0L)
        "  ·  UPDATED " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.fetchedAtMs))
    else ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text          = "FORECAST$updated",
                color         = dialogText,
                fontSize      = 11.sp,
                letterSpacing = 2.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // ── page tabs ─────────────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ForecastTab("HOURLY", selected = pagerState.currentPage == 0, accent = accent, subColor = subColor) {
                        scope.launch { pagerState.animateScrollToPage(0) }
                    }
                    ForecastTab("3-DAY", selected = pagerState.currentPage == 1, accent = accent, subColor = subColor) {
                        scope.launch { pagerState.animateScrollToPage(1) }
                    }
                }
                Spacer(Modifier.height(10.dp))

                HorizontalPager(
                    state    = pagerState,
                    modifier = Modifier.fillMaxWidth().height(250.dp)
                ) { page ->
                    when (page) {
                        0 -> HourlyForecastList(
                            state     = state.hourly,
                            metric    = metric,
                            dialogText = dialogText,
                            subColor  = subColor,
                            faintColor = faintColor
                        )
                        else -> DailyForecastList(
                            daily     = state.daily,
                            metric    = metric,
                            dialogText = dialogText,
                            subColor  = subColor,
                            faintColor = faintColor
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    "CLOSE",
                    color         = if (isDayMode) Color(0xFF6C757D) else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                    fontSize      = 11.sp,
                    letterSpacing = 1.sp
                )
            }
        },
        containerColor    = dialogBg,
        titleContentColor = dialogText,
        textContentColor  = dialogText
    )
}

@Composable
private fun ForecastTab(
    label: String,
    selected: Boolean,
    accent: Color,
    subColor: Color,
    onClick: () -> Unit
) {
    Text(
        text          = label,
        color         = if (selected) accent else subColor,
        fontSize      = 10.sp,
        fontWeight    = if (selected) FontWeight.Bold else FontWeight.Normal,
        letterSpacing = 2.sp,
        modifier      = Modifier.clickable(onClick = onClick)
    )
}

// ── hourly page: horizontal strip, scrollable past the visible hours ─────────

@Composable
private fun HourlyForecastList(
    state: List<HourForecast>,
    metric: Boolean,
    dialogText: Color,
    subColor: Color,
    faintColor: Color
) {
    if (state.isEmpty()) {
        Text("NO HOURLY DATA", color = faintColor, fontSize = 9.sp, letterSpacing = 1.sp)
        return
    }
    val hourFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    // A provider whose hourly feed starts "now" is already filtered; mark the
    // first row so the user sees where "now" is.
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(state) { hour ->
            Column(
                modifier            = Modifier.width(62.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text          = hourFmt.format(Date(hour.epochMs)),
                    color         = subColor,
                    fontSize      = 8.sp,
                    letterSpacing = 1.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(text = hour.conditionIcon, fontSize = 18.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text          = "${Math.round(if (metric) hour.tempC else hour.tempC * 9.0 / 5.0 + 32.0)}°",
                    color         = dialogText,
                    fontSize      = 11.sp
                )
                // met.no has no precipitation probability — the slot hides
                hour.precipProbPct?.let { pct ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text     = "$pct%",
                        color    = if (pct >= 50) Color(0xFF4DABF7) else faintColor,
                        fontSize = 7.sp
                    )
                }
            }
        }
    }
}

// ── 3-day page: one row per day ───────────────────────────────────────────────

@Composable
private fun DailyForecastList(
    daily: List<DayForecast>,
    metric: Boolean,
    dialogText: Color,
    subColor: Color,
    faintColor: Color
) {
    if (daily.isEmpty()) {
        Text("NO DAILY DATA", color = faintColor, fontSize = 9.sp, letterSpacing = 1.sp)
        return
    }
    val todayLabelFmt = SimpleDateFormat("EEEE", Locale.getDefault())
    val todayMidnight = rememberTodayMidnightMs()

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(daily) { day ->
            val dayIndex = ((day.epochMs - todayMidnight) / 86_400_000L).toInt()
            val name = when {
                dayIndex <= 0 -> "TODAY"
                dayIndex == 1 -> "TOMORROW"
                else          -> todayLabelFmt.format(Date(day.epochMs)).uppercase()
            }
            Row(
                modifier            = Modifier.fillMaxWidth(),
                verticalAlignment   = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text          = name,
                    color         = dialogText,
                    fontSize      = 9.sp,
                    letterSpacing = 1.sp,
                    modifier      = Modifier.width(86.dp)
                )
                Text(text = day.conditionIcon, fontSize = 16.sp)
                Text(
                    text          = day.conditionLabel.uppercase(),
                    color         = subColor,
                    fontSize      = 8.sp,
                    letterSpacing = 1.sp,
                    modifier      = Modifier.weight(1f)
                )
                // providers without rain probability (met.no) hide this cell
                day.precipProbPct?.let { pct ->
                    Text(
                        text     = "$pct%",
                        color    = if (pct >= 50) Color(0xFF4DABF7) else faintColor,
                        fontSize = 8.sp
                    )
                }
                val hi = day.tempMaxC
                val lo = day.tempMinC
                if (hi != null && lo != null) {
                    Text(
                        text          = "${Math.round(if (metric) hi else hi * 9.0 / 5.0 + 32.0)}° / ${Math.round(if (metric) lo else lo * 9.0 / 5.0 + 32.0)}°",
                        color         = dialogText,
                        fontSize      = 10.sp,
                        modifier      = Modifier.width(72.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberTodayMidnightMs(): Long = androidx.compose.runtime.remember {
    val cal = Calendar.getInstance()
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.timeInMillis
}
