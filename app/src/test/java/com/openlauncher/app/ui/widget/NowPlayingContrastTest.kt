package com.openlauncher.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the Now Playing card's adaptive title-contrast helpers
 * (pure functions at the bottom of NowPlayingWidget.kt — no Bitmap needed).
 */
class NowPlayingContrastTest {

    // ── averageLuminance ─────────────────────────────────────────────────────

    @Test
    fun `empty pixel array is treated as black`() {
        assertEquals(0f, averageLuminance(IntArray(0)), 0f)
    }

    @Test
    fun `solid colours match Rec 709 luma coefficients`() {
        // opaque black / white
        assertEquals(0f, averageLuminance(intArrayOf(0xFF000000.toInt())), 1e-4f)
        assertEquals(1f, averageLuminance(intArrayOf(0xFFFFFFFF.toInt())), 1e-4f)
        // pure channels: 0.2126 R + 0.7152 G + 0.0722 B
        assertEquals(0.2126f, averageLuminance(intArrayOf(0xFFFF0000.toInt())), 1e-4f)
        assertEquals(0.7152f, averageLuminance(intArrayOf(0xFF00FF00.toInt())), 1e-4f)
        assertEquals(0.0722f, averageLuminance(intArrayOf(0xFF0000FF.toInt())), 1e-4f)
    }

    @Test
    fun `mixed pixels average the per-pixel luma`() {
        val mixed = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())
        assertEquals(0.5f, averageLuminance(mixed), 1e-4f)
    }

    @Test
    fun `mid grey sits near half brightness`() {
        assertEquals(0.5f, averageLuminance(intArrayOf(0xFF808080.toInt())), 0.01f)
    }

    // ── useBlackTextOnArtwork ────────────────────────────────────────────────
    // Rule: black title text once dimmed artwork (x0.75) stays brighter than 0.5,
    // i.e. raw luminance above 2/3.

    @Test
    fun `bright artwork switches the title to black text`() {
        assertTrue(useBlackTextOnArtwork(1.0f))
        assertTrue(useBlackTextOnArtwork(0.9f))
        assertTrue(useBlackTextOnArtwork(0.67f))  // just over the 2/3 boundary
    }

    @Test
    fun `dark and mid artwork keep white title text`() {
        assertFalse(useBlackTextOnArtwork(0.0f))
        assertFalse(useBlackTextOnArtwork(0.5f))
        assertFalse(useBlackTextOnArtwork(0.66f)) // just under the boundary
    }

    @Test
    fun `boundary is exclusive at two thirds luminance`() {
        // 2/3 * 0.75 == 0.5 exactly -> not > 0.5 -> stay white
        assertFalse(useBlackTextOnArtwork(2f / 3f))
    }
}
