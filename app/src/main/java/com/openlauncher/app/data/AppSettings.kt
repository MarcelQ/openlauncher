package com.openlauncher.app.data

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.openlauncher.app.BuildConfig
import com.openlauncher.app.tpms.PressureUnit

enum class ClockStyle { DIGITAL, ANALOG }
enum class UnitSystem { METRIC, IMPERIAL }
enum class AppFont { SYSTEM, JETBRAINS_MONO, SOURCE_CODE_PRO }
enum class DayNightMode { DARK, LIGHT, AUTO, SYSTEM }
enum class SidebarPosition { LEFT, RIGHT, BOTTOM }
enum class GradientDirection { TOP_TO_BOTTOM, LEFT_TO_RIGHT, DIAGONAL, RADIAL }

enum class DefaultShortcutIcon {
    NONE,
    // Navigation & vehicle
    RADIO, CAMERA, PHONE, MAP, NAVIGATION, CAR, GAS_STATION, DASHBOARD,
    // Audio & media
    MUSIC, SPEAKER, HEADSET, EQUALIZER, VOLUME_UP,
    // Connectivity
    BLUETOOTH, WIFI,
    // Lighting & climate
    LIGHTBULB, BRIGHTNESS, AC, THERMOSTAT,
    // General utility
    TV, VIDEOCAM, STAR, MESSAGE, TIMER, LOCK, SETTINGS, FAVORITE,
    // Web / location
    GLOBE
}

data class SoundPadConfig(
    val label: String,
    val audioUri: String = "",
    val synthType: String = "BEEP"
)

fun defaultSoundboardPads() = listOf(
    SoundPadConfig("mario_jump",   synthType = "mario_jump"),
    SoundPadConfig("mario_coin",   synthType = "mario_coin"),
    SoundPadConfig("boom",         synthType = "boom"),
    SoundPadConfig("loud_fart",    synthType = "loud_fart"),
    SoundPadConfig("+",            synthType = ""),
    SoundPadConfig("+",            synthType = "")
)

data class ShortcutConfig(
    val packageName: String = "",
    val label: String = "",
    val isDefault: Boolean = false,
    val defaultIcon: DefaultShortcutIcon = DefaultShortcutIcon.NONE,
    // null = native app icon; non-null = override with this vector icon
    val customIconOverride: DefaultShortcutIcon? = null
)

data class AppSettings(
    val vehicleName: String = "MY CAR",
    val accentColor: Int = Color.White.toArgb(),
    val backgroundColor: Int = Color.Black.toArgb(),
    val fontColor: Int = Color.White.toArgb(),
    val wallpaperUri: String = "",
    val fontBold: Boolean = false,
    val textScale: Float = 1.2f,
    val uiScale: Float = 1.0f,
    val clockStyle: ClockStyle = ClockStyle.DIGITAL,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val appFont: AppFont = AppFont.JETBRAINS_MONO,
    val shortcuts: List<ShortcutConfig> = defaultShortcuts(),
    // ── Home screen grid ──────────────────────────────────────────────────────
    // Up to MAX_PAGES swipeable pages, each with its own cols×rows grid.
    // Which cards are shown is decided by `widgets` alone — a widget type is
    // "on" while it has at least one instance, and the same type can appear
    // several times (duplicates). See GridLayout.kt for the placement engine.
    val pages: List<GridPage> = defaultPages(),
    val widgets: List<WidgetInstance> = defaultWidgetInstances(),
    val carPlayPackage: String = "",
    val androidAutoPackage: String = "",
    val useGradient: Boolean = false,
    val gradientEndColor: Int = Color.Black.toArgb(),
    val wallpaperDim: Float = 0.55f,
    val sidebarPosition: SidebarPosition = SidebarPosition.LEFT,
    val bottomBarShortcutsRight: Boolean = false,
    val dayNightMode: DayNightMode = DayNightMode.DARK,
    val showPip: Boolean = false,
    val pipAppPackage: String = "",
    // Head unit's radio app — mirrored & controlled via its MediaSession
    val radioPackage: String = "",
    val onboardingCompleted: Boolean = false,
    val compassOffset: Float = 0f,
    val soundboardPads: List<SoundPadConfig> = defaultSoundboardPads(),
    // ── Screen brightness ─────────────────────────────────────────────────────────
    // Preset levels are 1..100 (% of the SCREEN_BRIGHTNESS range) and only applied
    // when a card button is tapped or brightnessAuto drives them. Auto needs a GPS
    // fix (sun position) and uses the weather card's data for cloud cover. The
    // auto loop only runs while a BRIGHTNESS card exists on some page.
    val brightnessAuto: Boolean = false,
    val brightnessHighPct: Int = 100,
    val brightnessMediumPct: Int = 55,
    val brightnessLowPct: Int = 15,
    // ── TPMS (USB receiver) ───────────────────────────────────────────────────────
    // Off by default: no TPMS instance in the stock layout, so the card is added
    // from the widget library. A TPMS card on any page is what arms the
    // auto-connect loop (see hasWidgetType + LauncherViewModel's settings collector).
    val tpmsAutoConnect: Boolean = true,
    val tpmsPressureUnit: PressureUnit = PressureUnit.BAR,
    /** Alarm threshold, always stored in kPa so changing the display unit can't shift it. */
    val tpmsLowPressureKpa: Float = 200f,
    val vitalsAsBars: Boolean = false,
    val speedometerDigitalOnly: Boolean = false,
    val gradientDirection: GradientDirection = GradientDirection.DIAGONAL,
    val useCustomBackgroundColor: Boolean = false,
    // ── Updates (GitHub rolling releases) ────────────────────────────────────────
    // "DEV" tracks dev-latest (integration builds), "STABLE" tracks main-latest.
    // Debug APKs default to the dev channel, release APKs to main. See UpdateChecker.kt.
    val updateChannel: String = if (BuildConfig.DEBUG) "DEV" else "STABLE"
)

fun defaultShortcuts() = listOf(
    ShortcutConfig(label = "Radio", isDefault = true, defaultIcon = DefaultShortcutIcon.RADIO),
    ShortcutConfig(label = "Camera", isDefault = true, defaultIcon = DefaultShortcutIcon.CAMERA),
    ShortcutConfig(label = "Music", isDefault = true, defaultIcon = DefaultShortcutIcon.MUSIC),
    ShortcutConfig(label = "Phone", isDefault = true, defaultIcon = DefaultShortcutIcon.PHONE)
)

/** True while at least one card of [type] is on any page (drives TPMS auto-connect, auto-brightness…). */
fun AppSettings.hasWidgetType(type: String): Boolean = widgets.any { it.type == type }
