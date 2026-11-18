package com.openlauncher.app.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire contract of the tyre LAN web server (tpms\TpmsWebServer.kt serves it).
 *
 * The socket itself is device-verified only; everything a phone browser can *see* is decided here,
 * so these tests pin the three things that would otherwise silently differ from the dash card:
 *  - the same warn decision (TpmsSnapshot.alarmingWheels) drives the page's red tiles,
 *  - a wheel that never reported, or went stale, arrives as null/stale — never as a plausible 0,
 *  - numbers are always dot-decimal, because a comma breaks JSON.parse in the browser.
 *
 * Deterministic clock, same convention as TpmsAlarmLogicTest.
 */
class TpmsWebReportTest {

    private val now = 1_700_000_000_000L

    private fun reading(
        pos: TirePosition,
        kpa: Float,
        ageMs: Long = 2_000L,
        hasAlarm: Boolean = false,
        noSignal: Boolean = false
    ) = TireReading(
        position          = pos,
        pressureKpa       = kpa,
        temperatureCelsius = 20f,
        hasAlarm          = hasAlarm,
        noSignal          = noSignal,
        timestamp         = now - ageMs
    )

    private fun snapshot(vararg readings: TireReading, status: TpmsConnection = TpmsConnection.CONNECTED) =
        TpmsSnapshot(
            status      = status,
            readings    = readings.associateBy { it.position },
            lastFrameAt = if (status.isActive) now - 1_000L else 0L
        )

    // ── which address the phone is told to open ────────────────────────────────

    @Test
    fun hotspotInterfaceWinsOverClientWifi() {
        val host = TpmsWebReport.pickServerHost(
            listOf(
                TpmsWebReport.NetworkCandidate("lo", listOf("127.0.0.1")),
                TpmsWebReport.NetworkCandidate("wlan0", listOf("192.168.1.50")),
                TpmsWebReport.NetworkCandidate("ap0", listOf("192.168.43.1"))
            )
        )
        assertEquals("192.168.43.1", host)
    }

    @Test
    fun apNamingVariantsAcrossRoms() {
        for (name in listOf("ap0", "swlan0", "softap", "wifi-ap")) {
            val host = TpmsWebReport.pickServerHost(
                listOf(
                    TpmsWebReport.NetworkCandidate("wlan0", listOf("192.168.1.50")),
                    TpmsWebReport.NetworkCandidate(name, listOf("192.168.43.1"))
                )
            )
            assertEquals("interface $name should be chosen", "192.168.43.1", host)
        }
    }

    @Test
    fun linkLocalAndLoopbackAreNeverOffered() {
        // 169.254.x has no gateway: a phone could never route to it, so the card must report
        // "no address" instead of advertising a URL that can only ever fail.
        assertNull(TpmsWebReport.pickServerHost(
            listOf(
                TpmsWebReport.NetworkCandidate("lo", listOf("127.0.0.1")),
                TpmsWebReport.NetworkCandidate("ap0", listOf("169.254.88.7"))
            )
        ))
    }

    @Test
    fun deterministicFallbackWhenNoApInterfaceExists() {
        val candidates = listOf(
            TpmsWebReport.NetworkCandidate("rmnet_data0", listOf("10.0.0.5")),
            TpmsWebReport.NetworkCandidate("eth0", listOf("192.168.0.9"))
        )
        val first = TpmsWebReport.pickServerHost(candidates)
        assertEquals(first, TpmsWebReport.pickServerHost(candidates.reversed()))
    }

    @Test
    fun emptyOrIpv6OnlyYieldsNoHost() {
        assertNull(TpmsWebReport.pickServerHost(emptyList()))
        assertNull(TpmsWebReport.pickServerHost(listOf(TpmsWebReport.NetworkCandidate("ap0", listOf("fe80::1")))))
    }

    // ── request line parsing ───────────────────────────────────────────────────

    @Test
    fun normalizesPathsAndDropsQueries() {
        assertEquals("/", TpmsWebReport.requestPath("GET / HTTP/1.1"))
        assertEquals("/tpms.json", TpmsWebReport.requestPath("GET /tpms.json?ts=1700 HTTP/1.1"))
        // The path stays verbatim (lower-cased, trailing slash trimmed); deciding that
        // /index.html shows the dashboard is route()'s job, not the parser's.
        assertEquals("/index.html", TpmsWebReport.requestPath("GET /index.html HTTP/1.1"))
        assertEquals("/tpms.json", TpmsWebReport.requestPath("HEAD /TPMS.JSON/ HTTP/1.1"))
    }

    @Test
    fun rejectsNoiseInsteadOfGuessing() {
        assertNull(TpmsWebReport.requestPath(null))
        assertNull(TpmsWebReport.requestPath(""))
        assertNull(TpmsWebReport.requestPath("GARBAGE"))
        assertNull(TpmsWebReport.requestPath("tpms.json"))          // no method, no leading slash
        assertNull(TpmsWebReport.requestPath("POST /tpms.json HTTP/1.1")) // read-only server
        assertNull(TpmsWebReport.requestPath("GET x HTTP/1.1"))
    }

    // ── the payload a phone sees ───────────────────────────────────────────────

    @Test
    fun neverReportedWheelIsNullNotNullZero() {
        val json = TpmsWebReport.payloadJson(
            snapshot(reading(TirePosition.FRONT_LEFT, 241f)),
            PressureUnit.BAR, 200f, true, true, true, now
        )
        // Exactly one wheel has data; the other three must be "no reading" so the page draws "—".
        assertTrue(json, json.contains("\"pos\":\"FL\""))
        val fr = json.substringAfter("\"pos\":\"FR\"")
        assertTrue(fr, fr.startsWith(",\"name\":\"Front right\",\"hasReading\":false,\"pressure\":null"))
    }

    @Test
    fun pressureIsBothDisplayValueAndRawKpa() {
        val json = TpmsWebReport.payloadJson(
            snapshot(reading(TirePosition.FRONT_LEFT, 241.4f)),
            PressureUnit.BAR, 200f, true, true, true, now
        )
        assertTrue(json, json.contains("\"pressure\":2.4"))
        assertTrue(json, json.contains("\"pressureKpa\":241.4"))
        assertTrue(json, json.contains("\"unit\":\"bar\""))
    }

    @Test
    fun numbersAreAlwaysDotDecimalEvenForBar() {
        // A German or French head unit must not emit 2,4 — JSON.parse would fail in the browser.
        val json = TpmsWebReport.payloadJson(
            snapshot(reading(TirePosition.FRONT_LEFT, 245f)),
            PressureUnit.BAR, 200f, true, true, true, now
        )
        assertTrue(json, json.contains("\"pressure\":2.5"))
        assertFalse(json, json.contains(",4"))
    }

    @Test
    fun staleReadingIsFlaggedAndAgedLikeTheCard() {
        val snap = snapshot(reading(TirePosition.FRONT_LEFT, 241f, ageMs = 300_000L))
        val json = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, true, true, now)
        assertTrue(json, json.contains("\"stale\":true"))
        assertTrue(json, json.contains("\"ageSeconds\":300"))
    }

    @Test
    fun staleWheelCannotKeepThePageFlashing() {
        // Same rule the card and the chime share: a sensor we can no longer hear from must not
        // hold the warning open on months-old pressure.
        val snap = snapshot(reading(TirePosition.FRONT_LEFT, 150f, ageMs = 300_000L))
        val json = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, true, true, now)
        assertTrue(json, json.contains("\"alarm\":false"))
    }

    @Test
    fun lowPressureAlarmFollowsTheSharedDecision() {
        val snap = snapshot(
            reading(TirePosition.FRONT_LEFT, 150f),
            reading(TirePosition.FRONT_RIGHT, 241f)
        )
        val json = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, true, true, now)
        val fl = json.substringAfter("\"pos\":\"FL\"").substringBefore("\"pos\"")
        val fr = json.substringAfter("\"pos\":\"FR\"")
        assertTrue(fl, fl.contains("\"alarm\":true"))
        assertFalse(fr, fr.substringBefore("\"stale\"").contains("\"alarm\":true"))
    }

    @Test
    fun alarmSwitchOffMeansNothingIsRedOnThePhone() {
        val snap = snapshot(reading(TirePosition.FRONT_LEFT, 150f))
        val json = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, false, true, true, now)
        assertFalse(json, json.contains("\"alarm\":true"))
        assertTrue(json, json.contains("\"alarmEnabled\":false"))
    }

    @Test
    fun sensorLeakFlagAlarmsEvenAboveTheThreshold() {
        //TpmsSnapshot.alarmingWheels also honours the sensor's own leak flag; the page must too.
        val snap = snapshot(reading(TirePosition.REAR_LEFT, 241f, hasAlarm = true))
        val json = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, true, true, now)
        val rl = json.substringAfter("\"pos\":\"RL\"")
        assertTrue(rl, rl.contains("\"alarm\":true"))
        assertTrue(rl, rl.contains("\"sensorAlarm\":true"))
    }

    @Test
    fun hiddenSpareIsNotSentAtAll() {
        val snap = snapshot(
            reading(TirePosition.FRONT_LEFT, 241f),
            reading(TirePosition.SPARE, 180f)
        )
        val hidden = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, false, true, now)
        assertFalse(hidden, hidden.contains("\"SP\""))
        assertTrue(hidden, hidden.contains("\"showSpare\":false"))
        val shown = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, true, true, now)
        assertTrue(shown, shown.contains("\"pos\":\"SP\""))
    }

    @Test
    fun spareAlarmIsNeverHiddenJustBecauseTheTileIs() {
        // Hiding the spare tile is a display choice; the phone must still be told the spare is low
        // when it is being displayed, and the verdict must not leak into the four road wheels.
        val snap = snapshot(reading(TirePosition.SPARE, 120f))
        val json = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, true, true, now)
        assertTrue(json, json.contains("\"pos\":\"SP\""))
        assertTrue(json, json.substringAfter("\"pos\":\"SP\"").contains("\"alarm\":true"))
    }

    @Test
    fun noSignalWheelHasNoTemperatureInvented() {
        val snap = snapshot(reading(TirePosition.FRONT_RIGHT, 0f, noSignal = true))
        val json = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, true, true, now)
        val fr = json.substringAfter("\"pos\":\"FR\"")
        assertTrue(fr, fr.contains("\"pressure\":null"))
        assertTrue(fr, fr.contains("\"temp\":null"))
        assertTrue(fr, fr.contains("\"noSignal\":true"))
    }

    @Test
    fun linkStateAndIdleAgeAreVisibleToThePage() {
        val down = TpmsWebReport.payloadJson(
            snapshot(status = TpmsConnection.DISCONNECTED),
            PressureUnit.BAR, 200f, true, true, true, now
        )
        assertTrue(down, down.contains("\"status\":\"DISCONNECTED\""))
        assertTrue(down, down.contains("\"lastFrameAgeSeconds\":null"))

        val idle = TpmsWebReport.payloadJson(
            snapshot(reading(TirePosition.FRONT_LEFT, 241f), status = TpmsConnection.CONNECTED),
            PressureUnit.BAR, 200f, true, true, true, now
        )
        assertTrue(idle, idle.contains("\"lastFrameAgeSeconds\":1"))
    }

    @Test
    fun transportDetailIsJsonEscaped() {
        val snap = TpmsSnapshot(
            status     = TpmsConnection.ERROR,
            detail     = "cannot open \"port\"\nreset",
            deviceName = "CH340"
        )
        val json = TpmsWebReport.payloadJson(snap, PressureUnit.BAR, 200f, true, true, true, now)
        assertTrue(json, json.contains("\\\"port\\\""))
        assertTrue(json, json.contains("\\n"))
        assertFalse("raw newline must not survive in a JSON string", json.contains("\nreset"))
    }

    @Test
    fun wheelOrderMatchesTheCardLayout() {
        val json = TpmsWebReport.payloadJson(
            snapshot(
                reading(TirePosition.REAR_RIGHT, 240f),
                reading(TirePosition.FRONT_LEFT, 240f),
                reading(TirePosition.SPARE, 240f),
                reading(TirePosition.REAR_LEFT, 240f),
                reading(TirePosition.FRONT_RIGHT, 240f)
            ),
            PressureUnit.BAR, 200f, true, true, true, now
        )
        val order = listOf("FL", "FR", "RL", "RR", "SP")
        var lastIndex = -1
        for (pos in order) {
            val at = json.indexOf("\"pos\":\"$pos\"")
            assertTrue("missing $pos", at > 0)
            assertTrue("$pos out of card order", at > lastIndex)
            lastIndex = at
        }
    }

    // ── routing and the page ───────────────────────────────────────────────────

    private fun route(path: String?, accent: String = "#2979FF") =
        TpmsWebReport.route(
            path          = path,
            snapshot      = snapshot(reading(TirePosition.FRONT_LEFT, 241f)),
            unit          = PressureUnit.BAR,
            lowPressureKpa = 200f,
            alarmEnabled  = true,
            showSpare     = true,
            isMetric      = true,
            accentHex     = accent,
            nowMs         = now,
            pageHtml      = "<html><link rel=stylesheet href=/theme.css></html>",
            cssText       = "i{color:__ACCENT__}",
            appJs         = "fetch('/tpms.json');",
            manifestJson  = "{\"name\":\"Tyres\"}",
            iconPng       = byteArrayOf(1, 2, 3),
            faviconSvg    = "<svg/>"
        )

    @Test
    fun dashboardAndApiRoutesExist() {
        assertEquals(200, route("/").status)
        assertTrue(route("/").contentType.startsWith("text/html"))
        assertEquals(200, route("/tpms.json").status)
        assertTrue(route("/tpms.json").contentType.startsWith("application/json"))
        assertEquals(200, route("/manifest.json").status)
        assertEquals(200, route("/apple-touch-icon.png").status)
        assertEquals("image/png", route("/apple-touch-icon.png").contentType)
        assertEquals(200, route("/favicon.svg").status)
    }

    @Test
    fun unknownPathsAndMalformedRequestsNeverLeakData() {
        assertEquals(404, route("/settings").status)
        assertEquals(404, route("/../etc/passwd").status)
        assertEquals(400, route(null).status)
    }

    @Test
    fun livePayloadIsNeverCacheable() {
        // A cached "—" or a cached warning would outlive the link state, which is the one thing
        // this feature must not get wrong on a bookmarked shortcut.
        assertEquals("no-store", route("/tpms.json").extraHeaders["Cache-Control"])
        assertEquals("no-store", route("/").extraHeaders["Cache-Control"])
    }

    @Test
    fun accentIsInjectedAndInvalidAccentsCannotBreakThePage() {
        // The __ACCENT__ token now lives in theme.css (the page links it instead of inlining
        // styles), so the injection proof belongs to that route; fillPageTokens itself still
        // substitutes anywhere a token appears, which is what keeps the HTML side future-proof.
        val css = route("/theme.css", accent = "#00E676").body.toString(Charsets.UTF_8)
        assertTrue(css, css.contains("color:#00E676"))
        assertFalse(css, css.contains("__ACCENT__"))

        val junk = route("/theme.css", accent = "#FFF; } body{display:none}").body.toString(Charsets.UTF_8)
        assertTrue(junk, junk.contains("color:#FFFFFF"))
        assertFalse(junk, junk.contains("display:none"))
    }

    @Test
    fun stylesheetAndScriptAreOwnRoutesWithCorrectTypes() {
        // The CSP forbids inline script/style, so the page only works if these two routes exist,
        // carry the right content type (browsers refuse to run a script served as text/plain under
        // nosniff) and never get cached — an old app.js must not outlive a launcher update.
        val css = route("/theme.css", accent = "#00E676")
        assertEquals(200, css.status)
        assertTrue(css.contentType, css.contentType.startsWith("text/css"))
        assertTrue(css.body.toString(Charsets.UTF_8).contains("color:#00E676"))
        assertEquals("no-store", css.extraHeaders["Cache-Control"])

        val js = route("/app.js")
        assertEquals(200, js.status)
        assertTrue(js.contentType, js.contentType.startsWith("text/javascript"))
        assertTrue(js.body.toString(Charsets.UTF_8).contains("/tpms.json"))
        assertEquals("no-store", js.extraHeaders["Cache-Control"])
    }

    @Test
    fun missingPageAssetDegradesTo503WithoutKillingTheApi() {
        val response = TpmsWebReport.route(
            path          = "/",
            snapshot      = snapshot(),
            unit          = PressureUnit.BAR,
            lowPressureKpa = 200f,
            alarmEnabled  = true,
            showSpare     = true,
            isMetric      = true,
            accentHex     = "#FFFFFF",
            nowMs         = now,
            pageHtml      = null,
            cssText       = null,
            appJs         = null,
            manifestJson  = null,
            iconPng       = null,
            faviconSvg    = null
        )
        assertEquals(503, response.status)
        // The other asset-backed routes degrade the same way, individually…
        assertEquals(503, TpmsWebReport.route(
            path          = "/theme.css",
            snapshot      = snapshot(),
            unit          = PressureUnit.BAR,
            lowPressureKpa = 200f,
            alarmEnabled  = true,
            showSpare     = true,
            isMetric      = true,
            accentHex     = "#FFFFFF",
            nowMs         = now,
            pageHtml      = null,
            cssText       = null,
            appJs         = null,
            manifestJson  = null,
            iconPng       = null,
            faviconSvg    = null
        ).status)
        // …and one missing asset never takes down the live JSON API.
        assertEquals(200, TpmsWebReport.route(
            path          = "/tpms.json",
            snapshot      = snapshot(),
            unit          = PressureUnit.BAR,
            lowPressureKpa = 200f,
            alarmEnabled  = true,
            showSpare     = true,
            isMetric      = true,
            accentHex     = "#FFFFFF",
            nowMs         = now,
            pageHtml      = null,
            cssText       = null,
            appJs         = null,
            manifestJson  = null,
            iconPng       = null,
            faviconSvg    = null
        ).status)
    }

    @Test
    fun unitSystemDrivesTemperatureNotPressureScale() {
        // Temperature follows the launcher's metric/imperial switch, pressure follows the TPMS
        // unit setting; mixing them up would put bar values next to Fahrenheit numbers.
        val metric = TpmsWebReport.payloadJson(
            snapshot(reading(TirePosition.FRONT_LEFT, 241f)),
            PressureUnit.BAR, 200f, true, true, true, now
        )
        assertTrue(metric, metric.contains("\"metric\":true"))
        assertTrue(metric, metric.contains("\"temp\":20"))
        val imperial = TpmsWebReport.payloadJson(
            snapshot(reading(TirePosition.FRONT_LEFT, 241f)),
            PressureUnit.PSI, 200f, true, true, false, now
        )
        assertTrue(imperial, imperial.contains("\"metric\":false"))
        assertTrue(imperial, imperial.contains("\"unit\":\"psi\""))
        assertNotNull(imperial)
    }
}
