package com.openlauncher.app.ui.widget

/**
 * Pure decision helpers behind the MAP_NATIVE card (branch exp-mapcard,
 * 2026-11-18 native-map-card research in docs/float-window-research.md).
 * Framework-free so they stay JVM-tested (MapCardLogicTest); the osmdroid
 * MapView plumbing in MapNativeWidget.kt is device-verified only.
 */
object MapCardLogic {

    /** OSM tile usage policy REQUIRES an identifiable User-Agent (Etiquette). */
    fun osmUserAgent(appVersion: String): String =
        "OpenLauncher/$appVersion (https://github.com/MarcelQ/openlauncher)"

    /**
     * First-fix zoom: GPS accuracy drives how tight the view starts. Null
     * accuracy (network fix without accuracy field, or pre-fix placeholder)
     * gets a conservative regional view.
     */
    fun initialZoomFor(accuracyMeters: Float?): Double = when {
        accuracyMeters == null -> 11.0
        accuracyMeters <= 30f  -> 16.0
        accuracyMeters <= 100f -> 14.0
        accuracyMeters <= 1000f -> 12.0
        else                   -> 10.0
    }

    /**
     * Handoff URI: standard `geo:` map intent, honored by Google Maps, Waze,
     * OSM apps etc. (the deep-link handoff model the vendor SystemUI sidebar
     * itself uses - launch, don't embed).
     */
    fun geoHandoffUri(lat: Double, lon: Double): String = "geo:$lat,$lon?q=$lat,$lon"

    /** Card sub-line format; 4 decimals ≈ 11 m, enough for a parking spot. */
    fun formatLatLon(lat: Double, lon: Double): String =
        String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon)
}
