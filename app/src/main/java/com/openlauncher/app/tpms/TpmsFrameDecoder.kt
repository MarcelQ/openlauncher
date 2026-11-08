package com.openlauncher.app.tpms

/**
 * Streaming framer + message decoder for the USB TPMS receiver ("v3" link protocol).
 *
 * Pure Kotlin — no Android imports — so it can be unit tested on the JVM without hardware.
 *
 * ## Wire format (observed, see caveats)
 *
 * ```
 * index 0  0x55          preamble
 * index 1  0xAA          sync
 * index 2  LEN           total frame length, INCLUDING these three bytes
 * index 3  LEN-1         XOR checksum of bytes 0 .. LEN-2
 * ```
 * Messages are dispatched on LEN: this protocol family gives each message type a fixed
 * length, so LEN doubles as the command discriminator. Anything outside
 * [MIN_FRAME_LEN]..[MAX_FRAME_LEN] or failing the checksum is treated as a desync and the
 * buffer is resynchronised by skipping a single byte.
 *
 * LEN == 8 is the tyre report the sensors burst every ~4 s:
 *
 * ```
 * [3] wheel id      0x00 FL, 0x01 FR, 0x10 RL, 0x11 RR, 0x05 spare
 *                   (low nibble = side, high nibble = axle)
 * [4] pressure raw  kPa = raw * 3.44 - PRESSURE_OFFSET_KPA   (3.44 kPa = 0.5 psi)
 * [5] temperature   degC = raw - 50
 * [6] status bits   0x20 = sensor signal lost, 0x08 = pressure falling (leak)
 * [7] checksum
 * ```
 *
 * LEN 6/7/9 carry the receiver's pairing / sensor-ID / wheel-swap messages. Those are only
 * needed to (re)learn sensor IDs, which this app does not do yet, so they are surfaced as
 * [TpmsEvent.RawFrame] instead of being dropped silently.
 *
 * ## Caveat — the pressure offset
 *
 * The scale factor and the status bits agree across every source examined, but the pressure
 * offset does not: the receiver vendor's own app subtracts 35 kPa (two steps, 15 + 20) while
 * a third-party launcher integration subtracts 15 kPa — a 0.2 bar difference, which matters.
 * It is therefore kept in one place ([TpmsFrameDecoder.pressureOffsetKpa]) and must be
 * confirmed against a known-good gauge on the real receiver before any TPMS reading here is
 * trusted. Everything downstream consumes kPa, so the offset never leaks into settings.
 */
class TpmsFrameDecoder(
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Diagnostics counters, safe to read at any time (decoder is single-threaded by contract). */
    class Stats {
        var framesOk = 0
        var checksumErrors = 0
        var invalidLengths = 0
        var resyncs = 0
        var overflows = 0
    }

    val stats = Stats()

    /** See class KDoc "Caveat". Change only after verifying against a pressure gauge. */
    var pressureOffsetKpa: Float = 35f

    /** Frames whose checksum is valid but whose payload is not interpreted. */
    var unknownFrameLogged = false

    private var buf = ByteArray(INITIAL_CAPACITY)
    private var size = 0

    /** Number of bytes currently held (partial frame carry-over). Useful for tests/diagnostics. */
    val bufferedBytes: Int get() = size

    /**
     * Appends [size] bytes of [data] and returns every message that became complete.
     * A trailing partial frame is kept for the next call.
     */
    fun feed(data: ByteArray, size: Int = data.size): List<TpmsEvent> {
        if (size <= 0) return emptyList()
        append(data, size)

        val out = ArrayList<TpmsEvent>()
        var i = 0
        while (true) {
            val remaining = this.size - i
            if (remaining < HEADER_BYTES) break

            if (buf[i] != PREAMBLE || buf[i + 1] != SYNC) {
                i++
                stats.resyncs++
                continue
            }

            val len = buf[i + 2].toInt() and 0xFF
            if (len < MIN_FRAME_LEN || len > MAX_FRAME_LEN) {
                i++
                stats.invalidLengths++
                continue
            }
            if (remaining < len) break // wait for the rest of this frame

            if (buf[i + len - 1] != xorOf(i, len - 1)) {
                i++
                stats.checksumErrors++
                continue
            }

            out += decodeAt(i, len)
            stats.framesOk++
            i += len
        }
        consume(i)
        return out
    }

    /** Drops all buffered bytes, e.g. after an unplug so a half frame can't leak into the next device. */
    fun reset() {
        size = 0
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private fun decodeAt(offset: Int, len: Int): TpmsEvent {
        if (len == TIRE_REPORT_LEN) {
            val wheel = buf[offset + 3].toInt() and 0xFF
            val pos   = TirePosition.fromRawCode(wheel)
            if (pos != null) {
                val rawPressure = (buf[offset + 4].toInt() and 0xFF).toFloat()
                val kpa = (rawPressure * PRESSURE_KPA_PER_STEP - pressureOffsetKpa)
                    .coerceAtLeast(0f)
                val degC = (buf[offset + 5].toInt() and 0xFF) - 50
                val status = buf[offset + 6].toInt() and 0xFF
                return TpmsEvent.TireUpdate(
                    TireReading(
                        position         = pos,
                        pressureKpa      = kpa,
                        temperatureCelsius = degC.toFloat(),
                        // The 8-byte serial frame carries no sensor battery field; the sensor
                        // reports its battery only in its radio burst. Never invent a value.
                        batteryPercent   = null,
                        hasAlarm         = (status and STATUS_LEAK) != 0,
                        noSignal         = (status and STATUS_NO_SIGNAL) != 0,
                        statusBits       = status,
                        timestamp        = clock()
                    )
                )
            }
        }
        return TpmsEvent.RawFrame(len, buf.copyOfRange(offset, offset + len))
    }

    private fun xorOf(offset: Int, count: Int): Byte {
        var acc = 0
        for (k in 0 until count) acc = acc xor (buf[offset + k].toInt() and 0xFF)
        return acc.toByte()
    }

    private fun append(data: ByteArray, count: Int) {
        var src = 0
        var n = count
        if (n > MAX_BUFFERED_BYTES) {
            // One read hands over the whole io-manager buffer (4 KiB). Keep the newest tail,
            // which is where a live device's next frame starts, instead of copying past the
            // array and throwing inside the reader thread.
            stats.overflows++
            src = n - MAX_BUFFERED_BYTES
            n = MAX_BUFFERED_BYTES
        }
        if (size + n > MAX_BUFFERED_BYTES) {
            // Stuck on garbage (or a device that is not a TPMS receiver): drop the oldest
            // bytes, never the newest — a frame torn by the drop is resynchronised by the scan.
            stats.overflows++
            val keep = MAX_BUFFERED_BYTES - n
            if (keep <= 0) {
                size = 0
            } else {
                System.arraycopy(buf, size - keep, buf, 0, keep)
                size = keep
            }
        }
        if (size + n > buf.size) {
            buf = buf.copyOf(maxOf(size + n, minOf(buf.size * 2, MAX_BUFFERED_BYTES)))
        }
        System.arraycopy(data, src, buf, size, n)
        size += n
    }

    /** Discards the first [count] bytes, keeping the unread tail in front of the buffer. */
    private fun consume(count: Int) {
        if (count <= 0) return
        val rest = size - count
        if (rest <= 0) {
            size = 0
            return
        }
        System.arraycopy(buf, count, buf, 0, rest)
        size = rest
    }

    companion object {
        const val PREAMBLE: Byte = 0x55
        const val SYNC: Byte     = 0xAA.toByte()
        const val HEADER_BYTES   = 3

        /** 4 is the smallest LEN the reference implementations accept; 32 is the largest seen. */
        const val MIN_FRAME_LEN  = 4
        const val MAX_FRAME_LEN  = 32

        /** Tyre report length; LEN doubles as the command id in this protocol family. */
        const val TIRE_REPORT_LEN = 8

        const val STATUS_NO_SIGNAL = 0x20
        const val STATUS_LEAK      = 0x08

        /** 0.5 psi expressed in kPa — the sensor's pressure step size. */
        const val PRESSURE_KPA_PER_STEP = 3.44f

        private const val INITIAL_CAPACITY = 64

        /**
         * Cap on buffered, not-yet-framed bytes. A healthy stream drains every read
         * (reports are ~4 s apart, 8 bytes each), so only a foreign protocol or a
         * stuck device reaches this.
         */
        const val MAX_BUFFERED_BYTES = 512
    }
}

/** One decoded protocol message. */
sealed class TpmsEvent {
    /** A tyre report (LEN 8) for one wheel. */
    data class TireUpdate(val reading: TireReading) : TpmsEvent()

    /**
     * A checksum-valid frame this build does not interpret (pairing ack, sensor-ID reply,
     * wheel-swap notice). Kept so a future sensor-ID learning flow can be built against
     * real captures.
     */
    data class RawFrame(val length: Int, val bytes: ByteArray) : TpmsEvent() {
        override fun equals(other: Any?): Boolean =
            this === other || (other is RawFrame && other.length == length &&
                other.bytes.contentEquals(bytes))

        override fun hashCode(): Int = 31 * length + bytes.contentHashCode()
    }
}
