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
     * stays testable without the framework. Values are cross-checked against the
     * real head unit's 2026-11-15 device report, where vendor sensor names made
     * the ids unambiguous (gravity = 9, linear_acceleration = 10,
     * gyroscope-uncalibrated = 16, Device Orientation = 27,
     * Accelerometer-Uncalibrated = 35). Type ids with no public constant —
     * e.g. the unit's Qualcomm-custom 22/29/30 — deliberately fall back to
     * "type N" so a head unit with exotic sensors still produces a readable line.
     */
    fun sensorTypeName(type: Int): String = when (type) {
        1  -> "accelerometer"
        2  -> "magnetic field"
        3  -> "orientation (deprecated)"
        4  -> "gyroscope"
        5  -> "ambient light"
        6  -> "barometric pressure"
        8  -> "proximity"
        9  -> "gravity"
        10 -> "linear acceleration"
        11 -> "rotation vector"
        12 -> "relative humidity"
        13 -> "ambient temperature"
        14 -> "magnetic field (uncalibrated)"
        15 -> "game rotation vector"
        16 -> "gyroscope (uncalibrated)"
        17 -> "significant motion"
        18 -> "step detector"
        19 -> "step counter"
        20 -> "geomagnetic rotation vector"
        21 -> "heart rate"
        26 -> "hinge angle"
        27 -> "device orientation"
        35 -> "accelerometer (uncalibrated)"
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
            "carfactoryservice",
            // SPD/Tianaid platform (the 2026-11-15 device report's unit: the whole
            // vendor stack lives under com.spd.* — carinfo, txzAdapter, calibrate…)
            "com.spd.", "carletter", "canbox"
        ),
        "CarPlay / phone mirroring" to listOf(
            "carplay", "carpplay", "carlink", "carlinkit", "easyconn", "hicar",
            "smartlink", "mirrorlink", "airpin", "zlink", "autoconnect", "mirrorwise",
            // observed on the 2026-11-15 unit: Goodocom BT-Tek dongle stack, Huawei
            // HiCar protocol apps, MiniAA launcher
            "goodocom", "bttek", "dmsdp", "miniaa"
        ),
        "Radio / FM tuner"         to listOf("radio", "tuner", ".fm.", ".fm"),
        "Camera / parking aid"     to listOf("camera", "parking", "reverse", "backupcam", "avm", "dvr"),
        "OBD / diagnostics"        to listOf("obd", "elm327", "torque"),
        "Steering wheel / input"   to listOf("steering", ".swc", "swc.", "remotecontroller", "keyevent", "keyproxy"),
        "DSP / audio processing"   to listOf(".dsp", "dsp.", "equalizer", "soundfield", "audioeffect", "wavesprocessing"),
        "Navigation"               to listOf("navigation", "garmin", "co-pilot", "here.maps"),
        "12 V fridge / accessory"  to listOf("alpicool", "fridge")
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
