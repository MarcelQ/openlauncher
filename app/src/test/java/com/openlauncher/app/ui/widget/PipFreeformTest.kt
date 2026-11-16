package com.openlauncher.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision helpers behind the PIP_APP overlay card (PipFreeform.kt).
 * The Android launch chain itself is device-verified only.
 */
class PipFreeformTest {

    @Test
    fun `plan with valid bounds tries reflection then bundle key then fullscreen`() {
        assertEquals(
            listOf(
                PipLaunchMode.FREEFORM_REFLECTION,
                PipLaunchMode.FREEFORM_BUNDLE,
                PipLaunchMode.FULLSCREEN
            ),
            pipLaunchPlan(hasValidBounds = true)
        )
    }

    @Test
    fun `plan without bounds degrades to a plain fullscreen launch`() {
        assertEquals(listOf(PipLaunchMode.FULLSCREEN), pipLaunchPlan(hasValidBounds = false))
    }

    @Test
    fun `zero-size and off-by-inversion rects are invalid`() {
        assertFalse(FreeformBounds(10, 10, 10, 40).isValid) // zero width
        assertFalse(FreeformBounds(10, 10, 40, 10).isValid) // zero height
        assertFalse(FreeformBounds(40, 40, 10, 10).isValid) // inverted
        assertTrue(FreeformBounds(0, 0, 1, 1).isValid)
    }

    @Test
    fun `screen rect is window offset plus card offset plus size`() {
        // Launcher window offset by the status bar (0,24); card at (100,50) sized 300x200.
        assertEquals(
            FreeformBounds(left = 100, top = 74, right = 400, bottom = 274),
            freeformBoundsOnScreen(rootX = 0, rootY = 24, cardX = 100, cardY = 50, width = 300, height = 200)
        )
    }

    @Test
    fun `computed screen rects pass their own validity gate`() {
        val b = freeformBoundsOnScreen(rootX = 0, rootY = 0, cardX = 120, cardY = 60, width = 250, height = 260)
        assertTrue(b.isValid)
        assertEquals(
            listOf(PipLaunchMode.FREEFORM_REFLECTION, PipLaunchMode.FREEFORM_BUNDLE, PipLaunchMode.FULLSCREEN),
            pipLaunchPlan(b.isValid)
        )
    }

    @Test
    fun `bundle key and windowing mode match the AOSP constants`() {
        // Keep in sync with ActivityOptions (hidden): WINDOWING_MODE_FREEFORM = 5,
        // KEY_LAUNCH_WINDOWING_MODE = "android:activity.windowingMode".
        assertEquals(5, WINDOWING_MODE_FREEFORM)
        assertEquals("android:activity.windowingMode", KEY_LAUNCH_WINDOWING_MODE)
    }
}
