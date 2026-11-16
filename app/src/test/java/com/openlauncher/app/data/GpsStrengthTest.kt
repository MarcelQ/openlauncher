package com.openlauncher.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Covers the GPS status icon's strength mapping (GpsStrengthLogic.evaluate). */
class GpsStrengthTest {

    @Test
    fun `permission missing or provider off is OFF regardless of fix`() {
        assertEquals(GpsStrength.OFF, GpsStrengthLogic.evaluate(false, true, 9, 5f))
        assertEquals(GpsStrength.OFF, GpsStrengthLogic.evaluate(true, false, 9, 5f))
        assertEquals(GpsStrength.OFF, GpsStrengthLogic.evaluate(false, false, null, null))
    }

    @Test
    fun `satellite counts drive the GNSS tiers`() {
        assertEquals(GpsStrength.STRONG, GpsStrengthLogic.evaluate(true, true, 5, 80f))
        assertEquals(GpsStrength.STRONG, GpsStrengthLogic.evaluate(true, true, 4, 20f))
        assertEquals(GpsStrength.FAIR,   GpsStrengthLogic.evaluate(true, true, 1, 5f))
        assertEquals(GpsStrength.FAIR,   GpsStrengthLogic.evaluate(true, true, 3, 1f))
        // GNSS chips present but nothing locked yet -> still searching
        assertEquals(GpsStrength.WEAK,   GpsStrengthLogic.evaluate(true, true, 0, 3f))
    }

    @Test
    fun `accuracy fallback applies only when no GNSS status was seen`() {
        assertEquals(GpsStrength.STRONG, GpsStrengthLogic.evaluate(true, true, null, 15f))
        assertEquals(GpsStrength.FAIR,   GpsStrengthLogic.evaluate(true, true, null, 50f))
        assertEquals(GpsStrength.WEAK,   GpsStrengthLogic.evaluate(true, true, null, 51f))
        assertEquals(GpsStrength.WEAK,   GpsStrengthLogic.evaluate(true, true, null, 300f))
    }

    @Test
    fun `no GNSS status and no fix yet is WEAK`() {
        assertEquals(GpsStrength.WEAK, GpsStrengthLogic.evaluate(true, true, null, null))
    }
}
