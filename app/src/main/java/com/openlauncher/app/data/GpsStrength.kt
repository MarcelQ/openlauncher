package com.openlauncher.app.data

/**
 * GNSS fix quality rendered as the sidebar's GPS status icon.
 * Inputs come from LocationCompassManager (GnssStatus satellite counts on
 * API 24+, fix accuracy as the fallback for older head units); the mapping
 * itself is framework-free and covered by GpsStrengthTest.
 */
enum class GpsStrength { OFF, WEAK, FAIR, STRONG }

object GpsStrengthLogic {
    /**
     * providerOn      – at least one location provider is switched on
     * permissionGranted – the app holds a runtime location permission
     * usedInFix       – satellites used in the latest GNSS fix (GnssStatus,
     *                   API 24+); null when no GNSS status has been seen
     * accuracyM       – last fix accuracy in metres; null when no fix yet
     */
    fun evaluate(
        providerOn: Boolean,
        permissionGranted: Boolean,
        usedInFix: Int?,
        accuracyM: Float?
    ): GpsStrength = when {
        !providerOn || !permissionGranted           -> GpsStrength.OFF
        usedInFix != null && usedInFix >= 4         -> GpsStrength.STRONG
        usedInFix != null && usedInFix >= 1         -> GpsStrength.FAIR
        usedInFix == null && accuracyM != null &&
            accuracyM <= 15f                        -> GpsStrength.STRONG
        usedInFix == null && accuracyM != null &&
            accuracyM <= 50f                        -> GpsStrength.FAIR
        else                                        -> GpsStrength.WEAK
    }
}
