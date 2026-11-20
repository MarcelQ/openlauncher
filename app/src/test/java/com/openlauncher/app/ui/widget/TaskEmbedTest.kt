package com.openlauncher.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the EMBED card's pure decision core (TaskEmbed.kt). The
 * reflective engine in TaskEmbedEngine.kt is device-verified only — these
 * tests pin the ROM-verified decision matrix: engine chain order, the
 * system-uid/API gate, per-API permission expectations, and the never-dead-tap
 * fallback rule.
 */
class TaskEmbedTest {

    @Test
    fun `non-system build skips straight to fallback`() {
        assertEquals(listOf(EmbedMode.FALLBACK_LAUNCH), embedChain(systemUid = false, sdkInt = 30, hasValidBounds = true))
    }

    @Test
    fun `pre-API-29 SDK falls back (initialize needs the SurfaceControl public API)`() {
        assertEquals(listOf(EmbedMode.FALLBACK_LAUNCH), embedChain(systemUid = true, sdkInt = 25, hasValidBounds = true))
        assertEquals(listOf(EmbedMode.FALLBACK_LAUNCH), embedChain(systemUid = true, sdkInt = 28, hasValidBounds = true))
    }

    @Test
    fun `invalid card rect falls back (nothing to size the task to)`() {
        assertEquals(listOf(EmbedMode.FALLBACK_LAUNCH), embedChain(systemUid = true, sdkInt = 30, hasValidBounds = false))
    }

    @Test
    fun `system unit gets the ROM-verified engine order`() {
        // TaskEmbedder (vendor ROM's own host) first, manual organizer second,
        // overlay launch last — matches the dev's API 30 SPD framework dump.
        assertEquals(
            listOf(EmbedMode.TASK_EMBEDDER, EmbedMode.TASK_ORGANIZER, EmbedMode.FALLBACK_LAUNCH),
            embedChain(systemUid = true, sdkInt = 29, hasValidBounds = true)
        )
        assertEquals(
            listOf(EmbedMode.TASK_EMBEDDER, EmbedMode.TASK_ORGANIZER, EmbedMode.FALLBACK_LAUNCH),
            embedChain(systemUid = true, sdkInt = 30, hasValidBounds = true)
        )
    }

    @Test
    fun `additional concurrent session prefers its own VirtualDisplay engine`() {
        // Server binds each new task to the LAST organizer registered for the
        // embed windowing mode (peekLast) — a second default-display embedder
        // would cross-wire card↔task binding, so extra sessions go first-VD.
        // Round 9: the VD engine's failure path goes STRAIGHT to fallback —
        // retrying the organizer engine would re-register an organizer and
        // steal the FIRST card's binding (round-8 bench: card 1 went blind and
        // card 2 rendered nothing).
        assertEquals(
            listOf(EmbedMode.TASK_ORGANIZER, EmbedMode.FALLBACK_LAUNCH),
            embedChain(systemUid = true, sdkInt = 30, hasValidBounds = true, vdFirst = true)
        )
        // The hint never bypasses the honest gates:
        assertEquals(
            listOf(EmbedMode.FALLBACK_LAUNCH),
            embedChain(systemUid = false, sdkInt = 30, hasValidBounds = true, vdFirst = true)
        )
        assertEquals(
            listOf(EmbedMode.FALLBACK_LAUNCH),
            embedChain(systemUid = true, sdkInt = 30, hasValidBounds = false, vdFirst = true)
        )
        // The FIRST session's chain keeps the full ladder (it owns the organizer).
        assertEquals(
            listOf(EmbedMode.TASK_EMBEDDER, EmbedMode.TASK_ORGANIZER, EmbedMode.FALLBACK_LAUNCH),
            embedChain(systemUid = true, sdkInt = 30, hasValidBounds = true, vdFirst = false)
        )
    }

    @Test
    fun `engines only engage at system uid with stack management granted`() {
        assertTrue(embedEnginesAllowed(systemUid = true,  sdkInt = 30, canManageActivities = true))
        assertFalse(embedEnginesAllowed(systemUid = false, sdkInt = 30, canManageActivities = true))
        assertFalse(embedEnginesAllowed(systemUid = true,  sdkInt = 30, canManageActivities = false))
        assertFalse(embedEnginesAllowed(systemUid = true,  sdkInt = 21, canManageActivities = true))
    }

    @Test
    fun `embed rect must be fully on screen (page-2 cards are laid out off-screen)`() {
        val screen = 1280 to 720
        assertTrue(embedRectOnScreen(FreeformBounds(970, 364, 1272, 712), screen.first, screen.second))
        // page-2 pager offset: card pushed fully past the right edge
        assertFalse(embedRectOnScreen(FreeformBounds(1280, 364, 1582, 712), screen.first, screen.second))
        // page-0 offset to the left (previous page)
        assertFalse(embedRectOnScreen(FreeformBounds(-302, 364, 0, 712), screen.first, screen.second))
        // bottom overflowing the panel
        assertFalse(embedRectOnScreen(FreeformBounds(0, 500, 300, 721), screen.first, screen.second))
        // zero/invalid rect
        assertFalse(embedRectOnScreen(FreeformBounds(0, 0, 0, 0), screen.first, screen.second))
    }

    @Test
    fun `API 30 unit expects MANAGE_ACTIVITY_STACKS not the API 31 name`() {
        val missing = embedMissingPermissions(sdkInt = 30, granted = setOf(PERM_ACTIVITY_EMBEDDING))
        assertEquals(listOf(PERM_MANAGE_ACTIVITY_STACKS), missing)
    }

    @Test
    fun `API 31+ expects MANAGE_ACTIVITY_TASKS`() {
        val missing = embedMissingPermissions(sdkInt = 31, granted = emptySet())
        assertEquals(listOf(PERM_ACTIVITY_EMBEDDING, PERM_MANAGE_ACTIVITY_TASKS), missing)
        assertTrue(embedMissingPermissions(sdkInt = 31, granted = setOf(PERM_ACTIVITY_EMBEDDING, PERM_MANAGE_ACTIVITY_TASKS)).isEmpty())
    }

    @Test
    fun `embed launch flags are NEW_TASK plus MULTIPLE_TASK`() {
        // Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK —
        // byte-identical to mini-aa's TaskView startApp addFlags(268500992).
        assertEquals(0x10008000, EMBED_LAUNCH_FLAGS)
    }

    @Test
    fun `resume watchdog revives only live sessions past the idle threshold`() {
        // mini-aa pattern (30 s there; ours shorter — the revive ladder starts
        // non-destructive with a WCT reorder+show).
        assertTrue(embedReviveOnResume(idleMs = 5_000, sessionLive = true))
        assertTrue(embedReviveOnResume(idleMs = EMBED_REVIVE_IDLE_MS, sessionLive = true))
        // Recents-peek / transient window blips: do nothing.
        assertFalse(embedReviveOnResume(idleMs = 500, sessionLive = true))
        // Dead/fallback sessions must not be revived from the watchdog.
        assertFalse(embedReviveOnResume(idleMs = 60_000, sessionLive = false))
    }

    @Test
    fun `fallback tap is never dead for an assigned card and dead only while editing`() {
        assertEquals(PipLaunchMode.FREEFORM_REFLECTION, embedFallbackTap(isEditing = false, hasApp = true))
        assertNull(embedFallbackTap(isEditing = true, hasApp = true))
        assertNull(embedFallbackTap(isEditing = false, hasApp = false))
    }

    @Test
    fun `embed log ring keeps the newest entries only`() {
        repeat(20) { TaskEmbedLog.record("entry-$it") }
        val snap = TaskEmbedLog.snapshot()
        assertEquals(12, snap.size)
        assertEquals("entry-19", snap.last())
        assertEquals("entry-8", snap.first())
    }
}
