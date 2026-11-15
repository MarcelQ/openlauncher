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
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

// ── api.met.no locationforecast 2.0 "complete" DTOs ─────────────────────────
// Chosen over the public forecast (yr.no) API because it needs no client ID,
// just an honest User-Agent (their usage requirement). Times are UTC ("...Z").
// The API documents its units (celsius, m/s) — wind is converted to km/h here.

data class MetNoResponse(
    @SerializedName("properties") val properties: MetNoProperties? = null
)

data class MetNoProperties(
    @SerializedName("timeseries") val timeseries: List<MetNoTimeseries>? = null
)

data class MetNoTimeseries(
    @SerializedName("time")  val time: String? = null,
    @SerializedName("data")  val data: MetNoData? = null
)

data class MetNoData(
    @SerializedName("instant")        val instant: MetNoBlock? = null,
    @SerializedName("next_1_hours")   val next1Hours: MetNoPeriod? = null,
    @SerializedName("next_6_hours")   val next6Hours: MetNoPeriod? = null,
    @SerializedName("next_12_hours")  val next12Hours: MetNoPeriod? = null
)

data class MetNoBlock(
    @SerializedName("details") val details: Map<String, Double>? = null
)

data class MetNoPeriod(
    @SerializedName("summary")  val summary: MetNoSummary? = null,
    @SerializedName("details")  val details: Map<String, Double>? = null
)

data class MetNoSummary(
    @SerializedName("symbol_code") val symbolCode: String? = null
)

interface MetNoApiService {
    @GET("weatherapi/locationforecast/2.0/complete")
    suspend fun getForecast(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double
    ): MetNoResponse
}

object MetNoApi {
    // met.no requires a uniquely identifying User-Agent and throttles generic ones.
    private const val USER_AGENT =
        "OpenLauncher/${com.openlauncher.app.BuildConfig.VERSION_NAME} " +
            "https://github.com/MarcelQ/openlauncher"

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .build()
            )
        }
        .build()

    val service: MetNoApiService = Retrofit.Builder()
        .baseUrl("https://api.met.no/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(MetNoApiService::class.java)
}

/**
 * Maps the met.no timeseries onto the app-wide [WeatherState]. Pure for JVM
 * tests. met.no has no gusts and no precipitation probability, so those stay
 * null; daily high/low are aggregated from the hourly instants per local date.
 */
fun MetNoResponse.toWeatherState(
    nowMs: Long = System.currentTimeMillis()
): WeatherState? {
    val series = properties?.timeseries?.filter { it.time != null && it.data != null }
        ?: return null
    if (series.isEmpty()) return null

    val utc = TimeZone.getTimeZone("GMT")
    val entryTimes = series.map { parseUtc(it.time!!, utc) }
    val first = series.first().data!!

    val symbol = first.next1Hours?.summary?.symbolCode
        ?: first.next6Hours?.summary?.symbolCode
        ?: first.next12Hours?.summary?.symbolCode
        ?: "clearsky_day"

    val curTemp = first.instant?.details?.get("air_temperature") ?: return null
    val windMs  = first.instant?.details?.get("wind_speed") ?: 0.0

    // ── Hourly (next 48 entries; series starts "now", all entries are future) ─
    val hourlyOut = ArrayList<HourForecast>()
    for ((i, ts) in series.withIndex()) {
        if (hourlyOut.size >= 48) break
        val d = ts.data!!
        val t = entryTimes[i] ?: continue
        val temp = d.instant?.details?.get("air_temperature") ?: continue
        val sym = d.next1Hours?.summary?.symbolCode
            ?: d.next6Hours?.summary?.symbolCode
            ?: d.next12Hours?.summary?.symbolCode
        hourlyOut += HourForecast(
            epochMs       = t,
            tempC         = temp,
            apparentC     = d.instant.details["apparent_air_temperature"],
            precipProbPct = null,                                   // not offered by met.no
            weatherCode   = metNoSymbolToWmo(sym),
            isDay         = sym != null && sym.endsWith("_day"),
            gustKmh       = null                                    // not offered by met.no
        )
    }

    // ── Daily: bucket hourly entries by the DEVICE's local date (the car is
    //    where the app runs), aggregate min/max, pick midday's 6-h symbol ─────
    val local = TimeZone.getDefault()
    val cal = Calendar.getInstance(local)
    data class Bucket(val dayStart: Long, val temps: MutableList<Double>, val entries: MutableList<Pair<Long, MetNoData>>)
    val buckets = LinkedHashMap<Long, Bucket>()
    for ((i, ts) in series.withIndex()) {
        val t = entryTimes[i] ?: continue
        if (t < nowMs - 60_000L) continue
        cal.timeInMillis = t
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val b = buckets.getOrPut(cal.timeInMillis) { Bucket(cal.timeInMillis, mutableListOf(), mutableListOf()) }
        ts.data!!.instant?.details?.get("air_temperature")?.let { b.temps += it }
        b.entries += t to ts.data
        if (buckets.size >= 5) break
    }
    val dailyOut = buckets.values.take(4).map { b ->
        // representative symbol: closest entry to 12:00 local
        val noonish = b.entries.minByOrNull { Math.abs(it.first - (b.dayStart + 12 * 3600_000L)) }?.second
        val sym = noonish?.next6Hours?.summary?.symbolCode
            ?: noonish?.next12Hours?.summary?.symbolCode
            ?: noonish?.next1Hours?.summary?.symbolCode
        DayForecast(
            epochMs       = b.dayStart,
            tempMaxC      = b.temps.maxOrNull(),
            tempMinC      = b.temps.minOrNull(),
            weatherCode   = metNoSymbolToWmo(sym),
            precipProbPct = null,
            gustKmhMax    = null
        )
    }

    return WeatherState(
        temperatureCelsius = curTemp,
        weatherCode        = metNoSymbolToWmo(symbol),
        windspeedKmh       = windMs * 3.6,
        isDay              = symbol.endsWith("_day"),
        apparentTempC      = first.instant.details["apparent_air_temperature"],
        gustKmh            = null,
        tempMaxC           = dailyOut.firstOrNull()?.tempMaxC,
        tempMinC           = dailyOut.firstOrNull()?.tempMinC,
        fetchedAtMs        = nowMs,
        hourly             = hourlyOut,
        daily              = dailyOut
    )
}

/**
 * met.no symbol codes ("<kind>_<day|night>", also "_polarnight") onto WMO codes
 * so the existing label/emoji maps work for both providers.
 */
fun metNoSymbolToWmo(symbol: String?): Int {
    if (symbol.isNullOrBlank()) return 0
    var s = symbol.removeSuffix("_polarnight")
    s = s.removeSuffix("_day").removeSuffix("_night").removeSuffix("_frost")
    if (s.contains("thunder")) return 95
    return when {
        s == "clearsky" || s == "fair" -> 0
        s == "partlycloudy" -> 2
        s == "cloudy" -> 3
        s == "fog" -> 48
        s.endsWith("drizzle") -> when {
            s.startsWith("heavy") -> 55
            s.startsWith("light") -> 51
            else -> 53
        }
        s.contains("freezing") || s.contains("ice") -> 66   // before plain rain: "freezingrain"
        s.endsWith("rainshowers") || (s.startsWith("rain") && s.endsWith("showers")) -> when {
            s.startsWith("heavy") -> 82
            s.startsWith("light") -> 80
            else -> 81
        }
        s.contains("rain") && !s.contains("sleet") -> when {
            s.startsWith("heavy") -> 65
            s.startsWith("light") -> 61
            else -> 63
        }
        s.contains("sleet") -> 71
        s.contains("snow") -> when {
            s.endsWith("snowshowers") -> 75
            s.startsWith("light") -> 71
            else -> 73
        }
        else -> 0
    }
}

private fun parseUtc(text: String, tz: TimeZone): Long? = runCatching {
    // "2026-11-15T07:00:00Z" — parse without the trailing Z under fixed GMT;
    // SimpleDateFormat("...XXX") needs API 24, this works on 21.
    val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
    f.timeZone = tz
    f.parse(text.removeSuffix("Z"))?.time
}.getOrNull()
