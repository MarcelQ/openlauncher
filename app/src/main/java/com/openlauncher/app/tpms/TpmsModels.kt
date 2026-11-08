package com.openlauncher.app.tpms

import java.util.Locale

/**
 * Wheel position as addressed by the USB TPMS receiver.
 *
 * [rawCode] is the wheel id byte carried in a sensor frame. 0x00/0x01 are the two
 * front wheels and 0x10/0x11 the two rear wheels; the low nibble carries the side
 * and the high nibble the axle, which is why the rear ids are 16/17 rather than 2/3.
 * 0x05 is the spare wheel, which some head units fit with a fifth sensor.
 */
enum class TirePosition(val rawCode: Int, val label: String, val shortLabel: String) {
    FRONT_LEFT(0x00, "Front left",  "FL"),
    FRONT_RIGHT(0x01, "Front right", "FR"),
    REAR_LEFT(0x10, "Rear left",   "RL"),
    REAR_RIGHT(0x11, "Rear right",  "RR"),
    SPARE(0x05, "Spare",       "SP");

    companion object {
        private val BY_RAW: Map<Int, TirePosition> = entries.associateBy { it.rawCode }

        fun fromRawCode(code: Int): TirePosition? = BY_RAW[code]

        /** The four road wheels, in the widget's FL/FR over RL/RR layout order. */
        val FOUR_WHEELS = listOf(FRONT_LEFT, FRONT_RIGHT, REAR_LEFT, REAR_RIGHT)
    }
}

/**
 * One sensor report for one wheel.
 *
 * [pressureKpa] and [temperatureCelsius] are always SI — the display layer converts
 * to bar/psi through [PressureUnit] so stored values never have to be re-interpreted
 * when the user changes units.
 *
 * [batteryPercent] is null when the protocol frame carries no battery field, which is
 * the case for the 8-byte sensor frames of a USB receiver: the sensor transmits its
 * battery state only in its radio burst, and the receiver does not forward it over the
 * serial link. The widget then renders "—" instead of inventing a value.
 *
 * [statusBits] keeps the raw status byte so a later capture can reveal what the other
 * bits mean without another round of hardware testing.
 */
data class TireReading(
    val position: TirePosition,
    val pressureKpa: Float,
    val temperatureCelsius: Float,
    val batteryPercent: Int? = null,
    val hasAlarm: Boolean = false,
    val noSignal: Boolean = false,
    val statusBits: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * True when the reading is older than [maxAgeMs].
     *
     * 120 s is not arbitrary: the receiver vendor's own app raises its "receiver
     * disconnected" warning after 120 s without a frame, and sensors burst every ~4 s
     * while driving, so 120 s of silence means the link (or the sensor) is gone.
     */
    fun isStale(nowMs: Long = System.currentTimeMillis(), maxAgeMs: Long = STALE_AFTER_MS): Boolean =
        nowMs - timestamp > maxAgeMs

    fun ageSeconds(nowMs: Long = System.currentTimeMillis()): Long =
        ((nowMs - timestamp) / 1000L).coerceAtLeast(0L)

    companion object {
        const val STALE_AFTER_MS = 120_000L
    }
}

/** USB TPMS receiver link state. */
enum class TpmsConnection {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR;

    val isActive: Boolean get() = this == CONNECTED || this == CONNECTING
}

/**
 * Pressure display unit. Values are stored as kPa everywhere internally
 * (see [TireReading.pressureKpa]); the sensor's own scaling is documented in
 * [TpmsFrameDecoder], so 100 kPa is exactly 1 bar.
 */
enum class PressureUnit(val label: String) {
    BAR("bar"),
    KPA("kPa"),
    PSI("psi");

    fun fromKpa(kpa: Float): Float = when (this) {
        BAR -> kpa / 100f
        KPA -> kpa
        PSI -> kpa * PSI_PER_KPA
    }

    /** Inverse of [fromKpa] — used by the settings slider that edits the alarm threshold. */
    fun toKpa(value: Float): Float = when (this) {
        BAR -> value * 100f
        KPA -> value
        PSI -> value / PSI_PER_KPA
    }

    /** Fixed-point rendering. Locale.US keeps the decimal point stable on EU head units. */
    fun format(kpa: Float): String =
        String.format(Locale.US, "%.${decimals()}f", fromKpa(kpa))

    fun formatWithUnit(kpa: Float): String = "${format(kpa)} $label"

    private fun decimals(): Int = when (this) {
        BAR -> 1
        PSI -> 1
        KPA -> 0
    }

    companion object {
        const val PSI_PER_KPA = 0.145038f

        /** Alarm threshold bounds, stored as kPa regardless of the display unit. */
        const val MIN_THRESHOLD_KPA = 100f
        const val MAX_THRESHOLD_KPA = 400f
    }
}

/**
 * Aggregated snapshot handed to the UI layer.
 *
 * [hasAnyReading] distinguishes "no sensor has ever reported" from "sensors reported
 * but are now stale", which the card renders differently (connect prompt vs. dimmed
 * last-known values).
 */
data class TpmsSnapshot(
    val status: TpmsConnection = TpmsConnection.DISCONNECTED,
    val readings: Map<TirePosition, TireReading> = emptyMap(),
    val lastFrameAt: Long = 0L,
    val deviceName: String? = null,
    /** Null while healthy, otherwise a short human-readable transport failure for the card. */
    val detail: String? = null
) {
    val hasAnyReading: Boolean get() = readings.isNotEmpty()

    fun reading(pos: TirePosition): TireReading? = readings[pos]

    /** Wheels whose pressure is below [lowPressureKpa], ignoring sensors with no data. */
    fun lowPressureWheels(lowPressureKpa: Float, nowMs: Long = System.currentTimeMillis()): List<TirePosition> =
        readings.values
            .filter { !it.noSignal && it.pressureKpa > 0f && it.pressureKpa < lowPressureKpa }
            .sortedBy { it.position.rawCode }
            .map { it.position }
}
