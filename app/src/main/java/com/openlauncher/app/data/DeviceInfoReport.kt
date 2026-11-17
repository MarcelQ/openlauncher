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

    // ── APP OVERLAY / freeform probe (Settings ▸ Diagnostics) ────────────────

    /**
     * One-line verdict on whether this ROM can host freeform windows, from the
     * two framework signals: the declared system feature and the legacy
     * `Settings.Global/enable_freeform_support` enabler. On API ≤30 AOSP builds
     * OR the enabler into the window manager's freeform check, so "enabler ON"
     * alone is usually enough; newer platforms gate windowing-mode requests by
     * caller privilege regardless of the setting.
     */
    fun freeformSupportLine(featureDeclared: Boolean, enabler: String?): String = when {
        featureDeclared && enabler == "1" ->
            "SUPPORTED — ROM declares the feature AND the enabler is on"
        featureDeclared ->
            "ROM declares the feature; enabler unset/off (feature alone should suffice)"
        enabler == "1" ->
            "ENABLER ON but no feature flag — API≤30 AOSP still honors freeform, try the card (framework caches this at boot: REBOOT once after enabling before testing)"
        enabler == "0" ->
            "OFF — enabler explicitly set to 0; `adb shell settings put global enable_freeform_support 1`"
        else ->
            "OFF — no feature flag, enabler never set; `adb shell settings put global enable_freeform_support 1` (developer option 'Enable freeform windows' does the same)"
    }

    /**
     * Whether the assigned APP OVERLAY app may run in a resizable/freeform
     * window. [appFlag] = ApplicationInfo.resizeableActivity (null on API <24),
     * [activityFlags] = per-activity resizeable bits collected on the caller side.
     */
    fun resizabilitySummary(appFlag: Boolean?, activityFlags: List<Boolean>): String = when {
        appFlag == true          -> "yes (app manifest resizable)"
        appFlag == false && activityFlags.none { it } ->
            "NO — app opts out of resizing; even a freeform-capable ROM may force it fullscreen"
        activityFlags.any { it } -> "yes (activity-level resizable)"
        else                     -> "unreadable — hidden resize fields blocked on this ROM; N+ apps default to resizable unless opted out"
    }

    /**
     * Common su binary locations checked by the collector (existence only — the
     * app never executes them). Ordered like the well-known rooting-software
     * layouts on aftermarket head-unit ROMs.
     */
    val suCandidatePaths: List<String> = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/vendor/bin/su",
        "/system/xbin/daemonsu", "/system/bin/daemonsu"
    )

    fun suSummary(foundPaths: List<String>): String =
        if (foundPaths.isEmpty()) "no su binaries found (not rooted)"
        else "ROOTED: " + foundPaths.joinToString()

    /**
     * `ApplicationExitInfo.reason` code -> readable label (API 30 post-mortem
     * probe in DeviceInfoCollector). Values mirror the framework constants,
     * duplicated here so the mapping stays JVM-testable; unknown codes fall
     * back to "code N" so newer ROMs degrade gracefully. CRASH/ANR is the
     * answer to "opens for a second then bounces back" on ROMs that hide
     * crash dialogs (`Settings.Global/hide_error_dialogs`).
     */
    fun appExitReasonName(code: Int): String = when (code) {
        1  -> "exit-self (app finished itself — normal for control-panel activities)"
        2  -> "signaled"
        3  -> "low-memory (lmk)"
        4  -> "CRASH — java exception"
        5  -> "CRASH_NATIVE"
        6  -> "ANR"
        7  -> "INITIALIZING — died before fully starting"
        8  -> "excessive resource usage"
        9  -> "user-requested (force stop)"
        10 -> "system update"
        15 -> "dependency-killed (newer Android)"
        16 -> "package update (killed by an APK replace)"
        100 -> "other"
        else -> "code $code"
    }
}
