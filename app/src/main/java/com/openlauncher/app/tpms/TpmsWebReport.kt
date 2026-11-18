package com.openlauncher.app.tpms

import java.util.Locale

/**
 * Pure part of the tyre-pressure LAN web server: what a phone browser is allowed to see.
 *
 * Split out of [TpmsWebServer] exactly like [TpmsFrameDecoder] is split out of
 * [TpmsUsbSource] — this file has no Android and no socket imports, so the wire contract of the
 * web API is JVM-unit-tested ([TpmsWebReportTest]) while the socket itself is device-verified only.
 *
 * The whole point of this layer is honesty across the network: the card and the page must show the
 * same numbers and the same warning decision. So the JSON carries
 *  - the raw kPa value AND the display-unit string, so a browser can never re-interpret a scale,
 *  - per-wheel staleness / no-signal / never-reported flags, so "—" survives the trip instead of
 *    degrading into a plausible-looking 0,
 *  - the alarm verdict computed here via [TpmsSnapshot.alarmingWheels] — the single warn decision
 *    the card, the settings label and the chime already share. The page does not decide anything;
 *    it paints what it is told.
 */
object TpmsWebReport {

    /** One network interface candidate for "which address should the phone be told to open". */
    data class NetworkCandidate(val name: String, val addresses: List<String>)

    /** Everything the transport layer needs to answer one request. */
    data class WebResponse(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        /** Extra cache/security headers the transport appends. */
        val extraHeaders: Map<String, String> = emptyMap()
    )

    /**
     * Pick the address a phone on the unit's hotspot should dial.
     *
     * The hotspot/AP interface is the answer — a phone joining the unit's network reaches the unit
     * through that interface, not through a client-mode Wi-Fi address. Qualcomm/SPD builds name it
     * `ap0`, older ones `swlan0`/`softap`, vendor variants `wifi-ap`; those are checked first and in
     * that order so the choice is deterministic on every ROM instead of "first interface found".
     * Ethernet (`eth0`/`rmnet` USB-tether) is a legitimate fallback for a bench setup, and plain
     * `wlan0` last, because on a head unit that is the client side, not the access point.
     *
     * Returns null when nothing usable exists — the caller then shows "no network" instead of a
     * made-up URL that a bookmark would silently break on.
     */
    fun pickServerHost(candidates: List<NetworkCandidate>): String? {
        val apNames = listOf("ap0", "swlan0", "softap", "wifi-ap", "athap0")
        val otherNames = listOf("eth0", "rmnet0", "rmnet_data0", "rndis0")
        for (group in listOf(apNames, otherNames)) {
            for (name in group) {
                candidates.firstOrNull { it.name == name }
                    ?.let { return validIpv4(it.addresses) ?: continue }
            }
        }
        // Last resort: any interface carrying a usable private IPv4, in stable name order,
        // so repeated calls with the same input always answer the same way.
        val fallback = candidates
            .mapNotNull { c -> validIpv4(c.addresses)?.let { c.name to it } }
            .sortedBy { it.first }
            .firstOrNull()
        return fallback?.second
    }

    /** Rejects loopback, link-local (169.254.x — no gateway, a phone could never route to it) and IPv6 text forms. */
    private fun validIpv4(addresses: List<String>): String? =
        addresses.firstOrNull { addr ->
            addr.count { it == '.' } == 3 &&
                !addr.startsWith("127.") &&
                !addr.startsWith("169.254.") &&
                addr.all { it.isDigit() || it == '.' }
        }

    /**
     * Parse the path out of an HTTP request line ("GET /tpms.json HTTP/1.1").
     * Returns null for anything malformed — the transport answers 400 and closes rather than
     * guessing, which keeps a stray binary/noise connection from being served data.
     */
    fun requestPath(requestLine: String?): String? {
        val parts = requestLine?.trim()?.split(Regex("\\s+")) ?: return null
        if (parts.size < 2) return null
        if (parts[0].uppercase(Locale.US) !in setOf("GET", "HEAD")) return null
        val path = parts[1].substringBefore('?')
        if (path.isEmpty() || !path.startsWith("/")) return null
        return if (path.length > 1) path.trimEnd('/').lowercase(Locale.US) else "/"
    }

    /**
     * Live tyre payload as JSON. Fixed field set, `Locale.US` number formatting (a German or
     * French head unit must not emit "2,4" and break a browser's JSON.parse), and nulls where the
     * card shows "—".
     */
    fun payloadJson(
        snapshot: TpmsSnapshot,
        unit: PressureUnit,
        lowPressureKpa: Float,
        alarmEnabled: Boolean,
        showSpare: Boolean,
        nowMs: Long
    ): String {
        val alarming = snapshot.alarmingWheels(lowPressureKpa, alarmEnabled, nowMs).toSet()
        val sb = StringBuilder(512)
        sb.append('{')
        sb.append("\"status\":\"").append(snapshot.status.name).append("\",")
        sb.append("\"detail\":").append(jsonString(snapshot.detail)).append(",")
        sb.append("\"device\":").append(jsonString(snapshot.deviceName)).append(",")
        sb.append("\"unit\":\"").append(unit.label).append("\",")
        sb.append("\"decimals\":").append(unit.decimalsForJson()).append(",")
        sb.append("\"alarmEnabled\":").append(alarmEnabled).append(",")
        sb.append("\"showSpare\":").append(showSpare).append(",")
        sb.append("\"lowPressure\":").append(num(unit.fromKpa(lowPressureKpa), unit)).append(",")
        sb.append("\"lastFrameAt\":").append(snapshot.lastFrameAt).append(",")
        sb.append("\"lastFrameAgeSeconds\":")
        sb.append(if (snapshot.lastFrameAt == 0L) "null"
                  else ((nowMs - snapshot.lastFrameAt) / 1000L).coerceAtLeast(0L))
        sb.append(",\"serverNow\":").append(nowMs)
        sb.append(",\"wheels\":[")

        // FL/FR then RL/RR, matching the card's 2x2 layout, spare last: a phone page should read
        // like the dash, not like an alphabetised dump.
        val ordered = TirePosition.FOUR_WHEELS +
            if (showSpare) listOf(TirePosition.SPARE) else emptyList()
        var firstWheel = true
        for (pos in ordered) {
            val r = snapshot.reading(pos)
            if (!firstWheel) sb.append(',')
            firstWheel = false
            sb.append(wheelJson(pos, r, unit, alarming.contains(pos), nowMs))
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun wheelJson(
        pos: TirePosition,
        r: TireReading?,
        unit: PressureUnit,
        isAlarm: Boolean,
        nowMs: Long
    ): String {
        val sb = StringBuilder(160)
        sb.append('{')
        sb.append("\"pos\":\"").append(pos.shortLabel).append("\",")
        sb.append("\"name\":\"").append(pos.label).append("\",")
        if (r == null) {
            // Nothing has ever reported this wheel — the page renders "—" and must not
            // render a stale or zero number.
            sb.append("\"hasReading\":false,\"pressure\":null,\"pressureKpa\":null,")
            sb.append("\"temp\":null,\"stale\":false,\"ageSeconds\":null,")
            sb.append("\"noSignal\":false,\"alarm\":false,\"timestamp\":null}")
            return sb.toString()
        }
        val stale = r.isStale(nowMs)
        sb.append("\"hasReading\":true,")
        sb.append("\"pressure\":")
            .append(if (r.noSignal && r.pressureKpa <= 0f) "null" else num(unit.fromKpa(r.pressureKpa), unit))
            .append(',')
        sb.append("\"pressureKpa\":").append(num(r.pressureKpa, decimals = 1)).append(",")
        sb.append("\"temp\":").append(temperatureField(r, nowMs)).append(",")
        sb.append("\"stale\":").append(stale).append(",")
        sb.append("\"ageSeconds\":").append(r.ageSeconds(nowMs)).append(",")
        sb.append("\"noSignal\":").append(r.noSignal).append(",")
        sb.append("\"sensorAlarm\":").append(r.hasAlarm).append(",")
        sb.append("\"alarm\":").append(isAlarm).append(",")
        sb.append("\"timestamp\":").append(r.timestamp).append('}')
        return sb.toString()
    }

    /**
     * Tyre temperature, or null when there is nothing honest to say. The card shows "NO SIGNAL"
     * rather than a temperature for a sensor it cannot hear, so the page gets null too and the
     * browser decides the wording — never this layer inventing a degree value.
     */
    private fun temperatureField(r: TireReading, nowMs: Long): String {
        if (r.noSignal) return "null"
        val celsius = r.temperatureCelsius
        if (celsius < -40f) return "null"   // the frame's "no reading" floor
        return num(celsius, decimals = 0)
    }

    /** Display-unit rounding: bar 2 decimals… 1, kPa 0, psi 1 — same rules as [PressureUnit.format]. */
    private fun num(value: Float, unit: PressureUnit): String = num(value, unit.decimalsForJson())

    private fun num(value: Float, decimals: Int): String {
        if (value.isNaN() || value.isInfinite()) return "null"
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun PressureUnit.decimalsForJson(): Int = when (this) {
        PressureUnit.BAR -> 1
        PressureUnit.PSI -> 1
        PressureUnit.KPA -> 0
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val sb = StringBuilder(value.length + 8)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '"'  -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(
                    String.format(Locale.US, "%04x", ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Route one parsed path to a response. Pure so the whole public surface of the server —
     * which paths exist, what content type they claim, and what 404 looks like — is unit-tested.
     *
     * [pageHtml] / [manifestJson] / [iconPng] are the static assets the transport loads once.
     */
    fun route(
        path: String?,
        snapshot: TpmsSnapshot,
        unit: PressureUnit,
        lowPressureKpa: Float,
        alarmEnabled: Boolean,
        showSpare: Boolean,
        accentHex: String,
        nowMs: Long,
        pageHtml: String?,
        manifestJson: String?,
        iconPng: ByteArray?,
        faviconSvg: String?
    ): WebResponse {
        if (path == null) return textResponse(400, "Bad Request")
        return when (path) {
            "/", "/index.html", "/tpms", "/tpms.html" ->
                if (pageHtml == null) textResponse(503, "Dashboard asset missing")
                else htmlResponse(fillPageTokens(pageHtml, accentHex))
            "/tpms.json" -> WebResponse(
                status = 200,
                contentType = "application/json; charset=utf-8",
                body = payloadJson(snapshot, unit, lowPressureKpa, alarmEnabled, showSpare, nowMs)
                    .toByteArray(Charsets.UTF_8),
                // Never cache: a bookmarked shortcut must show live pressures, and a cached
                // "—" would otherwise outlive the link coming back.
                extraHeaders = mapOf("Cache-Control" -> "no-store")
            )
            "/manifest.webmanifest", "/manifest.json" ->
                if (manifestJson == null) textResponse(404, "Not found")
                else WebResponse(
                    200, "application/manifest+json; charset=utf-8",
                    manifestJson.toByteArray(Charsets.UTF_8),
                    mapOf("Cache-Control" -> "no-store")
                )
            "/apple-touch-icon.png", "/icon.png" ->
                if (iconPng == null) textResponse(404, "Not found")
                else WebResponse(200, "image/png", iconPng, mapOf("Cache-Control" -> "max-age=86400"))
            "/favicon.svg" ->
                if (faviconSvg == null) textResponse(404, "Not found")
                else WebResponse(200, "image/svg+xml; charset=utf-8",
                    faviconSvg.toByteArray(Charsets.UTF_8), mapOf("Cache-Control" -> "max-age=86400"))
            "/health" -> textResponse(200, "ok " + snapshot.status.name)
            else -> textResponse(404, "Not found")
        }
    }

    /**
     * Inject the launcher's accent into the page. Token replacement instead of string
     * concatenation keeps the HTML a plain checked-in asset that can be opened and eyeballed
     * on a desk, and keeps the accent choice a runtime decision.
     */
    fun fillPageTokens(html: String, accentHex: String): String =
        html.replace("__ACCENT__", sanitizeHex(accentHex))

    /** Anything that isn't #RRGGBB degrades to white rather than splicing arbitrary text into CSS. */
    private fun sanitizeHex(hex: String): String =
        if (Regex("^#[0-9a-fA-F]{6}$").matches(hex)) hex else "#FFFFFF"

    private fun htmlResponse(html: String) = WebResponse(
        200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8),
        mapOf("Cache-Control" -> "no-store")
    )

    private fun textResponse(status: Int, message: String) = WebResponse(
        status,
        "text/plain; charset=utf-8",
        message.toByteArray(Charsets.UTF_8),
        mapOf("Cache-Control" -> "no-store")
    )
}
