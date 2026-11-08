package com.openlauncher.app.tpms

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.HexDump
import com.hoho.android.usbserial.util.SerialInputOutputManager
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * USB host transport for the TPMS receiver.
 *
 * Owns exactly one open serial port and the [TpmsFrameDecoder] fed by it. Deliberately
 * read-only: the receiver also accepts pairing / wheel-swap / time-seed commands, but those
 * change what the receiver has learned, so they are left unimplemented until the framing has
 * been verified against a real unit.
 *
 * Depends on the MIT-licensed `com.github.mik3y:usb-serial-for-android` library for the
 * chip-specific drivers (FTDI / CH34x / CP210x / CDC-ACM); this class only does discovery,
 * permission, and lifecycle.
 */
class TpmsUsbSource(
    context: Context,
    private val listener: Listener
) {

    interface Listener {
        /** Tyre reports decoded from one read burst (usually one wheel, sometimes several). */
        fun onReadings(readings: List<TireReading>)

        /** Valid frame whose payload is not interpreted yet (pairing / sensor-id messages). */
        fun onUnknownFrame(frame: TpmsEvent.RawFrame)

        /** Link died: read error, device gone, or driver exception. */
        fun onTransportError(reason: String)
    }

    private val appContext = context.applicationContext
    private val usbManager: UsbManager? =
        appContext.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val decoder = TpmsFrameDecoder()

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var ioManager: SerialInputOutputManager? = null
    private var claimKey: String? = null

    /** Name of the currently open device (`/dev/bus/usb/001/002`), null when closed. */
    val connectedDeviceName: String? get() = port?.device?.deviceName

    /** Decoder diagnostics, for the VITALS card / logcat. */
    val stats: TpmsFrameDecoder.Stats get() = decoder.stats

    val isConnected: Boolean get() = ioManager != null

    /**
     * Finds a receiver, obtains USB permission if needed, and starts reading.
     * Never writes to the device. Callers must serialise with [disconnect].
     */
    suspend fun connect(): ConnectOutcome {
        val manager = usbManager ?: return ConnectOutcome.NO_USB_SERVICE
        if (isConnected) return ConnectOutcome.ALREADY_CONNECTED

        val drivers = runCatching { UsbSerialProber.getDefaultProber().findAllDrivers(manager) }
            .getOrElse {
                Log.e(TAG, "USB enumeration failed", it)
                return ConnectOutcome.OPEN_FAILED
            }

        if (drivers.isEmpty()) {
            Log.w(TAG, "No USB serial device found. Attached devices: " +
                manager.deviceList.values.joinToString { describe(it) }.ifEmpty { "none" })
            return ConnectOutcome.NO_DEVICE
        }

        val candidates = drivers
            .filter { !TpmsDeviceRegistry.claimedByOther(it.device.deviceName, FEATURE) }
            .sortedByDescending { preferredRank(it.device) }
        if (candidates.isEmpty()) {
            Log.w(TAG, "Every serial device is claimed by another launcher feature")
            return ConnectOutcome.IN_USE_BY_OTHER_FEATURE
        }

        Log.i(TAG, "TPMS candidates: " + candidates.joinToString { describe(it.device) })

        // Devices the user already approved (e.g. by plugging the stick in while the
        // launcher was running) skip the permission dialog entirely.
        for (driver in candidates.filter { manager.hasPermission(it.device) }) {
            if (openDriver(manager, driver)) return ConnectOutcome.CONNECTED
        }

        val target = candidates.first()
        Log.i(TAG, "Requesting USB permission for ${describe(target.device)}")
        // The dialog can sit unanswered indefinitely (or be dismissed with no result
        // broadcast), so the wait is bounded and the retry loop stays alive.
        val granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
            requestPermission(manager, target.device)
        } ?: false
        if (!granted) {
            Log.w(TAG, "USB permission denied / not answered for ${target.device.deviceName}")
            return ConnectOutcome.NO_PERMISSION
        }
        return if (openDriver(manager, target)) ConnectOutcome.CONNECTED else ConnectOutcome.OPEN_FAILED
    }

    /** Stops the reader thread, closes the port and releases the device claim. */
    fun disconnect() {
        val p = port
        ioManager?.let { io ->
            runCatching { io.stop() }
                .onFailure { Log.w(TAG, "io manager stop failed", it) }
        }
        ioManager = null
        port = null
        connection?.let { runCatching { it.close() } }
        connection = null
        runCatching { p?.close() }.onFailure { Log.d(TAG, "port already closed") }
        decoder.reset()
        claimKey?.let { TpmsDeviceRegistry.release(it, FEATURE) }
        claimKey = null
        decoder.unknownFrameLogged = false
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun openDriver(manager: UsbManager, driver: UsbSerialDriver): Boolean {
        val device = driver.device
        val ports = driver.ports
        if (ports.isEmpty()) {
            Log.w(TAG, "${describe(device)} exposes no ports")
            return false
        }
        if (!TpmsDeviceRegistry.claim(device.deviceName, FEATURE)) {
            Log.w(TAG, "${device.deviceName} claimed by another feature during open")
            return false
        }
        val conn = try {
            manager.openDevice(device)
        } catch (e: SecurityException) {
            // Permission dialog answered "deny" between enumeration and open
            Log.w(TAG, "openDevice refused for ${device.deviceName}", e)
            TpmsDeviceRegistry.release(device.deviceName, FEATURE)
            return false
        }
        if (conn == null) {
            Log.e(TAG, "openDevice returned null for ${describe(device)}")
            TpmsDeviceRegistry.release(device.deviceName, FEATURE)
            return false
        }

        // Receiver sticks are single-port; multi-port CDC/Ftdi devices are tried in order.
        var opened: UsbSerialPort? = null
        for (candidate in ports) {
            try {
                candidate.open(conn)
                candidate.setParameters(
                    BAUD_RATE,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                opened = candidate
                break
            } catch (e: Exception) {
                Log.w(TAG, "cannot use ${driver.javaClass.simpleName} port ${candidate.portNumber}", e)
                runCatching { candidate.close() }
            }
        }
        val serialPort = opened ?: run {
            runCatching { conn.close() }
            TpmsDeviceRegistry.release(device.deviceName, FEATURE)
            return false
        }

        port = serialPort
        connection = conn
        claimKey = device.deviceName
        decoder.reset()

        val io = SerialInputOutputManager(serialPort, ioListener).apply {
            // Bounded read so stop() can't park on a stuck CDC endpoint forever.
            setReadTimeout(READ_TIMEOUT_MS)
            setReadBufferSize(READ_BUFFER_BYTES)
        }
        ioManager = io
        io.start()

        // getSerial() is optional per driver and may throw UnsupportedOperationException,
        // so it is only ever diagnostic.
        val serial = runCatching { serialPort.serial }.getOrNull().orEmpty()
        val linkSummary = "TPMS link up: ${describe(device)} via ${driver.javaClass.simpleName} " +
            "port ${serialPort.portNumber} @ $BAUD_RATE 8N1"
        Log.i(TAG, if (serial.isEmpty()) linkSummary else "$linkSummary serial=$serial")
        return true
    }

    private val ioListener = object : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            // Runs on the io manager's read thread only, which is what keeps the decoder
            // single-threaded by contract.
            val events = decoder.feed(data)
            if (events.isEmpty() && logRx(data)) {
                Log.d(TAG, "rx ${data.size}B (no complete frame): ${HexDump.dumpHexString(data)}")
            }
            val readings = events.filterIsInstance<TpmsEvent.TireUpdate>().map { it.reading }
            if (readings.isNotEmpty()) listener.onReadings(readings)
            events.filterIsInstance<TpmsEvent.RawFrame>().forEach { frame ->
                if (!decoder.unknownFrameLogged) {
                    decoder.unknownFrameLogged = true
                    Log.i(TAG, "unhandled TPMS frame len=${frame.length}: " +
                        HexDump.dumpHexString(frame.bytes))
                }
                listener.onUnknownFrame(frame)
            }
        }

        override fun onRunError(e: Exception) {
            Log.e(TAG, "TPMS io runner stopped: ${e.message}", e)
            listener.onTransportError(e.message ?: e.javaClass.simpleName)
        }
    }

    private var rxLogCount = 0

    /** Rate-limits raw hex so a chatty foreign device can't flood logcat. */
    private fun logRx(data: ByteArray): Boolean {
        if (rxLogCount >= RAW_RX_LOG_LIMIT) return false
        rxLogCount++
        return true
    }

    private suspend fun requestPermission(manager: UsbManager, device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val action = "$PERMISSION_ACTION_PREFIX.${appContext.packageName}"
            val intent = Intent(action).setPackage(appContext.packageName)
            val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getBroadcast(appContext, 0, intent, piFlags)

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, it: Intent) {
                    runCatching { appContext.unregisterReceiver(this) }
                    val granted = it.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // The broadcast is sent by the system, so the receiver has to be exported.
                // PendingIntent already pins it to our package.
                appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }
            try {
                manager.requestPermission(device, pending)
            } catch (e: Exception) {
                runCatching { appContext.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resume(false)
                Log.e(TAG, "requestPermission failed", e)
            }
        }

    private fun describe(device: UsbDevice): String =
        "${device.deviceName} VID:PID=%04x:%04x perm=%b ports=%b".format(
            device.vendorId,
            device.productId,
            usbManager?.hasPermission(device) == true,
            UsbSerialProber.getDefaultProber().probeDevice(device)?.ports?.size ?: 0
        )

    /**
     * Ranking for the "which serial device is the TPMS stick" decision. The receiver family
     * enumerates as an FTDI FT232R/FT231X or a Qinheng CH340; the vendor app whitelists those
     * three id pairs and nothing else. Head units expose other CDC devices (MCU bridges,
     * internal modems), so a known-good id is preferred over "whatever appeared first".
     */
    private fun preferredRank(device: UsbDevice): Int {
        val id = device.vendorId to device.productId
        return when (id) {
            0x0403 to 0x6001, 0x0403 to 0x6015, 0x1A86 to 0x7523 -> 3
            else -> if (UsbSerialProber.getDefaultProber().probeDevice(device) != null) 2 else 1
        }
    }

    companion object {
        private const val TAG = "TpmsUsb"

        /** Registry key for this feature, mirrors how the device claim table is keyed. */
        const val FEATURE = "tpms"

        /**
         * The receiver talks 19200 8N1. Its vendor app uses the same value, and no
         * baud-rate probing is needed for this stick.
         */
        const val BAUD_RATE = 19200

        private const val READ_TIMEOUT_MS = 1500
        private const val READ_BUFFER_BYTES = 4096
        private const val RAW_RX_LOG_LIMIT = 8

        /** How long to wait for the user to answer the USB permission dialog. */
        private const val PERMISSION_TIMEOUT_MS = 60_000L

        /** Custom action prefix for the USB permission callback. */
        const val PERMISSION_ACTION_PREFIX = "com.openlauncher.app.tpms.USB_PERMISSION"
    }
}

enum class ConnectOutcome {
    CONNECTED,
    ALREADY_CONNECTED,
    NO_DEVICE,
    NO_PERMISSION,
    IN_USE_BY_OTHER_FEATURE,
    NO_USB_SERVICE,
    OPEN_FAILED;

    /** Short text safe to show on the widget. */
    fun userReason(): String = when (this) {
        CONNECTED, ALREADY_CONNECTED -> ""
        NO_DEVICE -> "No USB TPMS receiver found"
        NO_PERMISSION -> "USB permission denied"
        IN_USE_BY_OTHER_FEATURE -> "Receiver in use by another app"
        NO_USB_SERVICE -> "No USB host on this unit"
        OPEN_FAILED -> "Could not open receiver"
    }
}

/**
 * Process-wide "which feature owns which USB device" table.
 *
 * A head unit can expose several serial devices at once (receiver stick, OBD dongle, MCU
 * bridge). Without this, two launcher features enumerating the same stick would each claim a
 * port and the reader thread of one would silently starve the other.
 */
object TpmsDeviceRegistry {

    private val claims = ConcurrentHashMap<String, String>()

    /** True when [feature] may take the device (unclaimed or already ours). */
    fun claim(deviceName: String, feature: String): Boolean {
        val previous = claims.putIfAbsent(deviceName, feature)
        return previous == null || previous == feature
    }

    fun release(deviceName: String, feature: String) {
        claims.remove(deviceName, feature)
    }

    fun claimedByOther(deviceName: String, feature: String): Boolean =
        claims[deviceName]?.let { it != feature } == true

    fun owner(deviceName: String): String? = claims[deviceName]

    fun snapshot(): Map<String, String> = claims.toMap()
}
