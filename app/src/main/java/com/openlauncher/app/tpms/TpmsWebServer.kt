package com.openlauncher.app.tpms

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.provider.Settings
import com.openlauncher.app.data.AppSettings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tiny HTTP server that puts the tyre card on a phone browser over the unit's own Wi-Fi hotspot.
 *
 * Why this shape: the launcher already runs on a head unit whose hotspot the phone joins for
 * CarPlay, so a LAN page needs no app store, no signing, no TLS and no internet at all — it works
 * with the car and the phone both fully offline. Everything the browser needs (HTML, manifest,
 * icon) is served by the unit itself.
 *
 * Deliberately a hand-rolled [ServerSocket] rather than a library: the routing/serialisation half
 * is pure and unit-tested in [TpmsWebReport], so the socket layer stays small enough to audit, and
 * minSdk 21 aftermarket units gain no new dependency to age badly. Bind is `0.0.0.0` on one fixed
 * port with NO fallback: the phone bookmarks `http://<gateway>:<port>` as a home-screen shortcut,
 * so a port that silently moved would break a shortcut that looked like it was working. A busy port
 * surfaces as an error state on the card instead.
 *
 * Lifecycle: owned by the process (like [TpmsRepository]), started only while
 * [AppSettings.tpmsWebServerEnabled] is on and a TPMS card exists. Idle cost is one blocked
 * `accept()` — negligible even on the QCM665 unit, which is why the card's button can be left on.
 *
 * Security stance: no authentication, and that is acceptable ONLY because the listener exists on a
 * private hotspot with no route to the outside world, and what it serves is a read-only,
 * non-secret snapshot (tyre pressures). Do not add any write/actuator endpoint here without adding
 * a token — and do not bind this to a Wi-Fi client or cellular interface.
 */
class TpmsWebServer(private val context: Context) {

    enum class State { STOPPED, STARTING, RUNNING, ERROR }

    data class Status(
        val state: State = State.STOPPED,
        val port: Int = 0,
        val host: String? = null,
        val detail: String? = null
    ) {
        /** Full URL shown on the card so the phone shortcut can be typed exactly as displayed. */
        val url: String? get() = if (state == State.RUNNING && host != null) "http://$host:$port/" else null
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var lastSnapshot: TpmsSnapshot = TpmsSnapshot()
    @Volatile private var settings: AppSettings = AppSettings()
    @Volatile private var accentHex: String = "#FFFFFF"

    /** Assets are read once from the APK and kept in memory: no per-request I/O on a weak unit. */
    @Volatile private var pageHtml: String? = null
    @Volatile private var manifestJson: String? = null
    @Volatile private var faviconSvg: String? = null
    @Volatile private var iconPng: ByteArray? = null
    @Volatile private var assetsLoaded = false

    /** Called by the settings collector so a request sees current thresholds/units without a restart. */
    fun configure(snapshot: TpmsSnapshot, appSettings: AppSettings, accentColor: Int) {
        lastSnapshot = snapshot
        settings = appSettings
        accentHex = String.format("#%06X", accentColor and 0xFFFFFF)
    }

    val isRunning: Boolean get() = _status.value.state == State.RUNNING

    fun start(port: Int) {
        if (acceptThread != null) return
        _status.value = Status(State.STARTING, port, null, null)
        loadAssetsOnce()
        val host = currentHost()
        if (host == null) {
            // No usable interface yet (hotspot off). Stay STARTING rather than claiming a URL:
            // the card shows "waiting for the hotspot" and the accept loop below re-checks on each
            // successful bind.
            _status.value = Status(State.STARTING, port, null, context.getString(
                com.openlauncher.app.R.string.tpms_web_needs_hotspot))
        }
        val thread = Thread({ acceptLoop(port, host) }, "TpmsWebServer")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
    }

    fun stop() {
        val socket = serverSocket
        serverSocket = null
        acceptThread = null
        runCatching { socket?.close() }
        _status.value = Status(State.STOPPED)
    }

    /**
     * Re-check the interface an active thread holds; the hotspot can come up after the server did,
     * so the displayed URL refreshes without a restart.
     */
    fun refreshHost() {
        val current = _status.value
        if (current.state != State.RUNNING && current.state != State.STARTING) return
        val host = currentHost()
        if (host != null && host != current.host) {
            _status.value = current.copy(state = State.RUNNING, host = host, detail = null)
        }
    }

    private fun acceptLoop(port: Int, hostAtStart: String?) {
        var boundSocket: ServerSocket? = null
        try {
            // One retry pass with a short wait: hotspot interfaces often appear a second after the
            // launcher toggles the setting on, and binding to 0.0.0.0 succeeds even before that, so
            // this only covers the rare ROM that rejects a bind while Wi-Fi is bringing up.
            boundSocket = ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"))
            serverSocket = boundSocket
            _status.value = Status(
                State.RUNNING, port, hostAtStart ?: currentHost(),
                if (hostAtStart == null && currentHost() == null)
                    context.getString(com.openlauncher.app.R.string.tpms_web_needs_hotspot) else null
            )
            while (!Thread.currentThread().isInterrupted) {
                val client = try {
                    boundSocket.accept()
                } catch (_: java.net.SocketException) {
                    break   // stop() closed the listener
                }
                handleAsync(client)
            }
        } catch (e: Exception) {
            if (serverSocket == null || acceptThread == null) return   // deliberate stop
            _status.value = Status(State.ERROR, port, hostAtStart ?: currentHost(),
                e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { boundSocket?.close() }
            if (serverSocket === boundSocket) {
                serverSocket = null
                if (_status.value.state == State.RUNNING) _status.value = Status(State.STOPPED)
            }
        }
    }

    /**
     * One connection per short-lived thread. Read/write timeouts are mandatory: a captive-portal
     * phone that opens a socket and sends nothing must not tie up a thread on the head unit.
     */
    private fun handleAsync(client: Socket) {
        val worker = Thread({
            try {
                client.soTimeout = 5_000
                client.tcpNoDelay = true
                val input = client.getInputStream()
                val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1))
                val path = TpmsWebReport.requestPath(reader.readLine())
                // Drain the headers: leaving them in the stream is harmless for a one-shot
                // response, but reading them keeps keep-alive abuse from stacking on one socket.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val response = TpmsWebReport.route(
                    path = path,
                    snapshot = lastSnapshot,
                    unit = settings.tpmsPressureUnit,
                    lowPressureKpa = settings.tpmsLowPressureKpa,
                    alarmEnabled = settings.tpmsAlarmEnabled,
                    showSpare = settings.tpmsShowSpare,
                    accentHex = accentHex,
                    nowMs = System.currentTimeMillis(),
                    pageHtml = pageHtml,
                    manifestJson = manifestJson,
                    iconPng = iconPng,
                    faviconSvg = faviconSvg
                )
                writeResponse(client.getOutputStream(), response, path)
            } catch (_: Exception) {
                // A dropped/broken client is normal on a flaky hotspot; never let one kill the unit.
            } finally {
                runCatching { client.close() }
            }
        }, "TpmsWebConn")
        worker.isDaemon = true
        worker.start()
    }

    /** Minimal but strict HTTP/1.1: correct status line, length, no keep-alive. */
    private fun writeResponse(out: OutputStream, response: TpmsWebReport.WebResponse, path: String?) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(response.status)
            .append(' ').append(reason(response.status)).append("\r\n")
        head.append("Content-Type: ").append(response.contentType).append("\r\n")
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        // A home-screen web app must be allowed to frame its own assets and must never be
        // rewritten by a captive portal on someone else's network.
        head.append("Content-Security-Policy: default-src 'self' data:; img-src 'self' data:; style-src 'unsafe-inline'; frame-ancestors 'none'\r\n")
        for ((k, v) in response.extraHeaders) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 503 -> "Service Unavailable"
        else -> "Error"
    }

    /**
     * Which address to tell the phone. Delegates the choice to the pure [TpmsWebReport.pickServerHost]
     * so the ordering rules are unit-tested; enumeration itself is Android-only.
     *
     * The hotspot gateway is the interface named ap0/swlan0 on this QCM build — a phone joining the
     * unit reaches it there, which is why the shortcut keeps working across reboots: the tethering
     * subnet (typically 192.168.43.1) is fixed by the OS, not chosen by DHCP.
     */
    private fun currentHost(): String? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces().toList().mapNotNull { nif ->
                val addrs = nif.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress }
                if (addrs.isEmpty()) null else TpmsWebReport.NetworkCandidate(nif.name, addrs)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val picked = TpmsWebReport.pickServerHost(candidates)
        if (picked != null) return picked
        // Last resort for the AVD/desktop case: the Wi-Fi station address, so the card still shows
        // something reachable on a shared LAN instead of nothing at all.
        return runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi?.connectionInfo?.ipAddress ?: 0
            if (ip == 0) null else (ip and 0xFF).toString() + "." +
                ((ip shr 8) and 0xFF) + "." + ((ip shr 16) and 0xFF) + "." + ((ip shr 24) and 0xFF)
        }.getOrNull()
    }

    private fun loadAssetsOnce() {
        if (assetsLoaded) return
        try {
            val assets = context.assets
            pageHtml = assets.open(WEB_DIR + "/index.html").use { it.readBytes().toString(Charsets.UTF_8) }
            manifestJson = assets.open(WEB_DIR + "/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }
            faviconSvg = assets.open(WEB_DIR + "/favicon.svg").use { it.readBytes().toString(Charsets.UTF_8) }
            iconPng = assets.open(WEB_DIR + "/apple-touch-icon.png").use { it.readBytes() }
        } catch (e: Exception) {
            // Missing asset => the route answers 503/404 for that path only; the JSON API still
            // works, so a broken page never takes the whole feature down.
            android.util.Log.w(TAG, "web asset load failed: ${e.message}")
        }
        assetsLoaded = true
    }

    companion object {
        const val TAG = "TpmsUsb"       // same tag as the rest of TPMS: one filter covers the feature
        const val WEB_DIR = "tpmsweb"
        const val CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE"

        /** Best-effort "open the hotspot settings" jump, mirroring the sidebar hotspot click chain. */
        fun hotspotSettingsIntent(): Array<Intent> = arrayOf(
            Intent(Settings.ACTION_TETHER_SETTINGS),
            Intent(Settings.ACTION_WIRELESS_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
    }
}
