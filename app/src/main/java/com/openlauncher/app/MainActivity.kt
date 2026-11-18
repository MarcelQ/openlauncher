package com.openlauncher.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.SidebarPosition
import com.openlauncher.app.data.GradientDirection
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.ui.components.Sidebar
import com.openlauncher.app.ui.screen.*
import com.openlauncher.app.ui.theme.OpenLauncherTheme
import com.openlauncher.app.viewmodel.LauncherViewModel

class MainActivity : ComponentActivity() {

    private val vm: LauncherViewModel by viewModels()

    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            granted[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            vm.startLocationUpdates()
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        // The manifest's USB_DEVICE_ATTACHED filter raises this activity when the TPMS stick
        // is plugged in — that is how the receiver comes up without touching settings.
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.onUsbDeviceAttached()

        setContent {
            val settingsLoaded by vm.settingsLoaded.collectAsStateWithLifecycle()
            val settings       by vm.settings.collectAsStateWithLifecycle()
            val nav            by vm.nav.collectAsStateWithLifecycle()
            val apps        by vm.apps.collectAsStateWithLifecycle()
            val appsLoading by vm.appsLoading.collectAsStateWithLifecycle()
            val nowPlaying  by vm.nowPlaying.collectAsStateWithLifecycle()
            val weather     by vm.weather.collectAsStateWithLifecycle()
            val location    by vm.location.collectAsStateWithLifecycle()
            val bearing     by vm.compassBearing.collectAsStateWithLifecycle()
            val isWifi      by vm.isWifi.collectAsStateWithLifecycle()
            val isData      by vm.isData.collectAsStateWithLifecycle()
            val isBluetooth by vm.isBluetooth.collectAsStateWithLifecycle()
            val isHotspot   by vm.isHotspot.collectAsStateWithLifecycle()
            val gpsStrength by vm.gpsStrength.collectAsStateWithLifecycle()
            val editMode    by vm.rearrangeMode.collectAsStateWithLifecycle()
            val isDayModeVM by vm.isDayMode.collectAsStateWithLifecycle()
            val hardwareRadio by vm.hardwareRadio.collectAsStateWithLifecycle()
            val tpmsSnapshot  by vm.tpmsSnapshot.collectAsStateWithLifecycle()
            val tpms        by vm.tpmsSnapshot.collectAsStateWithLifecycle()
            val tpmsWeb     by vm.tpmsWebStatus.collectAsStateWithLifecycle()
            val updateState by vm.updateState.collectAsStateWithLifecycle()
            val systemIsDark = isSystemInDarkTheme()
            val isDayMode = if (settings.dayNightMode == DayNightMode.SYSTEM) !systemIsDark else isDayModeVM
            val pickerSlot      by vm.shortcutPickerSlot.collectAsStateWithLifecycle()
            val appPickerTarget by vm.appPickerTarget.collectAsStateWithLifecycle()

            val accent         = Color(settings.accentColor)
            val bg             = if (settings.useCustomBackgroundColor) {
                Color(settings.backgroundColor)
            } else {
                if (isDayMode) Color(0xFFEEEEEE) else Color.Black
            }
            val textColor      = if (isDayMode) Color(0xFF111111) else Color(settings.fontColor)
            val bgGradientEnd  = Color(settings.gradientEndColor)
            val bgBrush        = if (settings.useCustomBackgroundColor && settings.useGradient) {
                val colors = listOf(bg, bgGradientEnd)
                when (settings.gradientDirection) {
                    GradientDirection.TOP_TO_BOTTOM -> androidx.compose.ui.graphics.Brush.verticalGradient(colors)
                    GradientDirection.LEFT_TO_RIGHT -> androidx.compose.ui.graphics.Brush.horizontalGradient(colors)
                    GradientDirection.DIAGONAL -> androidx.compose.ui.graphics.Brush.linearGradient(colors)
                    GradientDirection.RADIAL -> androidx.compose.ui.graphics.Brush.radialGradient(colors)
                }
            } else null

            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density   = baseDensity.density * settings.uiScale,
                    fontScale = baseDensity.fontScale
                )
            ) {
                if (!settingsLoaded) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Black))
                } else OpenLauncherTheme(
                    accent     = accent,
                    background = bg,
                    textColor  = textColor,
                    fontBold   = settings.fontBold,
                    textScale  = settings.textScale,
                    appFont    = settings.appFont,
                    isDayMode  = isDayMode,
                    useCustomBg = settings.useCustomBackgroundColor
                ) {
                if (!settings.onboardingCompleted) {
                    OnboardingScreen(
                        accent = accent,
                        onComplete = {
                            vm.updateSettings { copy(onboardingCompleted = true) }
                            // Start location updates immediately upon completion
                            vm.startLocationUpdates()
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().let { m ->
                        if (bgBrush != null) m.background(bgBrush) else m.background(bg)
                    }) {
                        // Optional wallpaper layer
                        if (settings.wallpaperUri.isNotEmpty()) {
                            AsyncImage(
                                model              = android.net.Uri.parse(settings.wallpaperUri),
                                contentDescription = null,
                                contentScale       = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize()
                            )
                            Box(modifier = Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = settings.wallpaperDim)))
                        }

                        val isBottomBar    = settings.sidebarPosition == SidebarPosition.BOTTOM
                        val layoutDivColor = if (isDayMode) Color(0xFFCCCCCC) else Color(0xFF1A1A1A)

                        val sidebarContent: @Composable () -> Unit = {
                            val sidebarDensity = Density(
                                density = baseDensity.density * (1.0f + (settings.uiScale - 1.0f) * 0.35f),
                                fontScale = baseDensity.fontScale
                            )
                            CompositionLocalProvider(LocalDensity provides sidebarDensity) {
                                Sidebar(
                                    currentDest   = nav,
                                    settings      = settings,
                                    isWifi        = isWifi,
                                    isData        = isData,
                                    isBluetooth   = isBluetooth,
                                    isHotspot     = isHotspot,
                                    onWifiStatusClick      = {
                                        openConnectivitySettings(
                                            29, Settings.Panel.ACTION_WIFI,
                                            Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_SETTINGS
                                        )
                                    },
                                    onHotspotStatusClick   = {
                                        // No panel intent for tethering — try the
                                        // tethering settings page, then wireless
                                        // settings, then plain Settings
                                        openConnectivitySettings(
                                            0, "android.settings.TETHER_SETTINGS",
                                            Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS
                                        )
                                    },
                                    onBluetoothStatusClick = {
                                        // Bluetooth's slide-over panel never had a
                                        // public constant (removed from recent SDK
                                        // jars), but the intent string resolves on
                                        // stock API 29-34 builds; the fallback chain
                                        // catches vendor ROMs (SPD) that lack it
                                        openConnectivitySettings(
                                            29, "android.settings.panel.action.BLUETOOTH",
                                            Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS
                                        )
                                    },
                                    onDataStatusClick      = {
                                        openConnectivitySettings(
                                            30, Settings.Panel.ACTION_INTERNET_CONNECTIVITY,
                                            Settings.ACTION_SETTINGS
                                        )
                                    },
                                    gpsStrength            = gpsStrength,
                                    onGpsStatusClick       = {
                                        // No panel intent for location — Android's
                                        // location sources page, then plain Settings
                                        openConnectivitySettings(
                                            99, null,
                                            Settings.ACTION_LOCATION_SOURCE_SETTINGS, Settings.ACTION_SETTINGS
                                        )
                                    },
                                    isHorizontal  = isBottomBar,
                                    installedIconFor = { pkg ->
                                        apps.find { it.packageName == pkg }?.icon
                                    },
                                    onNavigate    = { dest ->
                                        vm.cancelShortcutPicker()
                                        vm.cancelCarPlayPicker()
                                        vm.exitRearrangeMode()
                                        vm.navigate(dest)
                                    },
                                    onShortcutClick = { slot ->
                                        val shortcut = settings.shortcuts[slot]
                                        if (shortcut.packageName.isNotEmpty()) {
                                            vm.launchApp(shortcut.packageName)
                                        }
                                    },
                                    onShortcutLongPress  = { slot -> vm.startShortcutPicker(slot) },
                                    onShortcutRemove     = { slot -> vm.removeShortcut(slot) },
                                    onShortcutSetIcon    = { slot, icon -> vm.setShortcutIcon(slot, icon) },
                                    onReorder            = { from, to -> vm.reorderShortcut(from, to) },
                                    // Dedicated CarPlay / Android Auto buttons
                                    // (Settings ▸ Vehicle), shown before the app
                                    // shortcut slots
                                    onCarPlayLaunch      = { vm.launchApp(settings.carPlayPackage) },
                                    onAndroidAutoLaunch  = { vm.launchApp(settings.androidAutoPackage) },
                                    // Long-press on the Settings cog enters home-screen
                                    // edit mode (the Edit button in the header was removed)
                                    onRearrangeRequested = {
                                        if (nav == NavDestination.HOME) {
                                            vm.toggleRearrangeMode()
                                        } else {
                                            vm.cancelShortcutPicker()
                                            vm.cancelCarPlayPicker()
                                            vm.navigate(NavDestination.HOME)
                                            vm.setRearrangeMode(true)
                                        }
                                    }
                                )
                            }
                        }

                        val mainPane: @Composable (Modifier) -> Unit = { paneModifier ->
                            // ── Main content pane ─────────────────────────────
                            AnimatedContent(
                                targetState   = nav,
                                transitionSpec = {
                                    fadeIn() + slideInHorizontally { it / 10 } togetherWith
                                    fadeOut() + slideOutHorizontally { -it / 10 }
                                },
                                modifier = paneModifier,
                                label    = "pane_transition"
                            ) { destination ->
                                when (destination) {
                                    NavDestination.HOME -> HomeScreen(
                                        settings            = settings,
                                        weather             = weather,
                                        nowPlaying          = nowPlaying,
                                        location            = location,
                                        bearing             = bearing,
                                        isDayMode           = isDayMode,
                                        editMode            = editMode,
                                        onToggleEditMode    = { vm.toggleRearrangeMode() },
                                        onPlayPause         = vm::playPause,
                                        onNext              = vm::skipNext,
                                        onPrev              = vm::skipPrev,
                                        onVolumeDown        = vm::volumeDown,
                                        onVolumeUp          = vm::volumeUp,
                                        onAssignPip         = { vm.startPipPicker() },
                                        onClearPip          = { vm.clearPipApp() },
                                        onTapNowPlaying     = {
                                            val pkg = nowPlaying?.controller?.packageName
                                            if (!pkg.isNullOrEmpty()) vm.launchApp(pkg)
                                        },
                                        onResizeWidget      = { id, gx, gy, sx, sy -> vm.resizeWidget(id, gx, gy, sx, sy) },
                                        onMoveWidget        = { id, gx, gy -> vm.moveWidgetConfig(id, gx, gy) },
                                        onAddWidget         = { type, page -> vm.addWidget(type, page) },
                                        onRemoveWidget      = { id -> vm.removeWidget(id) },
                                        onMoveWidgetToPage  = { id, page -> vm.moveWidgetToPage(id, page) },
                                        onAddPage           = { vm.addPage() },
                                        onRemovePage        = { page -> vm.removePage(page) },
                                        onSetPageSize       = { page, cols, rows -> vm.setPageSize(page, cols, rows) },
                                        onSetClockStyle     = { style -> vm.updateSettings { copy(clockStyle = style) } },
                                        onSetVitalsAsBars   = { asBars -> vm.updateSettings { copy(vitalsAsBars = asBars) } },
                                        onSetSpeedometerDigitalOnly = { digital -> vm.updateSettings { copy(speedometerDigitalOnly = digital) } },
                                        onUpdateSoundPad    = { idx, pad -> vm.updateSoundboardPad(idx, pad) },
                                        hardwareRadio         = hardwareRadio,
                                        onLaunchHardwareRadio = { vm.launchHardwareRadioApp() },
                                        onStopHardwareRadio   = { vm.stopHardwareRadioApp() },
                                        onRadioSeekUp         = { vm.radioSeekUp() },
                                        onRadioSeekDown       = { vm.radioSeekDown() },
                                        onRadioCycleFm        = { vm.radioCycleFm() },
                                        onRadioSwitchAm       = { vm.radioSwitchAm() },
                                        onRadioTune           = { band, freq -> vm.radioTune(band, freq) },
                                        onAssignRadio         = { vm.startRadioPicker() },
                                        tpms                  = tpmsSnapshot,
                                        onTpmsConnect         = { vm.requestTpmsConnect() },
                                        tpmsWeb               = tpmsWeb,
                                        onTpmsWebToggle       = { vm.toggleTpmsWebServer() },
                                        onSetBrightnessLevel  = { slot, pct -> vm.setBrightnessPct(slot, pct) },
                                        onSetBrightnessAuto   = { on -> vm.setBrightnessAuto(on) }
                                    )

                                    NavDestination.APP_LIBRARY -> AppLibraryScreen(
                                        apps                = apps,
                                        isLoading           = appsLoading,
                                        isPickerMode        = pickerSlot != null,
                                        pickerSlot          = pickerSlot,
                                        isCarPlayPickerMode = appPickerTarget != null,
                                        carPlayPickerLabel  = when (appPickerTarget) {
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.ANDROID_AUTO -> "CHOOSE ANDROID AUTO APP"
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.PIP          -> "CHOOSE PIP APP"
                                            com.openlauncher.app.viewmodel.LauncherViewModel.AppPickerTarget.RADIO        -> "CHOOSE RADIO APP"
                                            else -> "CHOOSE CARPLAY APP"
                                        },
                                        accent              = accent,
                                        onAppClick          = { app -> vm.launchApp(app.packageName) },
                                        onPickerSelect      = { slot, app -> vm.assignShortcut(slot, app) },
                                        onCarPlaySelect     = { app -> vm.assignPickerApp(app) }
                                    )

                                    NavDestination.SETTINGS -> SettingsScreen(
                                        settings           = settings,
                                        accent             = accent,
                                        bearing            = bearing,
                                        onUpdate           = { block -> vm.updateSettings(block) },
                                        onReset            = { vm.resetSettings() },
                                        updateState        = updateState,
                                        onCheckUpdates     = { vm.checkForUpdates() },
                                        onDownloadUpdate   = { vm.downloadAndInstallUpdate() },
                                        onInstallUpdate    = { vm.installDownloadedUpdate() },
                                        onSetUpdateChannel = { ch -> vm.setUpdateChannel(ch) },
                                        onAssignCarPlay     = { vm.startCarPlayPicker() },
                                        onAssignAndroidAuto = { vm.startAndroidAutoPicker() },
                                        onClearCarPlay      = { vm.clearCarPlayApp() },
                                        onClearAndroidAuto  = { vm.clearAndroidAutoApp() },
                                        // Category rail's bottom Home button (the rail
                                        // replaces the launcher sidebar on this screen)
                                        onGoHome            = {
                                            vm.cancelShortcutPicker()
                                            vm.cancelCarPlayPicker()
                                            vm.exitRearrangeMode()
                                            vm.navigate(NavDestination.HOME)
                                        }
                                    )
                                }
                            }
                        }

                        if (isBottomBar) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                mainPane(Modifier.weight(1f).fillMaxWidth())
                                androidx.compose.material3.HorizontalDivider(color = layoutDivColor)
                                sidebarContent()
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxSize()) {
                                val vDivider: @Composable () -> Unit = {
                                    androidx.compose.material3.VerticalDivider(
                                        modifier = Modifier.fillMaxHeight(),
                                        color    = layoutDivColor
                                    )
                                }
                                // On the Settings screen (vertical layouts) the launcher
                                // sidebar is hidden: the category rail inside
                                // SettingsScreen replaces it on the same side.
                                val railReplacesSidebar = nav == NavDestination.SETTINGS
                                if (settings.sidebarPosition == SidebarPosition.LEFT) {
                                    if (!railReplacesSidebar) {
                                        sidebarContent()
                                        vDivider()
                                    }
                                }
                                mainPane(Modifier.weight(1f).fillMaxHeight())
                                if (settings.sidebarPosition == SidebarPosition.RIGHT) {
                                    if (!railReplacesSidebar) {
                                        vDivider()
                                        sidebarContent()
                                    }
                                }
                            }
                        }
                    }
                }
            }
            } // CompositionLocalProvider
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a plug-in while the launcher is already running lands here, not in onCreate
        setIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.onUsbDeviceAttached()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshConnectivity()
        vm.refreshMedia()
    }

    override fun onStop() {
        super.onStop()
        vm.stopLocationUpdates()
    }

    override fun onStart() {
        super.onStart()
        vm.startLocationUpdates()
    }

    // Status-icon hand-off: try Android's slide-over settings panel (API 29/30+)
    // first, then the full settings page, then plain Settings as the last
    // fallback — vendor ROMs (e.g. SPD's com.spd.setting) may lack panel
    // activities, so each step catches ActivityNotFoundException.
    private fun openConnectivitySettings(minPanelSdk: Int, panelAction: String?, vararg fallbackActions: String) {
        if (panelAction != null && Build.VERSION.SDK_INT >= minPanelSdk) {
            try {
                startActivity(Intent(panelAction))
                return
            } catch (_: ActivityNotFoundException) { }
        }
        for (action in fallbackActions) {
            try {
                startActivity(Intent(action))
                return
            } catch (_: ActivityNotFoundException) { }
        }
    }
}
