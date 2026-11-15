package com.openlauncher.app.model

/**
 * Current conditions plus the short forecast used by the weather card and the
 * details dialog. Filled by either backend (Open-Meteo or met.no); fields the
 * chosen provider cannot supply are null, so every consumer must tolerate that.
 *
 * Plain data + defaults: the whole object is persisted as JSON in DataStore
 * (SettingsRepository WEATHER_CACHE) so the card still shows the last reading
 * when the network is gone at boot.
 */
data class WeatherState(
    val temperatureCelsius: Double,
    val weatherCode: Int,
    val windspeedKmh: Double,
    val isDay: Boolean,
    /** "Real feel" / apparent temperature; null when the provider lacks it. */
    val apparentTempC: Double? = null,
    /** Wind gusts; null when the provider lacks it (met.no has no gust data). */
    val gustKmh: Double? = null,
    /** Today's max/min; null when the provider returns no daily block. */
    val tempMaxC: Double? = null,
    val tempMinC: Double? = null,
    /** Wall-clock ms when this snapshot was fetched; 0 for legacy caches. */
    val fetchedAtMs: Long = 0L,
    /** Hour-by-hour forecast, oldest first (already filtered to "now or later"). */
    val hourly: List<HourForecast> = emptyList(),
    /** Day-by-day forecast, today first. */
    val daily: List<DayForecast> = emptyList()
) {
    // roundToInt, not toInt — truncation displayed 20.9° as 20°
    fun temperatureDisplay(metric: Boolean): String =
        if (metric) "${Math.round(temperatureCelsius)}°C"
        else "${Math.round(celsiusToFahrenheit(temperatureCelsius))}°F"

    fun tempFahrenheitOrCelsius(c: Double, metric: Boolean): String =
        if (metric) "${Math.round(c)}°"
        else "${Math.round(celsiusToFahrenheit(c))}°"

    val conditionLabel: String get() = wmoCodeToLabel(weatherCode)
    val conditionIcon: String get() = wmoCodeToEmoji(weatherCode, isDay)
}

/** One hour of forecast. epochMs is the start of the hour (UTC epoch). */
data class HourForecast(
    val epochMs: Long,
    val tempC: Double,
    val apparentC: Double? = null,
    /** Chance of precipitation in %; null when the provider doesn't offer it. */
    val precipProbPct: Int? = null,
    val weatherCode: Int,
    val isDay: Boolean,
    val gustKmh: Double? = null
) {
    val conditionLabel: String get() = wmoCodeToLabel(weatherCode)
    val conditionIcon: String get() = wmoCodeToEmoji(weatherCode, isDay)
}

/** One day of forecast. epochMs is local midnight at the location. */
data class DayForecast(
    val epochMs: Long,
    val tempMaxC: Double?,
    val tempMinC: Double?,
    val weatherCode: Int,
    val precipProbPct: Int? = null,
    val gustKmhMax: Double? = null
) {
    val conditionLabel: String get() = wmoCodeToLabel(weatherCode)
    val conditionIcon: String get() = wmoCodeToEmoji(weatherCode, false)
}

private fun celsiusToFahrenheit(c: Double) = c * 9.0 / 5.0 + 32.0

private fun wmoCodeToLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1, 2, 3 -> "Cloudy"
    45, 48 -> "Foggy"
    51, 53, 55 -> "Drizzle"
    56, 57 -> "Freezing drizzle"
    61, 63, 65 -> "Rain"
    66, 67 -> "Freezing rain"
    71, 73, 75 -> "Snow"
    80, 81, 82 -> "Showers"
    85, 86 -> "Snow showers"
    95 -> "Thunderstorm"
    96, 99 -> "Hail"
    else -> "Unknown"
}

private fun wmoCodeToEmoji(code: Int, isDay: Boolean): String = when (code) {
    0 -> if (isDay) "☀️" else "🌙"
    1, 2 -> if (isDay) "⛅" else "🌤"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
    71, 73, 75, 85, 86 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "🌡️"
}
