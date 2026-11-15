package com.openlauncher.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure decision helpers of the device report
 * (DeviceInfoReport.kt). The collector itself needs a Context and is verified
 * on-device only.
 */
class DeviceInfoReportTest {

    // ── sensorTypeName ────────────────────────────────────────────────────────

    @Test
    fun `common sensor types get readable names`() {
        assertEquals("accelerometer", DeviceInfoReport.sensorTypeName(1))
        assertEquals("gyroscope", DeviceInfoReport.sensorTypeName(4))
        assertEquals("barometric pressure", DeviceInfoReport.sensorTypeName(13))
        assertEquals("proximity", DeviceInfoReport.sensorTypeName(12))
    }

    @Test
    fun `unknown sensor type falls back to its id`() {
        assertEquals("type 999", DeviceInfoReport.sensorTypeName(999))
    }

    // ── classifyPackages ──────────────────────────────────────────────────────

    @Test
    fun `mcu vendor packages map to the vehicle bus subsystem`() {
        val classified = DeviceInfoReport.classifyPackages(
            listOf("com.szchoiceway.radio", "com.szchoiceway.eventcenter", "com.google.android.gms")
        )
        assertEquals(
            listOf("com.szchoiceway.radio", "com.szchoiceway.eventcenter"),
            classified["MCU / vehicle bus"]
        )
        assertEquals(null, classified["MCU / vehicle bus"]?.firstOrNull { it.contains("gms") })
    }

    @Test
    fun `mcu mirror and camera vendors are recognised and cross-matches appear in both`() {
        val classified = DeviceInfoReport.classifyPackages(
            listOf("com.carlinkit.carlink", "com.autosense.easyconn.h96", "com.huawei.hicar", "com.vendor.parkingguideline")
        )
        assertEquals(listOf("com.autosense.easyconn.h96"), classified["MCU / vehicle bus"])
        assertEquals(listOf("com.carlinkit.carlink", "com.autosense.easyconn.h96", "com.huawei.hicar"),
            classified["CarPlay / phone mirroring"])
        assertEquals(listOf("com.vendor.parkingguideline"), classified["Camera / parking aid"])
    }

    @Test
    fun `matching is case insensitive`() {
        val classified = DeviceInfoReport.classifyPackages(listOf("COM.SZCHOICEWAY.RADIO"))
        assertEquals(listOf("COM.SZCHOICEWAY.RADIO"), classified["MCU / vehicle bus"])
    }

    @Test
    fun `ordinary apps produce no subsystem entries and map order follows pattern order`() {
        assertTrue(DeviceInfoReport.classifyPackages(
            listOf("com.spotify.music", "org.mozilla.firefox", "com.google.android.apps.maps")
        ).isEmpty())

        val mixed = DeviceInfoReport.classifyPackages(
            // radio hit listed before mcu hit in the input, but MCU subsystem comes first
            listOf("com.someapp.fmradio", "com.szchoiceway.eventcenter")
        )
        assertEquals(listOf("MCU / vehicle bus", "Radio / FM tuner"), mixed.keys.toList())
    }

    @Test
    fun `a package matching two subsystems is listed under both`() {
        val classified = DeviceInfoReport.classifyPackages(listOf("com.szchoiceway.radio"))
        assertEquals(listOf("com.szchoiceway.radio"), classified["MCU / vehicle bus"])
        assertEquals(listOf("com.szchoiceway.radio"), classified["Radio / FM tuner"])
    }

    // ── installMarker ─────────────────────────────────────────────────────────

    @Test
    fun `install markers pad to equal width for the report listing`() {
        assertEquals("system", DeviceInfoReport.installMarker(true))
        assertEquals("user  ", DeviceInfoReport.installMarker(false))
    }
}
