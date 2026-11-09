package com.openlauncher.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openlauncher.app.tpms.PressureUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "launcher_settings")

class SettingsRepository(private val context: Context) {

    private val gson = Gson()

    private object Keys {
        val VEHICLE_NAME       = stringPreferencesKey("vehicle_name")
        val ACCENT_COLOR       = intPreferencesKey("accent_color")
        val BG_COLOR           = intPreferencesKey("bg_color")
        val FONT_COLOR         = intPreferencesKey("font_color")
        val WALLPAPER_URI      = stringPreferencesKey("wallpaper_uri")
        val FONT_BOLD          = booleanPreferencesKey("font_bold")
        val TEXT_SCALE         = floatPreferencesKey("text_scale")
        val UI_SCALE           = floatPreferencesKey("ui_scale")
        val CLOCK_STYLE        = stringPreferencesKey("clock_style")
        val UNIT_SYSTEM        = stringPreferencesKey("unit_system")
        val APP_FONT           = stringPreferencesKey("app_font")
        val SHORTCUTS_JSON     = stringPreferencesKey("shortcuts_json")
        val CAR_PLAY_PACKAGE      = stringPreferencesKey("car_play_package")
        val ANDROID_AUTO_PACKAGE  = stringPreferencesKey("android_auto_package")
        val USE_GRADIENT          = booleanPreferencesKey("use_gradient")
        val GRADIENT_END_COLOR    = intPreferencesKey("gradient_end_color")
        val WALLPAPER_DIM         = floatPreferencesKey("wallpaper_dim")
        val RIGHT_HAND_DRIVE      = booleanPreferencesKey("right_hand_drive") // kept for migration
        val SIDEBAR_POSITION           = stringPreferencesKey("sidebar_position")
        val BOTTOM_BAR_SHORTCUTS_RIGHT = booleanPreferencesKey("bottom_bar_shortcuts_right")
        val DAY_NIGHT_MODE        = stringPreferencesKey("day_night_mode")
        val SHOW_PIP              = booleanPreferencesKey("show_pip")
        val PIP_APP_PACKAGE       = stringPreferencesKey("pip_app_package")
        val RADIO_PACKAGE         = stringPreferencesKey("radio_package")
        val ONBOARDING_COMPLETED  = booleanPreferencesKey("onboarding_completed")
        val COMPASS_OFFSET        = floatPreferencesKey("compass_offset")
        val SOUNDBOARD_PADS_JSON  = stringPreferencesKey("soundboard_pads_json")
        val BRIGHTNESS_AUTO       = booleanPreferencesKey("brightness_auto")
        val BRIGHTNESS_HIGH_PCT   = intPreferencesKey("brightness_high_pct")
        val BRIGHTNESS_MEDIUM_PCT = intPreferencesKey("brightness_medium_pct")
        val BRIGHTNESS_LOW_PCT    = intPreferencesKey("brightness_low_pct")
        val TPMS_AUTO_CONNECT     = booleanPreferencesKey("tpms_auto_connect")
        val TPMS_PRESSURE_UNIT    = stringPreferencesKey("tpms_pressure_unit")
        val TPMS_LOW_PRESSURE_KPA = floatPreferencesKey("tpms_low_pressure_kpa")
        val TPMS_ALARM_ENABLED    = booleanPreferencesKey("tpms_alarm_enabled")
        val TPMS_ALARM_SOUND      = booleanPreferencesKey("tpms_alarm_sound")
        val VITALS_AS_BARS        = booleanPreferencesKey("vitals_as_bars")
        val SPEEDOMETER_DIGITAL_ONLY = booleanPreferencesKey("speedometer_digital_only")
        val GRADIENT_DIRECTION    = stringPreferencesKey("gradient_direction")
        val USE_CUSTOM_BG_COLOR   = booleanPreferencesKey("use_custom_bg_color")
        val UPDATE_CHANNEL        = stringPreferencesKey("update_channel")

        // Multi-page grid model
        val PAGES_JSON   = stringPreferencesKey("grid_pages_json")
        val WIDGETS_JSON = stringPreferencesKey("widget_instances_json")

        // ── Legacy (single 3×2 grid, one instance per type) — read for migration
        // only; deleted from the store on the next save ──────────────────────────
        val SHOW_WEATHER       = booleanPreferencesKey("show_weather")
        val SHOW_CLOCK         = booleanPreferencesKey("show_clock")
        val SHOW_TELEMETRY     = booleanPreferencesKey("show_telemetry")
        val SHOW_NOW_PLAYING   = booleanPreferencesKey("show_now_playing")
        val SHOW_ALTIMETER     = booleanPreferencesKey("show_altimeter")
        val SHOW_SPEEDOMETER   = booleanPreferencesKey("show_speedometer")
        val SHOW_VITALS        = booleanPreferencesKey("show_vitals")
        val SHOW_TRIP_TRACKER  = booleanPreferencesKey("show_trip_tracker")
        val SHOW_SOUNDBOARD    = booleanPreferencesKey("show_soundboard")
        val SHOW_VOLUME        = booleanPreferencesKey("show_volume")
        val SHOW_BRIGHTNESS    = booleanPreferencesKey("show_brightness")
        val SHOW_TPMS          = booleanPreferencesKey("show_tpms")
        val WIDGET_LAYOUT_JSON = stringPreferencesKey("widget_layout_json")
    }

    /** Legacy showXxx key per widget type, for reading during migration. */
    private val legacyShowKeys = mapOf(
        "CLOCK"        to Keys.SHOW_CLOCK,
        "WEATHER"      to Keys.SHOW_WEATHER,
        "NOW_PLAYING"  to Keys.SHOW_NOW_PLAYING,
        "TELEMETRY"    to Keys.SHOW_TELEMETRY,
        "ALTIMETER"    to Keys.SHOW_ALTIMETER,
        "SPEEDOMETER"  to Keys.SHOW_SPEEDOMETER,
        "VITALS"       to Keys.SHOW_VITALS,
        "TRIP_TRACKER" to Keys.SHOW_TRIP_TRACKER,
        "SOUNDBOARD"   to Keys.SHOW_SOUNDBOARD,
        "VOLUME"       to Keys.SHOW_VOLUME,
        "BRIGHTNESS"   to Keys.SHOW_BRIGHTNESS,
        "TPMS"         to Keys.SHOW_TPMS
    )

    val settingsFlow: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> readSettings(prefs) }

    private fun readSettings(prefs: Preferences): AppSettings {
            val defaults = AppSettings()

            val shortcutsJson = prefs[Keys.SHORTCUTS_JSON]
            val shortcuts = if (shortcutsJson != null) {
                runCatching {
                    gson.fromJson<List<ShortcutConfig>>(
                        shortcutsJson,
                        object : TypeToken<List<ShortcutConfig>>() {}.type
                    )
                }.getOrNull() ?: defaults.shortcuts
            } else defaults.shortcuts

            // ── Widget grid: new model first, legacy singleton layout migrated once ──
            val widgetsJson = prefs[Keys.WIDGETS_JSON]
            val pages: List<GridPage>
            val widgets: List<WidgetInstance>
            if (widgetsJson != null) {
                pages = prefs[Keys.PAGES_JSON]?.let { json ->
                    runCatching {
                        gson.fromJson<List<GridPage>>(json, object : TypeToken<List<GridPage>>() {}.type)
                    }.getOrNull()
                }?.filter { it.cols > 0 && it.rows > 0 }?.takeIf { it.isNotEmpty() } ?: defaultPages()
                val loaded = runCatching {
                    gson.fromJson<List<WidgetInstance>>(
                        widgetsJson,
                        object : TypeToken<List<WidgetInstance>>() {}.type
                    )
                }.getOrNull() ?: defaultWidgetInstances()
                // Defensive: a hand-corrupted pages list must not leave cards on a missing page
                widgets = loaded.map { it.copy(page = it.page.coerceIn(0, pages.size - 1)) }
            } else {
                val legacyJson = prefs[Keys.WIDGET_LAYOUT_JSON]
                val legacy = legacyJson?.let { json ->
                    runCatching {
                        gson.fromJson<List<LegacyWidget>>(json, object : TypeToken<List<LegacyWidget>>() {}.type)
                    }.getOrNull()
                }
                if (legacy.isNullOrEmpty()) {
                    pages = defaultPages(); widgets = defaultWidgetInstances()
                } else if (legacy.none { (it.gridX ?: 0) >= 2 }) {
                    // Migrate: old 2×2 layout has no widget with gridX≥2 — replace with current defaults
                    pages = defaultPages(); widgets = defaultWidgetInstances()
                } else {
                    val on  = legacyShowKeys.filterValues { prefs[it] == true  }.keys
                    val off = legacyShowKeys.filterValues { prefs[it] == false }.keys
                    val (page, migrated) = migrateLegacyLayout(legacy, on, off)
                    pages = listOf(page); widgets = migrated
                }
            }

            return AppSettings(
                vehicleName    = prefs[Keys.VEHICLE_NAME]     ?: defaults.vehicleName,
                accentColor    = prefs[Keys.ACCENT_COLOR]     ?: defaults.accentColor,
                backgroundColor = prefs[Keys.BG_COLOR]        ?: defaults.backgroundColor,
                fontColor      = prefs[Keys.FONT_COLOR]       ?: defaults.fontColor,
                wallpaperUri   = prefs[Keys.WALLPAPER_URI]    ?: defaults.wallpaperUri,
                fontBold       = prefs[Keys.FONT_BOLD]        ?: defaults.fontBold,
                textScale      = prefs[Keys.TEXT_SCALE]       ?: defaults.textScale,
                uiScale        = prefs[Keys.UI_SCALE]         ?: defaults.uiScale,
                clockStyle     = prefs[Keys.CLOCK_STYLE]?.let { runCatching { ClockStyle.valueOf(it) }.getOrNull() } ?: defaults.clockStyle,
                unitSystem     = prefs[Keys.UNIT_SYSTEM]?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() } ?: defaults.unitSystem,
                appFont        = prefs[Keys.APP_FONT]?.let { runCatching { AppFont.valueOf(it) }.getOrNull() } ?: defaults.appFont,
                shortcuts      = shortcuts,
                pages          = pages,
                widgets        = widgets,
                carPlayPackage      = prefs[Keys.CAR_PLAY_PACKAGE]      ?: defaults.carPlayPackage,
                androidAutoPackage  = prefs[Keys.ANDROID_AUTO_PACKAGE]  ?: defaults.androidAutoPackage,
                useGradient      = prefs[Keys.USE_GRADIENT]        ?: defaults.useGradient,
                gradientEndColor = prefs[Keys.GRADIENT_END_COLOR]  ?: defaults.gradientEndColor,
                wallpaperDim     = prefs[Keys.WALLPAPER_DIM]       ?: defaults.wallpaperDim,
                sidebarPosition  = prefs[Keys.SIDEBAR_POSITION]?.let { runCatching { SidebarPosition.valueOf(it) }.getOrNull() }
                                   ?: if (prefs[Keys.RIGHT_HAND_DRIVE] == true) SidebarPosition.RIGHT else defaults.sidebarPosition,
                bottomBarShortcutsRight = prefs[Keys.BOTTOM_BAR_SHORTCUTS_RIGHT] ?: defaults.bottomBarShortcutsRight,
                dayNightMode     = prefs[Keys.DAY_NIGHT_MODE]?.let { runCatching { DayNightMode.valueOf(it) }.getOrNull() } ?: defaults.dayNightMode,
                showPip          = prefs[Keys.SHOW_PIP]         ?: defaults.showPip,
                pipAppPackage    = prefs[Keys.PIP_APP_PACKAGE]  ?: defaults.pipAppPackage,
                radioPackage     = prefs[Keys.RADIO_PACKAGE]    ?: defaults.radioPackage,
                onboardingCompleted = prefs[Keys.ONBOARDING_COMPLETED] ?: defaults.onboardingCompleted,
                compassOffset    = prefs[Keys.COMPASS_OFFSET]    ?: defaults.compassOffset,
                brightnessAuto   = prefs[Keys.BRIGHTNESS_AUTO]   ?: defaults.brightnessAuto,
                brightnessHighPct   = prefs[Keys.BRIGHTNESS_HIGH_PCT]?.coerceIn(1, 100)   ?: defaults.brightnessHighPct,
                brightnessMediumPct = prefs[Keys.BRIGHTNESS_MEDIUM_PCT]?.coerceIn(1, 100) ?: defaults.brightnessMediumPct,
                brightnessLowPct    = prefs[Keys.BRIGHTNESS_LOW_PCT]?.coerceIn(1, 100)    ?: defaults.brightnessLowPct,
                tpmsAutoConnect  = prefs[Keys.TPMS_AUTO_CONNECT] ?: defaults.tpmsAutoConnect,
                tpmsPressureUnit = prefs[Keys.TPMS_PRESSURE_UNIT]?.let {
                    runCatching { PressureUnit.valueOf(it) }.getOrNull()
                } ?: defaults.tpmsPressureUnit,
                tpmsLowPressureKpa = (prefs[Keys.TPMS_LOW_PRESSURE_KPA] ?: defaults.tpmsLowPressureKpa)
                    .coerceIn(PressureUnit.MIN_THRESHOLD_KPA, PressureUnit.MAX_THRESHOLD_KPA),
                tpmsAlarmEnabled = prefs[Keys.TPMS_ALARM_ENABLED] ?: defaults.tpmsAlarmEnabled,
                tpmsAlarmSound   = prefs[Keys.TPMS_ALARM_SOUND] ?: defaults.tpmsAlarmSound,
                soundboardPads   = prefs[Keys.SOUNDBOARD_PADS_JSON]?.let {
                    runCatching {
                        gson.fromJson<List<SoundPadConfig>>(it, object : com.google.gson.reflect.TypeToken<List<SoundPadConfig>>() {}.type)
                    }.getOrNull()
                } ?: defaults.soundboardPads,
                vitalsAsBars     = prefs[Keys.VITALS_AS_BARS] ?: defaults.vitalsAsBars,
                speedometerDigitalOnly = prefs[Keys.SPEEDOMETER_DIGITAL_ONLY] ?: defaults.speedometerDigitalOnly,
                gradientDirection = prefs[Keys.GRADIENT_DIRECTION]?.let { runCatching { GradientDirection.valueOf(it) }.getOrNull() } ?: defaults.gradientDirection,
                useCustomBackgroundColor = prefs[Keys.USE_CUSTOM_BG_COLOR] ?: defaults.useCustomBackgroundColor,
                 updateChannel = prefs[Keys.UPDATE_CHANNEL]?.let {
                     runCatching { UpdateChannel.valueOf(it) }.getOrNull()?.name ?: defaults.updateChannel
                 } ?: defaults.updateChannel
            )
    }

    suspend fun saveSettings(s: AppSettings) {
        context.dataStore.edit { prefs -> writeSettings(prefs, s) }
    }

    /**
     * Atomic read-modify-write. DataStore serializes edit blocks, so concurrent
     * updates can't overwrite each other (unlike transforming a stale snapshot).
     */
    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs -> writeSettings(prefs, transform(readSettings(prefs))) }
    }

    private fun writeSettings(prefs: MutablePreferences, s: AppSettings) {
            prefs[Keys.VEHICLE_NAME]       = s.vehicleName
            prefs[Keys.ACCENT_COLOR]       = s.accentColor
            prefs[Keys.BG_COLOR]           = s.backgroundColor
            prefs[Keys.FONT_COLOR]         = s.fontColor
            prefs[Keys.WALLPAPER_URI]      = s.wallpaperUri
            prefs[Keys.FONT_BOLD]          = s.fontBold
            prefs[Keys.TEXT_SCALE]         = s.textScale
            prefs[Keys.UI_SCALE]           = s.uiScale
            prefs[Keys.CLOCK_STYLE]        = s.clockStyle.name
            prefs[Keys.UNIT_SYSTEM]        = s.unitSystem.name
            prefs[Keys.APP_FONT]           = s.appFont.name
            prefs[Keys.SHORTCUTS_JSON]     = gson.toJson(s.shortcuts)
            prefs[Keys.PAGES_JSON]         = gson.toJson(s.pages)
            prefs[Keys.WIDGETS_JSON]       = gson.toJson(s.widgets)
            prefs[Keys.CAR_PLAY_PACKAGE]      = s.carPlayPackage
            prefs[Keys.ANDROID_AUTO_PACKAGE]  = s.androidAutoPackage
            prefs[Keys.USE_GRADIENT]       = s.useGradient
            prefs[Keys.GRADIENT_END_COLOR]  = s.gradientEndColor
            prefs[Keys.WALLPAPER_DIM]      = s.wallpaperDim
            prefs[Keys.SIDEBAR_POSITION]           = s.sidebarPosition.name
            prefs[Keys.BOTTOM_BAR_SHORTCUTS_RIGHT] = s.bottomBarShortcutsRight
            prefs[Keys.DAY_NIGHT_MODE]     = s.dayNightMode.name
            prefs[Keys.SHOW_PIP]           = s.showPip
            prefs[Keys.PIP_APP_PACKAGE]    = s.pipAppPackage
            prefs[Keys.RADIO_PACKAGE]      = s.radioPackage
            prefs[Keys.ONBOARDING_COMPLETED] = s.onboardingCompleted
            prefs[Keys.COMPASS_OFFSET]     = s.compassOffset
            prefs[Keys.SOUNDBOARD_PADS_JSON] = gson.toJson(s.soundboardPads)
            prefs[Keys.BRIGHTNESS_AUTO]    = s.brightnessAuto
            prefs[Keys.BRIGHTNESS_HIGH_PCT]   = s.brightnessHighPct
            prefs[Keys.BRIGHTNESS_MEDIUM_PCT] = s.brightnessMediumPct
            prefs[Keys.BRIGHTNESS_LOW_PCT]    = s.brightnessLowPct
            prefs[Keys.TPMS_AUTO_CONNECT]  = s.tpmsAutoConnect
            prefs[Keys.TPMS_PRESSURE_UNIT] = s.tpmsPressureUnit.name
            prefs[Keys.TPMS_LOW_PRESSURE_KPA] = s.tpmsLowPressureKpa
            prefs[Keys.TPMS_ALARM_ENABLED] = s.tpmsAlarmEnabled
            prefs[Keys.TPMS_ALARM_SOUND]   = s.tpmsAlarmSound
            prefs[Keys.VITALS_AS_BARS]     = s.vitalsAsBars
            prefs[Keys.SPEEDOMETER_DIGITAL_ONLY] = s.speedometerDigitalOnly
            prefs[Keys.GRADIENT_DIRECTION] = s.gradientDirection.name
            prefs[Keys.USE_CUSTOM_BG_COLOR] = s.useCustomBackgroundColor
            prefs[Keys.UPDATE_CHANNEL]     = s.updateChannel

            // Once the new model has been written the legacy keys are dead weight —
            // drop them so a downgrade never reads a half-migrated store again.
            listOf(
                Keys.SHOW_WEATHER, Keys.SHOW_CLOCK, Keys.SHOW_TELEMETRY, Keys.SHOW_NOW_PLAYING,
                Keys.SHOW_ALTIMETER, Keys.SHOW_SPEEDOMETER, Keys.SHOW_VITALS, Keys.SHOW_TRIP_TRACKER,
                Keys.SHOW_SOUNDBOARD, Keys.SHOW_VOLUME, Keys.SHOW_BRIGHTNESS, Keys.SHOW_TPMS,
                Keys.WIDGET_LAYOUT_JSON
            ).forEach { prefs.remove(it) }
    }

    suspend fun resetToDefaults() {
        context.dataStore.edit { it.clear() }
    }
}
