package com.openlauncher.app.data

/**
 * Pure (framework-free) decision helpers for the developer device report
 * (Settings ▸ Diagnostics). Split out of [DeviceInfoCollector] so the parts that
 * need no Android context — which vendor packages indicate which head-unit
 * subsystem, human-readable sensor names — are unit-testable on the JVM
 * (DeviceInfoReportTest.kt).
 */
object DeviceInfoReport {

    /**
     * Sensor type id → readable name. The numeric values mirror
     * `android.hardware.Sensor.TYPE_*` constants, duplicated here so this map
     * stays testable without the framework. Unknown ids fall back to "type N"
     * so a head unit with exotic sensors still produces a readable line.
     */
    fun sensorTypeName(type: Int): String = when (type) {
        1  -> "accelerometer"
        2  -> "magnetic field"
        3  -> "orientation"
        4  -> "gyroscope"
        5  -> "ambient light"
        6  -> "linear acceleration"
        7  -> "gravity"
        8  -> "rotation vector"
        9  -> "relative humidity"
        10 -> "ambient temperature"
        11 -> "magnetic field (uncalibrated)"
        12 -> "proximity"
        13 -> "barometric pressure"
        14 -> "gyroscope (uncalibrated)"
        15 -> "game rotation vector"
        16 -> "geomagnetic rotation vector"
        17 -> "game gyroscope"
        18 -> "step detector"
        19 -> "step counter"
        20 -> "significant motion"
        21 -> "heart rate"
        22 -> "device orientation"
        else -> "type $type"
    }

    /**
     * Head-unit subsystem detection: subsystem label → lowercase substrings matched
     * against installed package names. The needles are publicly known head-unit
     * vendor and feature identifiers (e.g. the szchoiceway MCU stack the radio card
     * already talks to) — deliberately a starter list, not exhaustive. Packages
     * matched here are highlighted in the report; everything else still appears in
     * the full installed-apps list, so an unmatched vendor package is never lost,
     * only not pre-labelled.
     */
    private val subsystemPatterns: List<Pair<String, List<String>>> = listOf(
        "MCU / vehicle bus"        to listOf(
            "szchoiceway", "carbit", "canbus", "eventcenter", "vehical",
            "autosense", "carinfoonline", ".mcu", "mcu.", "vehiclecontroller",
            "carfactoryservice"
        ),
        "CarPlay / phone mirroring" to listOf(
            "carplay", "carpplay", "carlink", "carlinkit", "easyconn", "hicar",
            "smartlink", "mirrorlink", "airpin", "zlink", "autoconnect", "mirrorwise"
        ),
        "Radio / FM tuner"         to listOf("radio", "tuner", ".fm.", ".fm"),
        "Camera / parking aid"     to listOf("camera", "parking", "reverse", "backupcam", "avm", "dvr"),
        "OBD / diagnostics"        to listOf("obd", "elm327", "torque"),
        "Steering wheel / input"   to listOf("steering", ".swc", "swc.", "remotecontroller", "keyevent", "keyproxy"),
        "DSP / audio processing"   to listOf(".dsp", "dsp.", "equalizer", "soundfield", "audioeffect", "wavesprocessing"),
        "Navigation"               to listOf("navigation", "garmin", "co-pilot", "here.maps")
    )

    /**
     * Groups [packages] by head-unit subsystem. Only subsystems with at least one
     * hit appear; subsystem order follows [subsystemPatterns]. A package matching
     * two subsystems is listed under both (rare, and visible either way).
     */
    fun classifyPackages(packages: List<String>): LinkedHashMap<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        for ((subsystem, needles) in subsystemPatterns) {
            val hits = packages.filter { pkg ->
                val lower = pkg.lowercase()
                needles.any { lower.contains(it) }
            }
            if (hits.isNotEmpty()) result[subsystem] = hits
        }
        return result
    }

    /** "system"/"user" marker for the installed-apps list. */
    fun installMarker(isSystemApp: Boolean): String = if (isSystemApp) "system" else "user  "
}
