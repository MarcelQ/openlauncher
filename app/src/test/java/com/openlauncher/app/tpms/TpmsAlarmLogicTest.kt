package com.openlauncher.app.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Low-pressure warning decisions — [PressureUnit]'s threshold bounds/step and
 * [TpmsSnapshot.alarmingWheels], the single source of truth the card, the settings
 * screen and the audible chime all read.
 *
 * No Android here: every call passes an explicit `nowMs` so staleness is deterministic.
 */
class TpmsAlarmLogicTest {

    private val now = 1_700_000_000_000L

    /** Fresh reading by default; `ageMs` moves it into stale territory. */
    private fun reading(
        pos: TirePosition,
        kpa: Float,
        ageMs: Long = 0L,
        hasAlarm: Boolean = false,
        noSignal: Boolean = false
    ) = TireReading(
        position      = pos,
        pressureKpa   = kpa,
        temperatureCelsius = 20f,
        hasAlarm      = hasAlarm,
        noSignal      = noSignal,
        timestamp     = now - ageMs
    )

    private fun snapshot(vararg readings: TireReading) = TpmsSnapshot(
        status      = TpmsConnection.CONNECTED,
        readings    = readings.associateBy { it.position },
        lastFrameAt = now
    )

    private fun alarming(vararg readings: TireReading): List<TirePosition> =
        snapshot(*readings).alarmingWheels(220f, alarmEnabled = true, nowMs = now)

    // ── Threshold bounds and slider detents ───────────────────────────────────

    @Test
    fun thresholdRangeIsWholeTenthsOfBar() {
        assertEquals(10f, PressureUnit.MIN_THRESHOLD_KPA, 0f)
        assertEquals(400f, PressureUnit.MAX_THRESHOLD_KPA, 0f)
        // One detent = 0.1 bar exactly, so slider stops land on clean bar values.
        assertEquals(10f, PressureUnit.THRESHOLD_STEP_KPA, 0f)
    }

    @Test
    fun everyDetentFormatsAsCleanBarValue() {
        val stops = ((PressureUnit.MAX_THRESHOLD_KPA - PressureUnit.MIN_THRESHOLD_KPA) /
            PressureUnit.THRESHOLD_STEP_KPA).toInt() + 1
        assertEquals(40, stops)

        val formatted = (0 until stops).map { i ->
            PressureUnit.BAR.format(
                PressureUnit.MIN_THRESHOLD_KPA + i * PressureUnit.THRESHOLD_STEP_KPA
            )
        }
        assertEquals("0.1", formatted.first())
        assertEquals("2.2", formatted[21])
        assertEquals("4.0", formatted.last())
        // No float dust: every stop prints as X.Y, nothing longer.
        formatted.forEach { assertTrue("unexpected bar text '$it'", it.matches(Regex("""\d+\.\d"""))) }
    }

    @Test
    fun thresholdConversionsRoundTripThroughEveryUnit() {
        for (unit in PressureUnit.entries) {
            val kpa = 220f
            assertEquals(kpa, unit.toKpa(unit.fromKpa(kpa)), 0.01f)
        }
        // The step in psi is ~1.45, i.e. a detent is visible but not a round psi number —
        // documented behaviour, the kPa value stays authoritative.
        assertEquals(1.45f, PressureUnit.PSI.fromKpa(PressureUnit.THRESHOLD_STEP_KPA), 0.01f)
    }

    // ── alarmingWheels matrix ─────────────────────────────────────────────────

    @Test
    fun belowLimitAlarmsAtOrBelowOneTenthBarAboveZero() {
        assertEquals(listOf(TirePosition.FRONT_LEFT), alarming(reading(TirePosition.FRONT_LEFT, 219.9f)))
        // The lowest possible setting is 0.1 bar; anything under it still alarms.
        assertEquals(
            listOf(TirePosition.FRONT_LEFT),
            snapshot(reading(TirePosition.FRONT_LEFT, 9f))
                .alarmingWheels(PressureUnit.MIN_THRESHOLD_KPA, true, now)
        )
    }

    @Test
    fun atOrAboveLimitDoesNotAlarm() {
        assertEquals(emptyList<TirePosition>(), alarming(reading(TirePosition.FRONT_LEFT, 220f)))
        assertEquals(emptyList<TirePosition>(), alarming(reading(TirePosition.FRONT_LEFT, 221f)))
    }

    @Test
    fun alarmSwitchOffSilencesEverything() {
        val snap = snapshot(
            reading(TirePosition.FRONT_LEFT, 100f),
            reading(TirePosition.REAR_RIGHT, 300f, hasAlarm = true)
        )
        assertEquals(emptyList<TirePosition>(), snap.alarmingWheels(220f, alarmEnabled = false, nowMs = now))
        // …and the plain low list is unaffected by the switch (it is only the display helper).
        assertEquals(listOf(TirePosition.FRONT_LEFT), snap.lowPressureWheels(220f, now))
    }

    @Test
    fun zeroAndNoSignalAreNoDataNotFlatTyres() {
        // This guard is what makes a 0.1 bar threshold safe: wheels the receiver has
        // never heard from report 0 kPa and must not turn the card red.
        assertEquals(emptyList<TirePosition>(), alarming(reading(TirePosition.FRONT_LEFT, 0f)))
        assertEquals(
            emptyList<TirePosition>(),
            alarming(reading(TirePosition.FRONT_LEFT, 50f, noSignal = true))
        )
    }

    @Test
    fun staleReadingsDoNotAlarm() {
        val staleMs = TireReading.STALE_AFTER_MS + 1_000L
        assertEquals(emptyList<TirePosition>(), alarming(reading(TirePosition.FRONT_LEFT, 100f, ageMs = staleMs)))
        // Just inside the freshness window still alarms.
        assertEquals(
            listOf(TirePosition.FRONT_LEFT),
            alarming(reading(TirePosition.FRONT_LEFT, 100f, ageMs = TireReading.STALE_AFTER_MS - 1_000L))
        )
        // A stale sensor's own leak flag must not sound either — an unplugged receiver
        // keeps last-known readings on screen, they must stay quiet.
        val staleLeak = reading(TirePosition.FRONT_LEFT, 300f, ageMs = staleMs, hasAlarm = true)
        assertEquals(emptyList<TirePosition>(), alarming(staleLeak))
    }

    @Test
    fun sensorLeakFlagAlarmsEvenAboveTheLimit() {
        assertEquals(listOf(TirePosition.REAR_RIGHT), alarming(reading(TirePosition.REAR_RIGHT, 300f, hasAlarm = true)))
        // A leak flag on a 0 kPa reading is the one case where 0 may alarm.
        assertEquals(listOf(TirePosition.REAR_RIGHT), alarming(reading(TirePosition.REAR_RIGHT, 0f, hasAlarm = true)))
    }

    @Test
    fun resultIsDedupedAndSortedByWheelIdIncludingSpare() {
        // Insertion order deliberately scrambled; RR is both low and leaking.
        val snap = snapshot(
            reading(TirePosition.REAR_RIGHT, 200f, hasAlarm = true),
            reading(TirePosition.REAR_LEFT, 150f),
            reading(TirePosition.SPARE, 120f),
            reading(TirePosition.FRONT_RIGHT, 210f),
            reading(TirePosition.FRONT_LEFT, 250f)
        )
        assertEquals(
            listOf(
                TirePosition.FRONT_RIGHT,   // 0x01
                TirePosition.SPARE,         // 0x05
                TirePosition.REAR_LEFT,     // 0x10
                TirePosition.REAR_RIGHT     // 0x11 — appears once despite two reasons
            ),
            snap.alarmingWheels(220f, alarmEnabled = true, nowMs = now)
        )
        assertTrue(snap.hasAnyReading)
    }
}
