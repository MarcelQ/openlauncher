package com.openlauncher.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure resolution + persistence rules behind MULTI-EMBED cards: several
 * EMBED_APP WidgetInstances each assigned their own app so the embed engine
 * can run them concurrently (see TaskEmbedEngine docs). JVM-only.
 */
class EmbedAssignmentTest {

    private val card1 = WidgetInstance("EMBED_APP",    "EMBED_APP", page = 0, gridX = 0, gridY = 0)
    private val card2 = WidgetInstance("EMBED_APP~2",  "EMBED_APP", page = 0, gridX = 1, gridY = 0)

    @Test
    fun `legacy global assignment applies to every card without a per-card entry`() {
        val s = AppSettings(embedAppPackage = "com.google.android.apps.maps", widgets = listOf(card1, card2))
        assertEquals("com.google.android.apps.maps", s.embedPackageFor("EMBED_APP"))
        assertEquals("com.google.android.apps.maps", s.embedPackageFor("EMBED_APP~2"))
    }

    @Test
    fun `per-card entry wins and an explicit empty value clears only that card`() {
        val s = AppSettings(
            embedAppPackage  = "com.google.android.apps.maps",
            embedAppPackages = mapOf("EMBED_APP~2" to "com.spotify.music", "EMBED_APP" to ""),
            widgets          = listOf(card1, card2)
        )
        assertEquals("", s.embedPackageFor("EMBED_APP"))                 // cleared despite legacy key
        assertEquals("com.spotify.music", s.embedPackageFor("EMBED_APP~2"))
        // a card nobody touched still follows the legacy key:
        assertEquals("com.google.android.apps.maps", s.embedPackageFor("EMBED_APP~3"))
    }

    @Test
    fun `assignment map survives the serialize-parse round trip`() {
        val map = mapOf(
            "EMBED_APP"   to "com.google.android.apps.maps",
            "EMBED_APP~2" to "com.spotify.music",
            "EMBED_APP~3" to "" // explicit clear must survive too
        )
        assertEquals(map, parseEmbedAssignments(serializeEmbedAssignments(map)))
        assertEquals(emptyMap<String, String>(), parseEmbedAssignments(""))
    }

    @Test
    fun `malformed lines are dropped and dotted packages with tilde ids are fine`() {
        assertEquals(
            mapOf("EMBED_APP~2" to "com.spotify.music"),
            parseEmbedAssignments("garbageline\n=leadingeq\nEMBED_APP~2=com.spotify.music")
        )
    }
}
