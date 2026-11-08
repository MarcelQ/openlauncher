package com.openlauncher.app.tpms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.openlauncher.app.tpms.TpmsConnection.CONNECTED
import com.openlauncher.app.tpms.TpmsConnection.CONNECTING
import com.openlauncher.app.tpms.TpmsConnection.DISCONNECTED
import com.openlauncher.app.tpms.TpmsConnection.ERROR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Launcher-scoped TPMS state owner: transport lifecycle, reconnect policy, sensor state and
 * the snapshot the home card renders.
 *
 * Deliberately not tied to a screen or to a [androidx.lifecycle.ViewModel] — a low-pressure
 * warning has to work while the driver is looking at the map, not only while a TPMS screen is
 * open. One instance lives for the process lifetime ([get]) so two ViewModels can never fight
 * over the same serial port.
 *
 * Threading: connection work is serialised by [connectMutex] and always runs off the main
 * thread (stopping `SerialInputOutputManager` joins its threads). A transport error is never
 * handled on the io runner's own thread — it only flags [pendingTeardown], and the monitor
 * loop performs the teardown.
 */
class TpmsRepository private constructor(context: Context) : TpmsUsbSource.Listener {

    private val appContext = context.applicationContext

    /** Process-lifetime scope: the receiver must outlive the home screen's recompositions. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val connectMutex = Mutex()

    private val _status = MutableStateFlow(DISCONNECTED)
    private val _readings = MutableStateFlow<Map<TirePosition, TireReading>>(emptyMap())
    private val _detail = MutableStateFlow<String?>(null)
    private val _deviceName = MutableStateFlow<String?>(null)

    /** ms of the last complete frame of the current link session; 0 = nothing received yet. */
    @Volatile private var lastFrameAt = 0L

    /** ms the current link was opened, so "silent" is measured per session. */
    @Volatile private var linkOpenedAt = 0L

    /** Set when the io runner died or the device was unplugged; the loop does the teardown. */
    @Volatile private var pendingTeardown = false

    private var source: TpmsUsbSource? = null
    private var monitorJob: Job? = null

    private var cardVisible = false
    private var autoConnect = true

    /** True after the card's connect action or a plug-in event; survives card re-adds. */
    private var manualWanted = false

    val snapshot: StateFlow<TpmsSnapshot> =
        combine(_status, _readings, _detail, _deviceName) { status, readings, detail, name ->
            TpmsSnapshot(
                status      = status,
                readings    = readings,
                lastFrameAt = lastFrameAt,
                deviceName  = name,
                detail      = detail
            )
        }.stateIn(scope, SharingStarted.Eagerly, TpmsSnapshot())

    init {
        registerUsbDeviceReceiver()
    }

    // ── configuration from settings ───────────────────────────────────────────

    /**
     * Single entry point for settings-driven state; idempotent so a settings write can't
     * thrash an established link.
     *
     * `autoConnect = false` means the link only starts from the card's CONNECT action
     * ([requestConnect]); with the card on the grid and autoConnect on, the launcher brings
     * the receiver up by itself, including after a replug.
     */
    fun configure(cardVisible: Boolean, autoConnect: Boolean) {
        if (this.cardVisible == cardVisible && this.autoConnect == autoConnect) return
        this.cardVisible = cardVisible
        this.autoConnect = autoConnect
        if (!cardVisible) manualWanted = false
        evaluateRunIntent()
    }

    /**
     * Starts (or restarts) the link on purpose — the card's CONNECT action and the USB
     * plug-in hook. This is what lets TPMS come up without visiting the settings screen.
     */
    fun requestConnect(reason: String) {
        Log.i(TAG, "TPMS connect requested ($reason); status=${_status.value}")
        manualWanted = true
        _detail.value = null
        evaluateRunIntent()
    }

    /**
     * Plug-in hook for the activity's `USB_DEVICE_ATTACHED` intent. Only arms the link when
     * the feature is already in use, so plugging in any other USB device (a mouse, a stick
     * of MP3s) can never raise a TPMS permission dialog on a launcher that never enabled it.
     */
    fun onPlugIn(reason: String) {
        if (!cardVisible && !manualWanted) {
            Log.d(TAG, "USB attach ignored — TPMS card not shown and no manual connect requested")
            return
        }
        requestConnect(reason)
    }

    /** Last transport failure for the card / logcat (null when healthy). */
    val detail: String? get() = _detail.value

    // ── transport lifecycle ───────────────────────────────────────────────────

    private fun evaluateRunIntent() {
        val shouldRun = manualWanted || (cardVisible && autoConnect)
        val running = monitorJob?.isActive == true
        when {
            shouldRun && !running -> startMonitor()
            !shouldRun && running -> stopMonitor()
        }
    }

    private fun startMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            var failures = 0
            while (isActive) {
                if (source?.isConnected != true) {
                    _status.value = CONNECTING
                    val outcome = withContext(Dispatchers.IO) {
                        connectMutex.withLock {
                            val src = source
                                ?: TpmsUsbSource(appContext, this@TpmsRepository).also { source = it }
                            src.connect()
                        }
                    }
                    when (outcome) {
                        ConnectOutcome.CONNECTED, ConnectOutcome.ALREADY_CONNECTED -> {
                            failures = 0
                            linkOpenedAt = System.currentTimeMillis()
                            pendingTeardown = false
                            _status.value = CONNECTED
                            _detail.value = null
                            _deviceName.value = source?.connectedDeviceName
                        }
                        else -> {
                            failures++
                            _status.value = ERROR
                            _detail.value = outcome.userReason()
                            _deviceName.value = null
                            Log.w(TAG, "connect failed: $outcome")
                        }
                    }
                } else if (pendingTeardown) {
                    withContext(Dispatchers.IO) {
                        connectMutex.withLock {
                            source?.disconnect()
                            pendingTeardown = false
                            lastFrameAt = 0L
                        }
                    }
                    if (_status.value != DISCONNECTED) _status.value = ERROR
                } else {
                    checkLinkHealth()
                }
                delay(if (source?.isConnected == true) LINK_POLL_MS else backoffFor(failures))
            }
        }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        scope.launch {
            withContext(Dispatchers.IO) {
                connectMutex.withLock {
                    source?.disconnect()
                    source = null
                    lastFrameAt = 0L
                    linkOpenedAt = 0L
                }
            }
            pendingTeardown = false
            _status.value = DISCONNECTED
            _deviceName.value = null
            _detail.value = null
        }
    }

    /**
     * Detects a link that is technically open but silent. Sensors burst roughly every 4 s
     * while driving, so a gap longer than [SILENCE_RECYCLE_MS] means the stick (or its RF
     * side) died — closing and reopening is the only recovery short of a reboot. A port that
     * opens but never speaks is almost certainly the wrong device or the wrong baud rate, and
     * that is worth naming on the card.
     */
    private fun checkLinkHealth() {
        val now = System.currentTimeMillis()
        val frameMark = if (lastFrameAt >= linkOpenedAt) lastFrameAt else 0L
        val sinceFrame = now - if (frameMark == 0L) linkOpenedAt else frameMark
        if (frameMark == 0L && sinceFrame > NO_DATA_WARN_MS) {
            _detail.value = "Receiver open, no data yet"
        }
        if (sinceFrame > SILENCE_RECYCLE_MS) {
            Log.w(TAG, "TPMS link silent for ${sinceFrame / 1000}s — recycling")
            pendingTeardown = true
            _detail.value = if (frameMark == 0L) "No data from receiver" else "Receiver silent"
        }
    }

    // ── TpmsUsbSource.Listener ────────────────────────────────────────────────

    override fun onReadings(readings: List<TireReading>) {
        if (readings.isEmpty()) return
        markFrameSeen()
        _readings.update { current ->
            val merged = current.toMutableMap()
            for (r in readings) merged[r.position] = r
            merged
        }
        if (_status.value != CONNECTED) _status.value = CONNECTED
    }

    /** Pairing / sensor-id style frames: proof the link is alive, no state to store yet. */
    override fun onUnknownFrame(frame: TpmsEvent.RawFrame) {
        markFrameSeen()
    }

    private fun markFrameSeen() {
        val now = System.currentTimeMillis()
        lastFrameAt = now
        if (linkOpenedAt == 0L) linkOpenedAt = now
    }

    override fun onTransportError(reason: String) {
        // Runs on the io runner thread: never disconnect() here, the loop does it.
        Log.w(TAG, "transport error: $reason")
        pendingTeardown = true
        _detail.value = "Receiver link dropped"
    }

    // ── hot-plug ──────────────────────────────────────────────────────────────

    private fun registerUsbDeviceReceiver() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = readUsbDevice(intent)
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (device != null && device.deviceName == source?.connectedDeviceName) {
                            Log.i(TAG, "TPMS receiver detached (${device.deviceName})")
                            pendingTeardown = true
                            _status.value = DISCONNECTED
                            _deviceName.value = null
                            _detail.value = "Receiver unplugged"
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        // Only react while the feature is in use: plugging in any other USB
                        // device must not raise a permission dialog for a feature the user
                        // never turned on.
                        if (device == null || (!cardVisible && !manualWanted)) return
                        Log.i(TAG, "USB device attached: ${device.deviceName} " +
                            "VID:PID=%04x:%04x".format(device.vendorId, device.productId))
                        // Give the kernel time to bind the FTDI/CH340 driver, otherwise
                        // findAllDrivers() still sees nothing.
                        scope.launch {
                            delay(ATTACH_SETTLE_MS)
                            requestConnect("device attached")
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Protected system broadcasts: NOT_EXPORTED is both allowed and correct here.
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
    }

    @Suppress("DEPRECATION")
    private fun readUsbDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    companion object {
        private const val TAG = "TpmsUsb"

        private const val LINK_POLL_MS = 2_000L
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L

        /** Far more than the ~4 s sensor burst interval. */
        private const val SILENCE_RECYCLE_MS = 120_000L

        /** A port that opens but never speaks is the wrong device or the wrong baud rate. */
        private const val NO_DATA_WARN_MS = 20_000L

        private const val ATTACH_SETTLE_MS = 1_000L

        @Volatile private var instance: TpmsRepository? = null

        /** Single shared instance — the serial port may only be opened once. */
        fun get(context: Context): TpmsRepository =
            instance ?: synchronized(this) {
                instance ?: TpmsRepository(context).also { instance = it }
            }

        private fun backoffFor(failures: Int): Long =
            if (failures <= 0) LINK_POLL_MS
            else minOf(RETRY_BASE_MS shl (failures - 1).coerceAtMost(6), RETRY_MAX_MS)
    }
}
