package com.openlauncher.app.tpms

import android.content.Context
import android.net.wifi.WifiManager
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.UnitSystem
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small HTTP server that mirrors the tyre card onto a phone browser over the unit's own hotspot.
 *
 * Why this shape: the phone already joins the head unit's Wi-Fi hotspot for CarPlay, so a LAN page
 * needs no app store, no code signing, no TLS certificate and no internet — it works with the car
 * and the phone both fully offline. The unit itself serves the HTML, manifest and icon.
 *
 * Deliberately a hand-rolled [ServerSocket] instead of a library: the routing and serialisation half
 * is pure and unit-tested in [TpmsWebReport], which keeps this socket layer small enough to audit,
 * and minSdk 21 aftermarket units gain no dependency that can age badly. It binds one fixed port on
 * `0.0.0.0` with NO fallback: the phone bookmarks `http://<gateway>:<port>/` as a home-screen
 * shortcut, so a port that silently moved would break a shortcut that appeared to work. A busy port
 * surfaces as an error on the card instead of a surprise move.
 *
 * Lifecycle: process-wide like [TpmsRepository] (the port can only be bound once, and MainActivity
 * is recreated on theme changes/HOME relaunch while the phone keeps polling). Runs only while
 * [AppSettings.tpmsWebServerEnabled] is on and a TPMS card exists. Idle cost is one blocked
 * `accept()`, which is why leaving the card's ON button on is harmless even on the QCM665 unit.
 *
 * Security stance — read before adding anything: unauthenticated, and acceptable only because the
 * listener lives on a private hotspot with no route to the outside world and serves a read-only,
 * non-secret snapshot. Do not add write/actuator endpoints, do not add vehicle or account data, and
 * do not bind this to a Wi-Fi-client or cellular interface.
 */
class TpmsWebServer private constructor(private val context: Context) {

    enum class State { STOPPED, RUNNING, ERROR }

    /** What the card renders: state, and the exact URL the phone should open. */
    data class Status(
        val state: State = State.STOPPED,
        val port: Int = 0,
        /** Hotspot-side address the phone reaches us on; null until an interface exists. */
        val host: String? = null,
        /** Human-readable failure or "waiting for the hotspot"; null when healthy. */
        val detail: String? = null
    ) {
        val isRunning: Boolean get() = state == State.RUNNING

        /**
         * Full URL, shown on the card so the phone shortcut is typed exactly as displayed.
         * `http://` is spelled out rather than hinted: Safari accepts a bare host:port, but showing
         * the real scheme is what stops the user from bookmarking something that later fails.
         */
        val url: String?
            get() = if (state == State.RUNNING && host != null) "http://$host:$port/" else null
    }

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    private val tpms = TpmsRepository.get(context)

    @Volatile private var listener: ServerSocket? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var settings: AppSettings = AppSettings()

    /** Assets are read once out of the APK and kept in memory: no per-request file I/O on a weak unit. */
    @Volatile private var pageHtml: String? = null
    @Volatile private var cssText: String? = null
    @Volatile private var appJs: String? = null
    @Volatile private var manifestJson: String? = null
    @Volatile private var faviconSvg: String? = null
    @Volatile private var iconPng: ByteArray? = null
    @Volatile private var assetsLoaded = false

    /**
     * Pushed by the settings collector so a request uses the current thresholds, units and accent
     * without restarting the listener. The snapshot itself is NOT pushed: each request reads
     * `tpms.snapshot.value` from the repository, the same live source the card and the chime use, so
     * a phone polling every 2 s can never be the reason the launcher's own state went stale.
     */
    fun update(appSettings: AppSettings) {
        settings = appSettings
    }

    /** Start/stop on the arm conditions the ViewModel decides: setting on AND a TPMS card exists. */
    fun sync(enabled: Boolean, port: Int) {
        if (enabled) start(port) else stop()
    }

    fun start(port: Int) {
        if (acceptThread != null) return
        loadAssetsOnce()
        val thread = Thread({ acceptLoop(port) }, "TpmsWebServer")
        thread.isDaemon = true
        // Publish the thread BEFORE binding: stop() and acceptLoop both compare against
        // Thread.currentThread(), and a race here would otherwise leave an orphaned listener holding
        // the fixed port, which the next start() could never reclaim.
        acceptThread = thread
        _status.value = Status(State.RUNNING, port, currentHost(),
            if (currentHost() == null) "Waiting for the hotspot" else null)
        thread.start()
    }

    fun stop() {
        val socket = listener
        acceptThread = null
        listener = null
        runCatching { socket?.close() }
        if (_status.value.state != State.STOPPED) _status.value = Status(State.STOPPED)
    }

    /**
     * Refresh the address the card advertises. The hotspot interface can appear long after the
     * server started — the user turns tethering on in the vendor settings app, sometimes after a
     * reboot — and the URL on the card is what the phone's bookmark has to match, so a stale or
     * missing address here is a silently broken shortcut.
     */
    fun refreshHost() {
        val current = _status.value
        if (current.state != State.RUNNING) return
        val host = currentHost() ?: return
        if (host != current.host) _status.value = current.copy(host = host, detail = null)
    }

    private fun acceptLoop(port: Int) {
        // Binding 0.0.0.0 succeeds even before the AP interface exists, which is why there is no
        // bind-retry loop: a later interface only changes which URL we advertise, and refreshHost()
        // picks that up while this same listener keeps running.
        val socket = try {
            ServerSocket(port, 4, InetAddress.getByName("0.0.0.0"))
        } catch (e: Exception) {
            // Almost always "address already in use". No fallback on purpose — see class KDoc.
            if (acceptThread == null) return           // deliberate stop()
            _status.value = _status.value.copy(
                state = State.ERROR,
                detail = e.message ?: e.javaClass.simpleName
            )
            return
        }
        listener = socket
        if (acceptThread == null) {                     // stopped while we were binding
            runCatching { socket.close() }
            return
        }
        _status.value = _status.value.copy(port = port, state = State.RUNNING)
        refreshHost()
        try {
            while (!Thread.currentThread().isInterrupted) {
                val client = try {
                    socket.accept()
                } catch (_: java.net.SocketException) {
                    break                               // stop() closed the listener
                }
                handleAsync(client)
            }
        } finally {
            runCatching { socket.close() }
            if (listener === socket) {
                listener = null
                if (acceptThread != null) _status.value = Status(State.STOPPED)
            }
        }
    }

    /**
     * One connection per short-lived thread. The timeouts are not optional: a phone's captive
     * portal or a browser that opens a socket and sends nothing must not tie up the head unit.
     */
    private fun handleAsync(client: Socket) {
        val worker = Thread({
            try {
                client.soTimeout = 5_000
                client.tcpNoDelay = true
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
                val path = TpmsWebReport.requestPath(reader.readLine())
                // Drain the header block. A one-shot response does not need it, but skipping it
                // would let a pipelining client's next request look like a malformed one.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val response = TpmsWebReport.route(
                    path = path,
                    snapshot = tpms.snapshot.value,
                    unit = settings.tpmsPressureUnit,
                    lowPressureKpa = settings.tpmsLowPressureKpa,
                    alarmEnabled = settings.tpmsAlarmEnabled,
                    showSpare = settings.tpmsShowSpare,
                    isMetric = settings.unitSystem == UnitSystem.METRIC,
                    accentHex = accentHex(),
                    nowMs = System.currentTimeMillis(),
                    pageHtml = pageHtml,
                    cssText = cssText,
                    appJs = appJs,
                    manifestJson = manifestJson,
                    iconPng = iconPng,
                    faviconSvg = faviconSvg
                )
                writeResponse(client.getOutputStream(), response)
            } catch (_: Exception) {
                // A dropped or half-open client on a flaky hotspot is normal; never let one take
                // the launcher down with it.
            } finally {
                runCatching { client.close() }
            }
        }, "TpmsWebConn")
        worker.isDaemon = true
        worker.start()
    }

    /** Minimal but strict HTTP/1.1: correct status line, content length, Connection: close. */
    private fun writeResponse(out: OutputStream, response: TpmsWebReport.WebResponse) {
        val head = StringBuilder(256)
        head.append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
        head.append("Content-Type: ").append(response.contentType).append("\r\n")
        head.append("Content-Length: ").append(response.body.size).append("\r\n")
        head.append("Connection: close\r\n")
        head.append("X-Content-Type-Options: nosniff\r\n")
        // Strict CSP: the page has no inline script or style any more (theme.css and app.js are
        // their own same-origin routes), so there is nothing to exempt — 'unsafe-inline' is gone
        // and inline execution stays blocked, which is what keeps a man-in-the-middle or a captive
        // portal on some other network from injecting behaviour into a page that shows safety data.
        head.append("Content-Security-Policy: default-src 'self'; script-src 'self'; ")
            .append("style-src 'self'; img-src 'self' data:; frame-ancestors 'none'\r\n")
        for ((k, v) in response.extraHeaders) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(response.body)
        out.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    /**
     * The launcher's accent as the page sees it — same ARGB setting the cards use, so the phone page
     * is literally the same colour as the dash. The alpha byte is dropped on purpose: the page paints
     * on the dark card surface, where a translucent accent would read as a washed-out grey.
     */
    private fun accentHex(): String = String.format(Locale.US, "#%06X", settings.accentColor and 0xFFFFFF)

    /**
     * Which address to tell the phone. The decision rules live in the pure
     * [TpmsWebReport.pickServerHost] (unit-tested); this function only enumerates Android's
     * interfaces. This is the hotspot gateway — the tethering subnet is fixed by the OS
     * (192.168.43.1 on this QCM build), which is exactly why the phone's shortcut survives reboots.
     */
    private fun currentHost(): String? {
        val candidates = try {
            NetworkInterface.getNetworkInterfaces().toList().mapNotNull { nif ->
                val addrs = nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress }
                if (addrs.isEmpty()) null else TpmsWebReport.NetworkCandidate(nif.name, addrs)
            }
        } catch (_: Exception) {
            emptyList()
        }
        TpmsWebReport.pickServerHost(candidates)?.let { return it }
        // Fallback for the AVD / a shared bench LAN, where there is no AP interface at all: the
        // Wi-Fi station address, so the card shows something reachable rather than nothing.
        return runCatching {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
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
            cssText = assets.open(WEB_DIR + "/theme.css").use { it.readBytes().toString(Charsets.UTF_8) }
            appJs = assets.open(WEB_DIR + "/app.js").use { it.readBytes().toString(Charsets.UTF_8) }
            manifestJson = assets.open(WEB_DIR + "/manifest.json").use { it.readBytes().toString(Charsets.UTF_8) }
            faviconSvg = assets.open(WEB_DIR + "/favicon.svg").use { it.readBytes().toString(Charsets.UTF_8) }
            iconPng = assets.open(WEB_DIR + "/apple-touch-icon.png").use { it.readBytes() }
        } catch (e: Exception) {
            // A missing asset makes only that path answer 503/404 — the JSON API keeps working,
            // so a broken page never takes the whole feature (or the launcher) down.
            android.util.Log.w(TAG, "web asset load failed: ${e.message}")
        }
        assetsLoaded = true
    }

    companion object {
        // Same log tag as the rest of TPMS: one `logcat -s TpmsUsb` filter covers the whole feature.
        const val TAG = "TpmsUsb"
        const val WEB_DIR = "tpmsweb"

        @Volatile private var instance: TpmsWebServer? = null

        fun get(context: Context): TpmsWebServer =
            instance ?: synchronized(this) {
                instance ?: TpmsWebServer(context.applicationContext).also { instance = it }
            }
    }
}
