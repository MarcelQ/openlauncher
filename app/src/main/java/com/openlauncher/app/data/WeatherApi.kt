package com.openlauncher.app.data

import com.google.gson.annotations.SerializedName
import com.openlauncher.app.model.DayForecast
import com.openlauncher.app.model.HourForecast
import com.openlauncher.app.model.WeatherState
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// ── Open-Meteo response DTOs ─────────────────────────────────────────────────
// Requested with timezone=auto, so every "time" below is LOCAL at the queried
// position; utc_offset_seconds lets us turn them into epoch ms without java.time
// (minSdk 21 has no desugaring configured).

data class OpenMeteoResponse(
    @SerializedName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    @SerializedName("current")            val current: OpenMeteoCurrent? = null,
    @SerializedName("hourly")             val hourly: OpenMeteoHourly? = null,
    @SerializedName("daily")              val daily: OpenMeteoDaily? = null
)

data class OpenMeteoCurrent(
    @SerializedName("time")                  val time: String? = null,
    @SerializedName("temperature_2m")        val temperature: Double? = null,
    @SerializedName("apparent_temperature")  val apparentTemperature: Double? = null,
    @SerializedName("wind_speed_10m")        val windSpeed: Double? = null,
    @SerializedName("wind_gusts_10m")        val windGusts: Double? = null,
    @SerializedName("weather_code")          val weatherCode: Int? = null,
    @SerializedName("is_day")                val isDay: Int? = null
)

data class OpenMeteoHourly(
    @SerializedName("time")                       val time: List<String>? = null,
    @SerializedName("temperature_2m")             val temperature: List<Double>? = null,
    @SerializedName("apparent_temperature")       val apparentTemperature: List<Double>? = null,
    @SerializedName("precipitation_probability")  val precipitationProbability: List<Int>? = null,
    @SerializedName("weather_code")               val weatherCode: List<Int>? = null,
    @SerializedName("is_day")                     val isDay: List<Int>? = null,
    @SerializedName("wind_gusts_10m")             val windGusts: List<Double>? = null
)

data class OpenMeteoDaily(
    @SerializedName("time")                            val time: List<String>? = null,
    @SerializedName("weather_code")                    val weatherCode: List<Int>? = null,
    @SerializedName("temperature_2m_max")              val temperatureMax: List<Double>? = null,
    @SerializedName("temperature_2m_min")              val temperatureMin: List<Double>? = null,
    @SerializedName("precipitation_probability_max")   val precipitationProbabilityMax: List<Int>? = null,
    @SerializedName("wind_gusts_10m_max")              val windGustsMax: List<Double>? = null
)

interface WeatherApiService {
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude")   latitude: Double,
        @Query("longitude")  longitude: Double,
        @Query("current")    current: String =
            "temperature_2m,apparent_temperature,wind_speed_10m,wind_gusts_10m,weather_code,is_day",
        @Query("hourly")     hourly: String =
            "temperature_2m,apparent_temperature,precipitation_probability,weather_code,is_day,wind_gusts_10m",
        @Query("daily")      daily: String =
            "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,wind_gusts_10m_max",
        @Query("forecast_days")           forecastDays: Int = 4,
        @Query("timezone")                timezone: String = "auto",
        @Query("temperature_unit")        temperatureUnit: String = "celsius",
        @Query("windspeed_unit")          windspeedUnit: String = "kmh"
    ): OpenMeteoResponse
}

object WeatherApi {
    private val client = OkHttpClient.Builder().build()

    val service: WeatherApiService = Retrofit.Builder()
        .baseUrl("https://api.open-meteo.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WeatherApiService::class.java)
}

/**
 * Maps the raw Open-Meteo payload onto the app-wide [WeatherState]. Pure (no
 * Android types) so it is JVM-unit-testable; returns null only when there is
 * no usable "current" block. The hourly list is trimmed to the next 48 hours
 * from now, the daily list to 3 days after today.
 */
fun OpenMeteoResponse.toWeatherState(
    nowMs: Long = System.currentTimeMillis()
): WeatherState? {
    val cur = current ?: return null
    val temp = cur.temperature ?: return null
    val tz = TimeZone.getTimeZone("GMT" + offsetString(utcOffsetSeconds))

    val code = cur.weatherCode ?: 0
    val isDay = cur.isDay == 1

    // ── Hourly: keep the next 48 h at (or after) the current hour ────────────
    val hourlyOut = ArrayList<HourForecast>()
    hourly?.let { h ->
        val times = h.time ?: return@let
        for (i in times.indices) {
            val t = parseLocal(times[i], "yyyy-MM-dd'T'HH:mm", tz) ?: continue
            // hourly times are hour-START stamps: keep the hour that contains
            // "now" (its end is still ahead of us), drop only fully past hours
            if (t + 3_600_000L <= nowMs) continue
            val ht = h.temperature?.getOrNull(i) ?: continue
            hourlyOut += HourForecast(
                epochMs       = t,
                tempC         = ht,
                apparentC     = h.apparentTemperature?.getOrNull(i),
                precipProbPct = h.precipitationProbability?.getOrNull(i),
                weatherCode   = h.weatherCode?.getOrNull(i) ?: 0,
                isDay         = h.isDay?.getOrNull(i) == 1,
                gustKmh       = h.windGusts?.getOrNull(i)
            )
            if (hourlyOut.size >= 48) break
        }
    }

    // ── Daily: today + 3 days ─────────────────────────────────────────────────
    val dailyOut = ArrayList<DayForecast>()
    daily?.let { d ->
        val times = d.time ?: return@let
        for (i in times.indices) {
            val dayStart = parseLocal(times[i], "yyyy-MM-dd", tz) ?: continue
            if (dayStart < midnight(nowMs, tz)) continue
            dailyOut += DayForecast(
                epochMs       = dayStart,
                tempMaxC      = d.temperatureMax?.getOrNull(i),
                tempMinC      = d.temperatureMin?.getOrNull(i),
                weatherCode   = d.weatherCode?.getOrNull(i) ?: 0,
                precipProbPct = d.precipitationProbabilityMax?.getOrNull(i),
                gustKmhMax    = d.windGustsMax?.getOrNull(i)
            )
            if (dailyOut.size >= 4) break
        }
    }

    val today = dailyOut.firstOrNull()
    return WeatherState(
        temperatureCelsius = temp,
        weatherCode        = code,
        windspeedKmh       = cur.windSpeed ?: 0.0,
        isDay              = isDay,
        apparentTempC      = cur.apparentTemperature,
        gustKmh            = cur.windGusts,
        tempMaxC           = today?.tempMaxC,
        tempMinC           = today?.tempMinC,
        fetchedAtMs        = nowMs,
        hourly             = hourlyOut,
        daily              = dailyOut
    )
}

private fun offsetString(seconds: Int): String {
    val sign = if (seconds < 0) "-" else "+"
    val abs = Math.abs(seconds)
    return String.format(Locale.ROOT, "%s%02d:%02d", sign, abs / 3600, (abs % 3600) / 60)
}

private fun parseLocal(text: String, pattern: String, tz: TimeZone): Long? = runCatching {
    val f = SimpleDateFormat(pattern, Locale.ROOT)
    f.timeZone = tz
    f.parse(text)?.time
}.getOrNull()

/** Start of the local day containing [ms] in [tz]. */
private fun midnight(ms: Long, tz: TimeZone): Long {
    val f = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
    f.timeZone = tz
    return parseLocal(f.format(java.util.Date(ms)), "yyyy-MM-dd", tz) ?: ms
}
