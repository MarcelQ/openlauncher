package com.openlauncher.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Pure JVM tests for the two weather backends' response → WeatherState mapping
 * (Open-Meteo and api.met.no) and the met.no symbol-code translation. DTOs are
 * built in code — no network, no Android types.
 */
class WeatherForecastParseTest {

    private lateinit var previousTz: TimeZone

    @Before
    fun fixDeviceTimeZone() {
        previousTz = TimeZone.getDefault()
        // The met.no daily bucketing groups by the DEVICE local date; make it
        // deterministic (South African standard time, which is what this app
        // is used in).
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+02:00"))
    }

    @After
    fun restoreTimeZone() = TimeZone.setDefault(previousTz)

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun utc(text: String): Long {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT)
        f.timeZone = TimeZone.getTimeZone("GMT")
        return f.parse(text.removeSuffix("Z"))!!.time
    }

    // ── Open-Meteo ────────────────────────────────────────────────────────────

    // nowMs = 06:15Z; with utc_offset_seconds=7200 that is 08:15 local.
    private val omNow = utc("2026-11-15T06:15:00Z")

    private fun omFixture(
        times: List<String>,
        temps: List<Double>,
        daily: OpenMeteoDaily? = null
    ) = OpenMeteoResponse(
        utcOffsetSeconds = 7200,
        current = OpenMeteoCurrent(
            time = "2026-11-15T08:15",
            temperature = 22.0,
            apparentTemperature = 24.5,
            windSpeed = 14.0,
            windGusts = 31.0,
            weatherCode = 2,
            isDay = 1
        ),
        hourly = OpenMeteoHourly(
            time = times,
            temperature = temps,
            apparentTemperature = temps.map { it + 2 },
            precipitationProbability = times.indices.map { 30 },
            weatherCode = times.indices.map { 3 },
            isDay = times.indices.map { 1 },
            windGusts = times.indices.map { 30.0 }
        ),
        daily = daily
    )

    @Test
    fun openMeteo_keepsCurrentHourAndDropsFullyPastOnes() {
        // local 07:00 hour is fully past (ends 08:00 < 08:15); the 08:00 hour
        // contains "now" and must survive
        val resp = omFixture(
            times = listOf("2026-11-15T06:00", "2026-11-15T07:00", "2026-11-15T08:00", "2026-11-15T09:00"),
            temps = listOf(1.0, 2.0, 3.0, 4.0)
        )
        val s = resp.toWeatherState(nowMs = omNow)!!
        assertEquals(listOf(3.0, 4.0), s.hourly.map { it.tempC })
        // 08:00 local at +02:00 == 06:00Z
        assertEquals(utc("2026-11-15T06:00:00Z"), s.hourly.first().epochMs)
    }

    @Test
    fun openMeteo_capsHourlyAt48Entries() {
        val times = ArrayList<String>()
        val temps = ArrayList<Double>()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+02:00"))
        cal.timeInMillis = omNow
        cal.set(Calendar.HOUR_OF_DAY, 8); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.ROOT)
        f.timeZone = cal.timeZone
        repeat(60) {
            times += f.format(cal.time)
            temps += 20.0 + it
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
        val s = omFixture(times, temps).toWeatherState(nowMs = omNow)!!
        assertEquals(48, s.hourly.size)
        assertEquals(20.0, s.hourly.first().tempC, 0.0)
        assertEquals(67.0, s.hourly.last().tempC, 0.0) // 20 + 47
    }

    @Test
    fun openMeteo_dailyExcludesPastDaysAndCapsAtFour() {
        val daily = OpenMeteoDaily(
            time = listOf("2026-11-14", "2026-11-15", "2026-11-16", "2026-11-17", "2026-11-18", "2026-11-19"),
            weatherCode = List(6) { 1 },
            temperatureMax = listOf(25.0, 26.0, 27.0, 28.0, 29.0, 30.0),
            temperatureMin = listOf(15.0, 16.0, 17.0, 18.0, 19.0, 20.0),
            precipitationProbabilityMax = List(6) { 10 },
            windGustsMax = List(6) { 40.0 }
        )
        val s = omFixture(listOf("2026-11-15T08:00"), listOf(3.0), daily).toWeatherState(nowMs = omNow)!!
        assertEquals(4, s.daily.size)
        // yesterday (11-14) excluded; first surviving day is today
        assertEquals(utc("2026-11-14T22:00:00Z"), s.daily.first().epochMs) // 11-15T00:00 local
        assertEquals(26.0, s.daily.first().tempMaxC!!, 0.0)
        assertEquals(16.0, s.daily.first().tempMinC!!, 0.0)
        // today's high/low surface onto the card state
        assertEquals(26.0, s.tempMaxC!!, 0.0)
        assertEquals(16.0, s.tempMinC!!, 0.0)
    }

    @Test
    fun openMeteo_currentBlockMissingOrNullTemp_returnsNull() {
        assertNull(OpenMeteoResponse(utcOffsetSeconds = 0).toWeatherState(nowMs = omNow))
        assertNull(
            OpenMeteoResponse(utcOffsetSeconds = 0, current = OpenMeteoCurrent(temperature = null))
                .toWeatherState(nowMs = omNow)
        )
    }

    @Test
    fun openMeteo_mapsCurrentFieldsAndFetchTime() {
        val s = omFixture(listOf("2026-11-15T08:00"), listOf(3.0)).toWeatherState(nowMs = omNow)!!
        assertEquals(22.0, s.temperatureCelsius, 0.0)
        assertEquals(24.5, s.apparentTempC!!, 0.0)
        assertEquals(14.0, s.windspeedKmh, 0.0)
        assertEquals(31.0, s.gustKmh!!, 0.0)
        assertEquals(2, s.weatherCode)
        assertTrue(s.isDay)
        assertEquals(omNow, s.fetchedAtMs)
        assertNull(s.tempMaxC) // no daily block in this fixture
        val h = s.hourly.first()
        assertEquals(5.0, h.apparentC!!, 0.0)   // temp + 2 from the fixture
        assertEquals(30, h.precipProbPct)
        assertEquals(30.0, h.gustKmh!!, 0.0)
    }

    // ── met.no ────────────────────────────────────────────────────────────────

    // nowMs = 06:30Z; device local (GMT+2) = 08:30 on 2026-11-15.
    private val mnNow = utc("2026-11-15T06:30:00Z")

    private fun mnEntry(time: String, temp: Double?, apparent: Double?, wind: Double?, symbol: String?) =
        MetNoTimeseries(
            time = time,
            data = MetNoData(
                instant = MetNoBlock(
                    details = listOfNotNull(
                        temp?.let { "air_temperature" to it },
                        apparent?.let { "apparent_air_temperature" to it },
                        wind?.let { "wind_speed" to it }
                    ).toMap()
                ),
                next1Hours = symbol?.let { MetNoPeriod(summary = MetNoSummary(it)) },
                next6Hours = symbol?.let { MetNoPeriod(summary = MetNoSummary(it)) }
            )
        )

    @Test
    fun metNo_mapsCurrentWindConversionAndHourly() {
        val resp = MetNoResponse(
            properties = MetNoProperties(
                timeseries = listOf(
                    mnEntry("2026-11-15T07:00:00Z", 10.0, 9.0, 5.0, "lightrainshowers_day"),
                    mnEntry("2026-11-15T08:00:00Z", 20.5, 19.0, 5.0, "clearsky_day"),
                    mnEntry("2026-11-15T09:00:00Z", 8.0, 7.0, 5.0, "cloudy_day")
                )
            )
        )
        val s = resp.toWeatherState(nowMs = mnNow)!!
        assertEquals(10.0, s.temperatureCelsius, 0.0)
        assertEquals(9.0, s.apparentTempC!!, 0.0)
        assertEquals(18.0, s.windspeedKmh, 0.0)          // 5 m/s × 3.6
        assertNull(s.gustKmh)                            // met.no has no gusts
        assertEquals(80, s.weatherCode)                  // lightrainshowers → WMO 80
        assertTrue(s.isDay)
        assertEquals(mnNow, s.fetchedAtMs)

        assertEquals(3, s.hourly.size)
        assertEquals(utc("2026-11-15T07:00:00Z"), s.hourly.first().epochMs)
        assertEquals(80, s.hourly.first().weatherCode)      // lightrainshowers → WMO 80
        assertNull(s.hourly.first().precipProbPct)          // not offered by met.no
        assertNull(s.hourly.first().gustKmh)
        assertEquals(0, s.hourly[1].weatherCode)            // clearsky → WMO 0
        assertEquals(3, s.hourly[2].weatherCode)            // cloudy → WMO 3
    }

    @Test
    fun metNo_dailyBucketsByDeviceLocalDateAndAggregatesMinMax() {
        val resp = MetNoResponse(
            properties = MetNoProperties(
                timeseries = listOf(
                    // local 09:00-16:00 on 2026-11-15 (GMT+2)
                    mnEntry("2026-11-15T07:00:00Z", 10.0, null, 1.0, "cloudy_day"),
                    mnEntry("2026-11-15T08:00:00Z", 20.5, null, 1.0, "partlycloudy_day"),
                    mnEntry("2026-11-15T09:00:00Z", 8.0, null, 1.0, "cloudy_day"),
                    // 23:00Z = 01:00 local on 2026-11-16 → second bucket
                    mnEntry("2026-11-15T23:00:00Z", 5.0, null, 1.0, "clearsky_night")
                )
            )
        )
        val s = resp.toWeatherState(nowMs = mnNow)!!
        assertEquals(2, s.daily.size)
        val d0 = s.daily[0]
        assertEquals(utc("2026-11-14T22:00:00Z"), d0.epochMs) // 11-15T00:00 local
        assertEquals(20.5, d0.tempMaxC!!, 0.0)
        assertEquals(8.0, d0.tempMinC!!, 0.0)
        val d1 = s.daily[1]
        assertEquals(utc("2026-11-15T22:00:00Z"), d1.epochMs) // 11-16T00:00 local
        assertEquals(5.0, d1.tempMaxC!!, 0.0)
        assertEquals(5.0, d1.tempMinC!!, 0.0)
        // card-level today high/low come from the first bucket
        assertEquals(20.5, s.tempMaxC!!, 0.0)
        assertEquals(8.0, s.tempMinC!!, 0.0)
    }

    @Test
    fun metNo_emptyOrMissingSeries_returnsNull() {
        assertNull(MetNoResponse().toWeatherState(nowMs = mnNow))
        assertNull(MetNoResponse(properties = MetNoProperties(emptyList())).toWeatherState(nowMs = mnNow))
    }

    // ── met.no symbol code → WMO ──────────────────────────────────────────────

    @Test
    fun metNoSymbolMapper_matrix() {
        assertEquals(0, metNoSymbolToWmo("clearsky_day"))
        assertEquals(0, metNoSymbolToWmo("clearsky_night"))
        assertEquals(0, metNoSymbolToWmo("fair_day"))
        assertEquals(2, metNoSymbolToWmo("partlycloudy_day"))
        assertEquals(3, metNoSymbolToWmo("cloudy_day"))
        assertEquals(3, metNoSymbolToWmo("cloudy_frost"))
        assertEquals(48, metNoSymbolToWmo("fog_day"))
        assertEquals(51, metNoSymbolToWmo("lightdrizzle_day"))
        assertEquals(53, metNoSymbolToWmo("drizzle_day"))
        assertEquals(55, metNoSymbolToWmo("heavydrizzle_night"))
        assertEquals(61, metNoSymbolToWmo("lightrain_day"))
        assertEquals(63, metNoSymbolToWmo("rain_day"))
        assertEquals(65, metNoSymbolToWmo("heavyrain_day"))
        assertEquals(80, metNoSymbolToWmo("lightrainshowers_day"))
        assertEquals(81, metNoSymbolToWmo("rainshowers_day"))
        assertEquals(82, metNoSymbolToWmo("heavyrainshowers_polarnight"))
        assertEquals(71, metNoSymbolToWmo("lightsleet_day"))
        assertEquals(71, metNoSymbolToWmo("lightsnow_day"))
        assertEquals(73, metNoSymbolToWmo("snow_day"))
        assertEquals(75, metNoSymbolToWmo("heavysnowshowers_day"))
        assertEquals(95, metNoSymbolToWmo("thunder_day"))
        assertEquals(95, metNoSymbolToWmo("rainandthunder_day"))
        assertEquals(66, metNoSymbolToWmo("freezingrain_day"))
        // unrecognised / missing fall back to clear rather than crashing the card
        assertEquals(0, metNoSymbolToWmo(null))
        assertEquals(0, metNoSymbolToWmo(""))
        assertEquals(0, metNoSymbolToWmo("something_weird"))
        assertNotNull(metNoSymbolToWmo("polarpolarnight"))
    }
}
