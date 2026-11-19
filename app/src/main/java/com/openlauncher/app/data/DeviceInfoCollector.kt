package com.openlauncher.app.data

import android.app.ActivityManager
import android.app.UiModeManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.hardware.usb.UsbManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import com.openlauncher.app.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Developer diagnostics snapshot (Settings ▸ Diagnostics ▸ Collect Device Report).
 *
 * Gathers everything that helps build head-unit features: SoC/build properties,
 * Android version, screen geometry, memory/storage, sensor inventory, input
 * devices (steering-wheel controllers show up here), audio configuration, the
 * MCU/radio backends, connected USB devices, power state, the installed-package
 * list (classified for known head-unit vendors via [DeviceInfoReport]), and the
 * launcher's own permission/grant state — the same grants the widget cards die
 * without, so a report explains a dead card at a glance.
 *
 * Design rules:
 *  - Never crash. Old aftermarket units throw from assorted APIs; every field
 *    is caught individually so one failure only costs that line.
 *  - No private data: no serials, no account names, no phone-book/contact data.
 *    Installed packages and Settings-provider dumps are device configuration,
 *    not personal content, but the UI still warns to review before sharing.
 *  - Runs off the main thread (file + binder queries).
 */
object DeviceInfoCollector {

    fun collect(context: Context, pipAppPackage: String = ""): String = buildString {
        line("OPENLAUNCHER DEVICE REPORT")
        line("generated ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        line("NOTE: lists installed apps and system settings — review before sharing")

        section("App") {
            field("package") { context.packageName }
            field("version") { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.GIT_SHA}" }
            // 1000 = android.uid.system shared UID (only reachable by the
            // aospDebug platform-signed experiment build; proves key match).
            field("process uid") {
                val uid = android.system.Os.getuid()
                if (uid == 1000) "1000 (android.uid.system — SHARED SYSTEM UID ACTIVE)" else "$uid"
            }
            field("installed") {
                val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                "first ${date(pi.firstInstallTime)}, last update ${date(pi.lastUpdateTime)}"
            }
        }

        section("System") {
            field("manufacturer") { Build.MANUFACTURER }
            field("brand") { Build.BRAND }
            field("model") { Build.MODEL }
            field("device") { Build.DEVICE }
            field("product") { Build.PRODUCT }
            field("board") { Build.BOARD }
            field("hardware") { Build.HARDWARE }
            if (Build.VERSION.SDK_INT >= 31) {
                field("soc") { "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" }
            }
            field("bootloader") { Build.BOOTLOADER }
            field("baseband (radio fw)") { Build.getRadioVersion() }
            field("android") { "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }
            field("security patch") { Build.VERSION.SECURITY_PATCH }
            field("build") { "${Build.TYPE}/${Build.TAGS} ${Build.ID} inc ${Build.VERSION.INCREMENTAL}" }
            field("fingerprint") { Build.FINGERPRINT }
            field("kernel") { System.getProperty("os.version") }
            field("abi") { Build.SUPPORTED_ABIS.joinToString() }
            field("cpu cores") { Runtime.getRuntime().availableProcessors() }
            field("uptime") { msHuman(SystemClock.elapsedRealtime()) }
            // UiModeManager.MODE_CAR = 2 / MODE_DESK = 1: whether the unit identifies as a car
            @Suppress("DEPRECATION")
            field("ui mode") {
                when ((context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType) {
                    1 -> "desk"; 2 -> "car"; 3 -> "television"; 4 -> "auto-hide"
                    null -> "n/a"; else -> "other"
                }
            }
        }

        section("Screen") {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            @Suppress("DEPRECATION")
            val d = wm.defaultDisplay
            val dm = DisplayMetrics().also { d.getRealMetrics(it) }
            field("size px") { "${dm.widthPixels} x ${dm.heightPixels}" }
            field("density") { "${dm.densityDpi} dpi (${dm.density}x)" }
            if (dm.xdpi > 0f && dm.ydpi > 0f) {
                val diag = kotlin.math.hypot(dm.widthPixels / dm.xdpi, dm.heightPixels / dm.ydpi)
                field("diagonal") { "%.1f in".format(diag) }
                field("xdpi x ydpi") { "%.0f x %.0f".format(dm.xdpi, dm.ydpi) }
            }
            @Suppress("DEPRECATION")
            field("refresh rate") { "%.1f Hz".format(d.refreshRate) }
            field("rotation") { d.rotation }
            field("touch") {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            }
            // Extra displays (rear-seat screens, instrument clusters) matter for HU features
            val displays = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.displays
            if (displays != null && displays.size > 1) {
                for (dd in displays) field("display ${dd.displayId}") { "${dd.name} state=${dd.state}" }
            }
        }

        section("Memory & storage") {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            field("RAM total") { bytesHuman(mem.totalMem) }
            field("RAM available") { bytesHuman(mem.availMem) }
            field("internal storage") {
                // applicationInfo.dataDir works back to API 1 (Context.dataDir is API 24)
                val data = StatFs(context.applicationInfo.dataDir)
                "${bytesHuman(data.totalBytes)} total, ${bytesHuman(data.availableBytes)} free"
            }
            runCatching {
                val ext = StatFs(android.os.Environment.getExternalStorageDirectory().path)
                field("external storage") { "${bytesHuman(ext.totalBytes)} total, ${bytesHuman(ext.availableBytes)} free" }
            }
        }

        section("Sensors") {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val sensors = sm?.getSensorList(android.hardware.Sensor.TYPE_ALL)?.sortedBy { it.name }
                ?: emptyList()
            field("count") { sensors.size }
            for (s in sensors.take(80)) {
                line("  ${DeviceInfoReport.sensorTypeName(s.type)}: ${s.name} — ${s.vendor}" +
                    " (max ${s.maximumRange}, ${s.power} mA)")
            }
        }

        section("Input devices (steering-wheel & remotes)") {
            val im = context.getSystemService(Context.INPUT_SERVICE) as InputManager
            // getDevices(int) is a hidden system API; the public enumeration path is ids + lookup
            val ids = im.inputDeviceIds
            val devices = ids.map { id -> im.getInputDevice(id) }.filterNotNull()
            field("count") { devices.size }
            for (dev in devices) {
                // vid:pid identifies steering-wheel / remote adapters on real units
                val vp = if (dev.vendorId != 0 || dev.productId != 0)
                    " vid:pid=%04x:%04x".format(dev.vendorId, dev.productId) else ""
                line("  [${dev.id}] ${dev.name}$vp  sources=%x  keyboardType=${dev.keyboardType}".format(dev.sources))
            }
        }

        section("Audio") {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val pm = context.packageManager
            field("audio output feature") { pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT) }
            field("mic feature") { pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }
            field("sample rate") { am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }
            field("frames per buffer") { am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER) }
            field("mode") {
                when (am.mode) { AudioManager.MODE_NORMAL -> "normal"
                    AudioManager.MODE_IN_CALL -> "in-call"
                    AudioManager.MODE_IN_COMMUNICATION -> "in-communication"
                    else -> am.mode.toString() }
            }
            field("music active") { am.isMusicActive }
            for ((name, stream) in listOf(
                "voice" to AudioManager.STREAM_VOICE_CALL,
                "system" to AudioManager.STREAM_SYSTEM,
                "ring" to AudioManager.STREAM_RING,
                "music" to AudioManager.STREAM_MUSIC,
                "alarm" to AudioManager.STREAM_ALARM,
                "notification" to AudioManager.STREAM_NOTIFICATION
            )) field("volume $name") { "${am.getStreamVolume(stream)} / ${am.getStreamMaxVolume(stream)}" }
        }

        section("Radio & MCU backend") {
            val pm = context.packageManager
            for (pkg in listOf("com.szchoiceway.radio", "com.szchoiceway.eventcenter", "com.szchoiceway.media")) {
                field(pkg) {
                    runCatching { pm.getPackageInfo(pkg, 0).versionName ?: "installed" }.getOrElse { "not installed" }
                }
            }
            // The MCU live-radio JSON the radio card mirrors; full value is short
            field("Settings.Global SYS_MEDIA_INFO_JSON") {
                Settings.Global.getString(context.contentResolver, "SYS_MEDIA_INFO_JSON")
            }
            field("android.car framework") {
                runCatching { Class.forName("android.car.Car") }.isSuccess
            }
        }

        section("Settings.Global dump (MCU units keep vehicle keys here)") {
            dumpSettings(this, context, Settings.Global.CONTENT_URI, maxRows = 300)
        }

        section("Settings.System dump") {
            dumpSettings(this, context, Settings.System.CONTENT_URI, maxRows = 300)
        }

        section("USB devices attached") {
            val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val devices = um?.deviceList?.values?.sortedBy { it.deviceName } ?: emptyList()
            field("count") { devices.size }
            field("usb host feature") {
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)
            }
            for (dev in devices) {
                // Serial deliberately omitted (permission-gated + identifying)
                line("  ${dev.deviceName}  vid:pid=%04x:%04x  class=%d".format(
                    dev.vendorId, dev.productId, dev.deviceClass))
            }
        }

        section("Connectivity") {
            val pm = context.packageManager
            for ((label, feature) in listOf(
                "wifi" to PackageManager.FEATURE_WIFI,
                "bluetooth" to PackageManager.FEATURE_BLUETOOTH,
                "nfc" to PackageManager.FEATURE_NFC,
                "gps" to PackageManager.FEATURE_LOCATION_GPS,
                "telephony" to PackageManager.FEATURE_TELEPHONY,
                "ethernet" to "android.hardware.ethernet",
                "automotive type" to "android.hardware.type.automotive",
                "usb host" to PackageManager.FEATURE_USB_HOST,
                "usb accessory" to PackageManager.FEATURE_USB_ACCESSORY,
                "leanback (Android TV)" to "android.software.leanback"
            )) field(label) { pm.hasSystemFeature(feature) }

            field("wifi enabled") {
                (context.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled
            }
            field("bluetooth enabled") {
                (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled
            }
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (Build.VERSION.SDK_INT >= 23) {
                field("active network") {
                    val net = cm.activeNetwork
                    val caps = net?.let { cm.getNetworkCapabilities(it) }
                    if (caps == null) "none" else {
                        val transports = listOf(
                            "wifi" to android.net.NetworkCapabilities.TRANSPORT_WIFI,
                            "cell" to android.net.NetworkCapabilities.TRANSPORT_CELLULAR,
                            "eth" to android.net.NetworkCapabilities.TRANSPORT_ETHERNET,
                            "bt" to android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH
                        ).filter { caps.hasTransport(it.second) }.map { it.first }
                        if (transports.isEmpty()) "connected (other transport)" else transports.joinToString("+")
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                field("active network") { cm.activeNetworkInfo?.type }
            }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            field("location providers") { lm?.allProviders?.joinToString() }
        }

        section("Power") {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (sticky != null) {
                field("level") { "${sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)} / ${sticky.getIntExtra(BatteryManager.EXTRA_SCALE, -1)}" }
                field("voltage") { "${sticky.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)} mV" }
                field("technology") { sticky.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) }
                field("plugged") {
                    when (sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                        BatteryManager.BATTERY_PLUGGED_AC -> "AC"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"; 0 -> "unplugged"
                        else -> "other" }
                }
                field("status") {
                    when (sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                        BatteryManager.BATTERY_STATUS_FULL -> "full"
                        else -> "unknown" }
                }
            }
        }

        section("Launcher grants (why a card shows dash)") {
            val pm = context.packageManager
            field("default home app") {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addCategory(Intent.CATEGORY_DEFAULT)
                pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            }
            field("location (fine)") {
                pm.checkPermission(android.Manifest.permission.ACCESS_FINE_LOCATION, context.packageName) ==
                    PackageManager.PERMISSION_GRANTED
            }
            field("overlay windows") {
                if (Build.VERSION.SDK_INT >= 23) Settings.canDrawOverlays(context) else true
            }
            field("write system settings") {
                if (Build.VERSION.SDK_INT >= 23) Settings.System.canWrite(context) else true
            }
            field("install unknown apps") {
                pm.checkPermission("android.permission.REQUEST_INSTALL_PACKAGES", context.packageName) ==
                    PackageManager.PERMISSION_GRANTED
            }
            field("notification listener") {
                Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                    ?.contains(context.packageName) == true
            }
            field("usage access (enables crash post-mortems of other apps)") {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                val now = System.currentTimeMillis()
                runCatching {
                    usm.queryUsageStats(android.app.usage.UsageStatsManager.INTERVAL_DAILY, now - 86_400_000L, now)
                    "granted"
                }.getOrElse { "not granted" }
            }
        }

        section("APP OVERLAY freeform probe (PIP diagnostics)") {
            val pm = context.packageManager
            // The two framework signals that decide whether a normal app may
            // open a freeform window here. Both reads guarded — old units lock
            // Settings providers down.
            val feature = runCatching {
                pm.hasSystemFeature("android.software.freeform_window_management")
            }.getOrDefault(false)
            val enabler = runCatching {
                Settings.Global.getString(context.contentResolver, "enable_freeform_support")
            }.getOrNull()
            field("feature freeform_window_management") { feature }
            field("global enable_freeform_support") { enabler ?: "unset" }
            field("global force_resizable_activities") {
                runCatching { Settings.Global.getString(context.contentResolver, "force_resizable_activities") }
                    .getOrNull() ?: "unset"
            }
            line("  verdict = ${DeviceInfoReport.freeformSupportLine(feature, enabler)}")
            field("vendor float-window app (com.spd.floatwindow)") {
                runCatching {
                    "installed, v${pm.getPackageInfo("com.spd.floatwindow", 0).versionName}"
                }.getOrDefault("not installed")
            }
            // Component surface of the vendor floating-window stack: if it exposes
            // an exported activity/receiver, the launcher can hand an app (Maps) to
            // the ROM's OWN windowing feature - no freeform support or platform
            // signature needed. Enumeration only; nothing is launched from here.
            runCatching {
                val fwPkg = "com.spd.floatwindow"
                val fwPi = pm.getPackageInfo(
                    fwPkg, PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS
                )
                pm.getLaunchIntentForPackage(fwPkg)?.component?.let {
                    line("  floatwindow main activity = $it")
                }
                fwPi.activities?.forEach {
                    line("  floatwindow act ${it.name} exported=${it.exported} perm=${it.permission ?: "none"}")
                }
                fwPi.receivers?.forEach {
                    line("  floatwindow rcv ${it.name} exported=${it.exported} perm=${it.permission ?: "none"}")
                }
            }.onFailure { line("  floatwindow components: package not installed") }
            // API 30 public post-mortem: why did the vendor float-window process
            // exit right after our dev-button launch? "Opens for a second then
            // bounces back" = exit-self (control-panel activity) vs CRASH - and
            // this ROM family hides crash dialogs (hide_error_dialogs=1).
            // Needs the Usage-access grant; guarded where the ROM denies it.
            if (Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val exits = am.getHistoricalProcessExitReasons("com.spd.floatwindow", 0, 5)
                    if (exits.isEmpty()) line("  floatwindow exits: none recorded (process never ran?)")
                    exits.forEach { e ->
                        line("  floatwindow exit: ${DeviceInfoReport.appExitReasonName(e.reason)}" +
                            " at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.timestamp))}" +
                            " pid=${e.pid} desc=${e.description ?: "-"}")
                    }
                }.onFailure {
                    line("  floatwindow exits: not readable by a normal app (needs system privilege) " +
                        "- even with Usage access, API 30 gates this behind a signature permission. " +
                        "Was: ${it.javaClass.simpleName}")
                }
                // Own exit history needs no grant (the cross-app query above is
                // blocked by a hard PACKAGE_USAGE_STATS signature check on API 30,
                // even with Usage access granted). Cheap launcher-crash trail for
                // future benches — shows why the launcher process restarted.
                runCatching {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    val own = am.getHistoricalProcessExitReasons(context.packageName, 0, 3)
                    if (own.isEmpty()) line("  launcher exits (own): none recorded")
                    own.forEach { e ->
                        line("  launcher exit: ${DeviceInfoReport.appExitReasonName(e.reason)}" +
                            " at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(e.timestamp))}" +
                            " desc=${e.description ?: "-"}")
                    }
                }.onFailure { line("  launcher exits (own): unavailable (${it.javaClass.simpleName}: ${it.message})") }
            }
            // Existence probe only — the app never executes su. Its presence
            // decides whether a root freeform launcher (su -c am start
            // --windowingMode 5 …) could be added as an extra chain rung.
            line("  root = ${DeviceInfoReport.suSummary(
                DeviceInfoReport.suCandidatePaths.filter { java.io.File(it).exists() }
            )}")
            if (pipAppPackage.isEmpty()) {
                line("  pip app = (none assigned — APP OVERLAY card menu ▸ CHOOSE APP)")
            } else {
                line("  pip app = $pipAppPackage")
                val installedVersion = runCatching {
                    pm.getPackageInfo(pipAppPackage, 0).versionName ?: "?"
                }.getOrNull()
                line("  pip app installed = ${installedVersion ?: "NOT INSTALLED"}")
                // Does the assigned app opt out of resizing? If it does, even a
                // freeform-capable ROM may force it fullscreen. The manifest
                // boolean lives in @hide fields (ApplicationInfo/ActivityInfo.
                // resizeableActivity) — read by reflection where the ROM allows
                // it, and fall back to the public FLAG_RESIZEABLE_FOR_TASK bit.
                val appFlag = runCatching {
                    val ai = pm.getApplicationInfo(pipAppPackage, 0)
                    android.content.pm.ApplicationInfo::class.java
                        .getField("resizeableActivity").getBoolean(ai)
                }.getOrNull()
                val actFlags = runCatching {
                    val pi = pm.getPackageInfo(pipAppPackage, PackageManager.GET_ACTIVITIES)
                    (pi.activities ?: emptyArray()).map { a ->
                        runCatching {
                            android.content.pm.ActivityInfo::class.java
                                .getField("resizeableActivity").getBoolean(a)
                        }.getOrDefault((a.flags and 0x00200000) != 0) // ActivityInfo.FLAG_RESIZEABLE_FOR_TASK (bit fixed in AOSP)
                    }
                }.getOrDefault(emptyList())
                line("  pip app resizable = ${DeviceInfoReport.resizabilitySummary(appFlag, actFlags)}")
            }
            // What the last APP OVERLAY taps actually did (which attempt threw
            // what) — logcat without needing adb attached.
            line("  last app-overlay launches (this process):")
            val attempts = com.openlauncher.app.ui.widget.PipLaunchLog.snapshot()
            if (attempts.isEmpty()) line("    (none since launcher start)")
            attempts.forEach { line("    $it") }
            // APP EMBED card engine chain outcomes (gate + which engine threw
            // what) — the bench verdict helper for the TaskEmbedder path.
            line("  last app-embed engine events (this process):")
            val embedEvents = com.openlauncher.app.ui.widget.TaskEmbedLog.snapshot()
            if (embedEvents.isEmpty()) line("    (none since launcher start)")
            embedEvents.forEach { line("    $it") }
        }

        section("Installed packages") {
            val apps = context.packageManager.getInstalledApplications(0)
            val names = apps.map { it.packageName }.sorted()
            field("total") { names.size }
            field("user-installed") { apps.count { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 } }

            val classified = DeviceInfoReport.classifyPackages(names)
            if (classified.isNotEmpty()) {
                line("  — head-unit subsystems detected —")
                for ((subsystem, hits) in classified) {
                    line("  [$subsystem]")
                    for (pkg in hits) line("    $pkg")
                }
            }
            line("  — full list —")
            val systemByPkg = apps.associate { it.packageName to (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) }
            for (pkg in names) {
                line("  ${DeviceInfoReport.installMarker(systemByPkg[pkg] == true)} $pkg")
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun StringBuilder.line(s: String) { append(s); append('\n') }

    /** Section header; a throwing section can't take down the whole report. */
    private inline fun StringBuilder.section(title: String, block: StringBuilder.() -> Unit) {
        line("")
        line("=== ${title.uppercase(Locale.US)} ===")
        try { block() } catch (t: Throwable) { line("  (section failed: ${t.javaClass.simpleName}: ${t.message})") }
    }

    /** One key=value line; each field fails independently and reports why. */
    private inline fun StringBuilder.field(name: String, value: () -> Any?) {
        append("  $name = ")
        append(try { value()?.toString() ?: "n/a" }
               catch (t: Throwable) { "error: ${t.javaClass.simpleName}" })
        append('\n')
    }

    /**
     * Dump a Settings provider table (key = value). Some units lock whole-table
     * reads down; SecurityException then just kills this section. Values are
     * truncated so long JSON blobs don't bloat the report.
     */
    private fun dumpSettings(sb: StringBuilder, context: Context, uri: android.net.Uri, maxRows: Int) {
        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(uri, null, null, null, null)
            if (cursor == null) { sb.line("  (provider returned no cursor)"); return }
            val keyCol = cursor.getColumnIndex("name").takeIf { it >= 0 } ?: run {
                sb.line("  (no 'name' column)"); return }
            val valCol = cursor.getColumnIndex("value").takeIf { it >= 0 } ?: -1
            var rows = 0
            while (cursor.moveToNext() && rows < maxRows) {
                val key = cursor.getString(keyCol) ?: continue
                val raw = if (valCol >= 0) cursor.getString(valCol) ?: "" else ""
                val value = if (raw.length > 200) raw.take(200) + "…" else raw
                sb.line("  $key = $value")
                rows++
            }
            if (rows == maxRows) sb.line("  … truncated at $maxRows rows")
        } catch (t: Throwable) {
            sb.line("  (dump failed: ${t.javaClass.simpleName})")
        } finally {
            runCatching { cursor?.close() }
        }
    }

    private fun bytesHuman(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1e9)
        bytes >= 1L shl 20 -> "%.0f MB".format(bytes / 1e6)
        else -> "$bytes B"
    }

    private fun msHuman(ms: Long): String {
        val s = ms / 1000
        return when {
            s >= 86400 -> "${s / 86400}d ${s % 86400 / 3600}h"
            s >= 3600  -> "${s / 3600}h ${s % 3600 / 60}m"
            else       -> "${s / 60}m ${s % 60}s"
        }
    }

    private fun date(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}
