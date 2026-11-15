package com.openlauncher.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM tests for the shuffle custom-action lookup backing the Now Playing
 * shuffle button (pure helper findShuffleActionIndex in NowPlayingState.kt).
 */
class NowPlayingCustomActionTest {

    private fun action(action: String, title: String) =
        NowPlayingCustomAction(action = action, title = title)

    @Test
    fun `title match wins over action id`() {
        val actions = listOf(
            action("com.example.action.REPEAT", "Repeat"),
            action("com.example.action.RANDOM_ORDER", "Shuffle"),
            action("com.spotify.action.TOGGLE_SHUFFLE", "Spotify shuffle internals")
        )
        // First action whose TITLE says shuffle — not the id-only candidate.
        assertEquals(1, findShuffleActionIndex(actions))
    }

    @Test
    fun `action id match when no title matches`() {
        val actions = listOf(
            action("com.example.action.REPEAT", "Repeat"),
            action("com.spotify.action.TOGGLE_SHUFFLE", "Spotify shuffle internals")
        )
        assertEquals(1, findShuffleActionIndex(actions))
    }

    @Test
    fun `matching ignores case and works on spaced titles`() {
        val actions = listOf(action("x.y.TOGGLE", "Toggle Shuffle On"))
        assertEquals(0, findShuffleActionIndex(actions))
        val upper = listOf(action("x.Y.Z.TOGGLE_SHUFFLE", "REPEAT"))
        assertEquals(0, findShuffleActionIndex(upper))
    }

    @Test
    fun `no shuffle action exposed yields -1`() {
        val actions = listOf(
            action("com.example.action.REPEAT", "Repeat"),
            action("com.example.action.LIKE", "Like")
        )
        assertEquals(-1, findShuffleActionIndex(actions))
        assertEquals(-1, findShuffleActionIndex(emptyList()))
    }

    @Test
    fun `state-carrying titles like shuffle off still match`() {
        // Apps often label the toggle with its CURRENT state, e.g. "Shuffle off".
        val actions = listOf(action("x.y.ACTION", "Shuffle: Off"))
        assertEquals(0, findShuffleActionIndex(actions))
    }
}
