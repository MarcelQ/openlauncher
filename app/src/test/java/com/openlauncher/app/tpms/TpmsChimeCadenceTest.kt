package com.openlauncher.app.tpms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chime timing rules — the schedule that decides *when* the audible low-pressure warning
 * fires. Pure logic with an injected monotonic-style clock, so the whole timeline can be
 * walked with plain integers (the ViewModel feeds it SystemClock.elapsedRealtime()).
 */
class TpmsChimeCadenceTest {

    /** Drive a cadence at a fixed tick period and collect every chime instant. */
    private fun schedule(
        cadence: TpmsChimeCadence,
        untilMs: Long,
        tickMs: Long = 1_000L,
        alarming: (Long) -> Boolean = { true }
    ): List<Long> {
        val chimed = mutableListOf<Long>()
        var t = 0L
        while (t <= untilMs) {
            if (cadence.shouldChime(alarming(t), t)) chimed += t
            t += tickMs
        }
        return chimed
    }

    @Test
    fun firstChimeOnlyAfterWarningIsFourSecondsOld() {
        val cadence = TpmsChimeCadence()
        assertEquals(emptyList<Long>(), schedule(cadence, untilMs = 3_000L))
        // First tick at 0 s arms the cadence without chiming; the chime lands at exactly 4 s.
        assertEquals(listOf(4_000L), schedule(TpmsChimeCadence(), untilMs = 4_000L))
    }

    @Test
    fun eachScheduledChimeFiresExactlyOnce() {
        val cadence = TpmsChimeCadence()
        assertFalse(cadence.shouldChime(alarming = true, nowMs = 0L))    // arms
        assertTrue(cadence.shouldChime(alarming = true, nowMs = 4_000L)) // chimes
        assertFalse(cadence.shouldChime(alarming = true, nowMs = 4_001L))
        assertFalse(cadence.shouldChime(alarming = true, nowMs = 33_999L))
        assertTrue(cadence.shouldChime(alarming = true, nowMs = 34_000L))
    }

    @Test
    fun repeatsEveryThirtySecondsThenWidensToTwoMinutesAfterFiveMinutes() {
        // Expected: every 30 s while the warning is younger than 5 min, then 2 min gaps —
        // "we've told you already" for a slow leak on a long trip.
        assertEquals(
            listOf(
                4_000L, 34_000L, 64_000L, 94_000L, 124_000L, 154_000L,
                184_000L, 214_000L, 244_000L, 274_000L, 304_000L, // last one is 304 s old → now tired
                424_000L, 544_000L
            ),
            schedule(TpmsChimeCadence(), untilMs = 550_000L)
        )
    }

    @Test
    fun warningClearingRestartsTheShortFirstDelay() {
        val cadence = TpmsChimeCadence()
        // Sound for a while…
        assertFalse(cadence.shouldChime(alarming = true, nowMs = 0L))
        assertTrue(cadence.shouldChime(alarming = true, nowMs = 4_000L))
        // …then the driver tops up the tyre: one non-alarming tick wipes the history.
        assertFalse(cadence.shouldChime(alarming = false, nowMs = 11_000L))
        assertFalse(cadence.alarming)
        // A new genuine drop gets the 4 s grace period again, not an instant repeat.
        assertFalse(cadence.shouldChime(alarming = true, nowMs = 12_000L))
        assertFalse(cadence.shouldChime(alarming = true, nowMs = 15_999L))
        assertTrue(cadence.shouldChime(alarming = true, nowMs = 16_000L))
    }

    @Test
    fun explicitResetMakesNextWarningStartFresh() {
        val cadence = TpmsChimeCadence()
        assertTrue(schedule(cadence, untilMs = 4_000L).isNotEmpty())
        cadence.reset()
        assertFalse(cadence.alarming)
        // Arming again at t=10 s chimes at 14 s, not immediately.
        assertFalse(cadence.shouldChime(alarming = true, nowMs = 10_000L))
        assertFalse(cadence.shouldChime(alarming = true, nowMs = 13_000L))
        assertTrue(cadence.shouldChime(alarming = true, nowMs = 14_000L))
    }

    @Test
    fun alarmingPropertyTracksTheActiveWarning() {
        val cadence = TpmsChimeCadence()
        assertFalse(cadence.alarming)
        cadence.shouldChime(alarming = true, nowMs = 0L)
        assertTrue(cadence.alarming)
        cadence.shouldChime(alarming = false, nowMs = 1_000L)
        assertFalse(cadence.alarming)
    }

    @Test
    fun allTimingRulesAreInjectable() {
        val cadence = TpmsChimeCadence(
            firstDelayMs  = 1_000L,
            repeatMs      = 2_000L,
            tiredAfterMs  = 5_000L,
            tiredRepeatMs = 10_000L
        )
        assertEquals(listOf(1_000L, 3_000L, 5_000L, 15_000L), schedule(cadence, untilMs = 16_000L))
    }

    @Test
    fun shortGlitchNeverChimes() {
        // One bad frame while cranking: alarming for 2 s, then clean — under the 4 s delay.
        val cadence = TpmsChimeCadence()
        val glitch = schedule(cadence, untilMs = 10_000L, alarming = { it in 0L..2_000L })
        assertEquals(emptyList<Long>(), glitch)
    }
}
