package com.openlauncher.app.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoder tests — the only part of TPMS that can be proven without the receiver.
 *
 * Frame fixtures follow the format documented on [TpmsFrameDecoder] (0x55 0xAA LEN …,
 * XOR checksum over bytes 0..LEN-2). The scaling expectations encode the current offset
 * assumption and will need revisiting once readings are compared against a gauge.
 */
class TpmsFrameDecoderTest {

    private val fixedClock = { 1_700_000_000_000L }
    private fun decoder() = TpmsFrameDecoder(fixedClock)

    /** Builds a LEN-long frame: 55 AA LEN payload…, xor checksum — LEN counts all bytes. */
    private fun frame(payload: ByteArray): ByteArray {
        val len = payload.size + 4
        val out = ByteArray(len)
        out[0] = 0x55
        out[1] = 0xAA.toByte()
        out[2] = len.toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        var xor = 0
        for (i in 0 until len - 1) xor = xor xor (out[i].toInt() and 0xFF)
        out[len - 1] = xor.toByte()
        return out
    }

    /** wheel id, pressure raw, temp raw, status */
    private fun tireFrame(wheel: Int, pressure: Int, temp: Int, status: Int = 0) =
        frame(byteArrayOf(wheel.toByte(), pressure.toByte(), temp.toByte(), status.toByte()))

    private fun updates(events: List<TpmsEvent>) = events.filterIsInstance<TpmsEvent.TireUpdate>()

    @Test
    fun decodesSingleTyreReport() {
        val events = decoder().feed(tireFrame(0x00, 75, 85))
        val reading = updates(events).single().reading

        assertEquals(TirePosition.FRONT_LEFT, reading.position)
        // 75 * 3.44 - 35 = 223 kPa = 2.23 bar
        assertEquals(223f, reading.pressureKpa, 0.01f)
        assertEquals(35f, reading.temperatureCelsius, 0f)
        assertEquals(1_700_000_000_000L, reading.timestamp)
        assertEquals(null, reading.batteryPercent)
        assertEquals(0, reading.statusBits)
    }

    @Test
    fun mapsEveryWheelId() {
        val expected = mapOf(
            0x00 to TirePosition.FRONT_LEFT,
            0x01 to TirePosition.FRONT_RIGHT,
            0x10 to TirePosition.REAR_LEFT,
            0x11 to TirePosition.REAR_RIGHT,
            0x05 to TirePosition.SPARE
        )
        for ((id, pos) in expected) {
            val reading = updates(decoder().feed(tireFrame(id, 70, 60))).single().reading
            assertEquals(pos, reading.position)
        }
    }

    @Test
    fun keepsTrailingPartialFrameForNextRead() {
        val full = tireFrame(0x01, 72, 66)
        val d = decoder()

        val first = d.feed(full.copyOfRange(0, 5))
        assertTrue("partial frame must not decode", first.isEmpty())
        assertEquals(5, d.bufferedBytes)

        val second = d.feed(full.copyOfRange(5, full.size))
        assertEquals(1, updates(second).size)
        assertEquals(0, d.bufferedBytes)
    }

    @Test
    fun decodesTwoFramesFromOneRead() {
        val both = tireFrame(0x00, 70, 60) + tireFrame(0x11, 80, 70)
        val readings = updates(decoder().feed(both)).map { it.reading }
        assertEquals(2, readings.size)
        assertEquals(TirePosition.FRONT_LEFT, readings[0].position)
        assertEquals(TirePosition.REAR_RIGHT, readings[1].position)
        assertEquals(0, decoder().stats.resyncs)
    }

    @Test
    fun resynchronisesAfterGarbageBytes() {
        val noisy = byteArrayOf(0x00, 0x55, 0x11, 0x55.toByte()) + tireFrame(0x01, 74, 62)
        val events = decoder().feed(noisy)
        assertEquals(1, updates(events).size)
        assertEquals(TirePosition.FRONT_RIGHT, updates(events).single().reading.position)
    }

    @Test
    fun dropsFrameWithBadChecksum() {
        val broken = tireFrame(0x00, 70, 60).also {
            it[it.size - 1] = (it.last().toInt() xor 0x7F).toByte()
        }
        val d = decoder()
        assertTrue(d.feed(broken).isEmpty())
        assertEquals(1, d.stats.checksumErrors)
        assertEquals(0, d.stats.framesOk)
    }

    @Test
    fun rejectsInvalidLengthAndKeepsScanning() {
        // sync + LEN 0 (vendor code guards this exact case) then a good frame
        val bad = byteArrayOf(0x55, 0xAA.toByte(), 0x00, 0x00)
        val d = decoder()
        val events = d.feed(bad + tireFrame(0x10, 69, 55))
        assertEquals(1, updates(events).size)
        assertTrue(d.stats.invalidLengths >= 1)
    }

    @Test
    fun surfacesUnknownLengthAsRawFrame() {
        // LEN 6 = pairing / heartbeat style message: framed, checksummed, not interpreted
        val raw = decoder().feed(frame(byteArrayOf(0x18, 0x01)))
        val frameEvent = raw.single() as TpmsEvent.RawFrame
        assertEquals(6, frameEvent.length)
        assertEquals(0x55, frameEvent.bytes[0].toInt())
    }

    @Test
    fun ignoresFramesForUnknownWheelIds() {
        val events = decoder().feed(tireFrame(0x07, 70, 60))
        assertTrue(updates(events).isEmpty())
        assertEquals(1, events.filterIsInstance<TpmsEvent.RawFrame>().size)
    }

    @Test
    fun exposesStatusBitsAndNeverInventsBattery() {
        val reading = updates(decoder().feed(tireFrame(0x01, 70, 60, 0x28))).single().reading
        assertTrue(reading.noSignal)   // 0x20
        assertTrue(reading.hasAlarm)   // 0x08
        assertEquals(0x28, reading.statusBits)
        assertEquals(null, reading.batteryPercent)
    }

    @Test
    fun clampsPressureAtZero() {
        val reading = updates(decoder().feed(tireFrame(0x00, 2, 40))).single().reading
        assertEquals(0f, reading.pressureKpa, 0f)
    }

    @Test
    fun pressureOffsetIsAppliedFromOnePlace() {
        val d = decoder()
        d.pressureOffsetKpa = 15f
        // With the third-party launcher's offset the same raw byte reads 0.2 bar higher
        assertEquals(243f, updates(d.feed(tireFrame(0x00, 75, 85))).single().reading.pressureKpa, 0.01f)
    }

    @Test
    fun unitRenderingMatchesBarConvention() {
        // raw 80 -> 80 * 3.44 - 35 = 240.2 kPa
        val reading = updates(decoder().feed(tireFrame(0x00, 80, 60))).single().reading
        assertEquals(240.2f, reading.pressureKpa, 0.01f)
        assertEquals("2.4", PressureUnit.BAR.format(reading.pressureKpa))
        assertEquals("240", PressureUnit.KPA.format(reading.pressureKpa))
        assertEquals("34.8", PressureUnit.PSI.format(reading.pressureKpa))
        assertEquals("2.4 bar", PressureUnit.BAR.formatWithUnit(reading.pressureKpa))
    }

    @Test
    fun thresholdRoundTripsThroughEveryUnit() {
        // The settings slider edits in the display unit but stores kPa; a unit switch must
        // not move the alarm point.
        for (unit in PressureUnit.entries) {
            assertEquals(200f, unit.toKpa(unit.fromKpa(200f)), 0.01f)
        }
        assertEquals("2.0", PressureUnit.BAR.format(200f))
        assertEquals(200f, PressureUnit.BAR.toKpa(2.0f), 0.001f)
        assertEquals(200f, PressureUnit.PSI.toKpa(PressureUnit.PSI.fromKpa(200f)), 0.01f)
    }

    @Test
    fun foreignGarbageNeverGrowsTheBufferUnbounded() {
        val d = decoder()
        // NMEA-style ASCII spam from a GPS or MCU bridge: never framed, must not accumulate.
        val garbage = ByteArray(400) { (0x30 + it % 0x40).toByte() }
        repeat(3) { d.feed(garbage) }
        val afterThree = d.bufferedBytes
        repeat(3) { d.feed(garbage) }
        assertTrue("at most a partial sync may linger", d.bufferedBytes < TpmsFrameDecoder.HEADER_BYTES)
        assertEquals("unframed bytes must not accumulate across reads", afterThree, d.bufferedBytes)
        assertEquals(0, d.stats.overflows)
        assertTrue("garbage has to be resynchronised", d.stats.resyncs > 0)
        // A good frame after the storm still decodes
        assertEquals(1, updates(d.feed(tireFrame(0x00, 70, 60))).size)
    }

    @Test
    fun readBiggerThanTheCapKeepsNewestBytesInsteadOfThrowing() {
        val d = decoder()
        // The io manager hands over its whole read buffer (4 KiB). Copying that into a
        // 512-byte framed buffer used to overflow the array and kill the reader thread.
        d.feed(byteArrayOf(0x55, 0xAA.toByte(), 0x20))  // half a LEN-32 frame, kept
        d.feed(ByteArray(700) { 0x22 })                // flood larger than the cap
        assertTrue(d.bufferedBytes <= TpmsFrameDecoder.MAX_BUFFERED_BYTES)
        assertTrue(d.stats.overflows >= 1)
        assertEquals(1, updates(d.feed(tireFrame(0x00, 70, 60))).size)
    }

    @Test
    fun resetDropsHalfFrameSoAReplugCannotMixDevices() {
        val d = decoder()
        d.feed(tireFrame(0x00, 70, 60).copyOfRange(0, 6))
        d.reset()
        assertEquals(0, d.bufferedBytes)
        assertTrue(d.feed(byteArrayOf(0x11, 0x22)).isEmpty())
    }
}
