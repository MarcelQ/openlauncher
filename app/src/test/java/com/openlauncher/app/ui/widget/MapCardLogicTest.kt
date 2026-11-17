package com.openlauncher.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the MAP_NATIVE card's pure helpers (branch exp-mapcard).
 * The osmdroid MapView itself is device-verified only.
 */
class MapCardLogicTest {

    @Test
    fun `user agent is identifiable per OSM tile etiquette`() {
        val ua = MapCardLogic.osmUserAgent("0.0.5")
        assertTrue(ua.startsWith("OpenLauncher/0.0.5"))
        assertTrue(ua.contains("github.com"))
    }

    @Test
    fun `initial zoom tiers follow GPS accuracy`() {
        assertEquals(11.0, MapCardLogic.initialZoomFor(null), 0.001)
        assertEquals(16.0, MapCardLogic.initialZoomFor(12f), 0.001)
        assertEquals(16.0, MapCardLogic.initialZoomFor(30f), 0.001)
        assertEquals(14.0, MapCardLogic.initialZoomFor(80f), 0.001)
        assertEquals(12.0, MapCardLogic.initialZoomFor(500f), 0.001)
        assertEquals(10.0, MapCardLogic.initialZoomFor(5000f), 0.001)
    }

    @Test
    fun `handoff uri is the standard geo map intent form`() {
        // Coordinates chosen so Double.toString cannot drop trailing zeros;
        // the URI itself is exactly the raw doubles (geo: tolerates any format).
        assertEquals(
            "geo:51.5007,-0.1246?q=51.5007,-0.1246",
            MapCardLogic.geoHandoffUri(51.5007, -0.1246),
        )
    }

    @Test
    fun `lat lon formatting keeps south and west negative`() {
        assertEquals("-33.8651, 151.2093", MapCardLogic.formatLatLon(-33.8651, 151.2093))
        assertEquals("51.5007, -0.1246", MapCardLogic.formatLatLon(51.5007, -0.1246))
    }
}
