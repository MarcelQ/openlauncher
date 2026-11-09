package com.openlauncher.app.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openlauncher.app.BuildConfig
import com.openlauncher.app.data.ApkDownloader
import com.openlauncher.app.data.AppSettings
import com.openlauncher.app.data.DayNightMode
import com.openlauncher.app.data.DefaultShortcutIcon
import com.openlauncher.app.data.GridPage
import com.openlauncher.app.data.LocalBuild
import com.openlauncher.app.data.MAX_COLS
import com.openlauncher.app.data.MAX_PAGES
import com.openlauncher.app.data.MAX_ROWS
import com.openlauncher.app.data.MIN_COLS
import com.openlauncher.app.data.MIN_ROWS
import com.openlauncher.app.data.SettingsRepository
import com.openlauncher.app.data.ShortcutConfig
import com.openlauncher.app.data.SoundPadConfig
import com.openlauncher.app.data.UpdateApi
import com.openlauncher.app.data.UpdateChannel
import com.openlauncher.app.data.UpdateDecision
import com.openlauncher.app.data.UpdateUiState
import com.openlauncher.app.data.WeatherApi
import com.openlauncher.app.data.WidgetInstance
import com.openlauncher.app.data.buildRemoteUpdate
import com.openlauncher.app.data.computeWidgetMove
import com.openlauncher.app.data.decideUpdate
import com.openlauncher.app.data.defaultShortcuts
import com.openlauncher.app.data.firstFreeArea
import com.openlauncher.app.data.firstFreeCell
import com.openlauncher.app.data.hasWidgetType
import com.openlauncher.app.data.instancesOnPage
import com.openlauncher.app.data.nextInstanceId
import com.openlauncher.app.data.resizePageLayout
import com.openlauncher.app.util.SunriseSunset
import com.openlauncher.app.util.SystemBrightness
import com.openlauncher.app.model.AppInfo
import com.openlauncher.app.model.NavDestination
import com.openlauncher.app.model.NowPlayingState
import com.openlauncher.app.model.WeatherState
import com.openlauncher.app.service.MediaListenerService
import com.openlauncher.app.tpms.TpmsRepository
import com.openlauncher.app.tpms.TpmsSnapshot
import com.openlauncher.app.util.LocationCompassManager
import com.openlauncher.app.util.LocationData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    private val locationMgr  = LocationCompassManager(application)

    // ── Settings ──────────────────────────────────────────────────────────────
    private val _settingsLoaded = MutableStateFlow(false)
    val settingsLoaded: StateFlow<Boolean> = _settingsLoaded

    val settings: StateFlow<AppSettings> = settingsRepo.settingsFlow
        .onEach { _settingsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    fun updateSettings(block: AppSettings.() -> AppSettings) {
        viewModelScope.launch { settingsRepo.updateSettings { it.block() } }
    }

    fun resetSettings() {
        viewModelScope.launch { settingsRepo.resetToDefaults() }
    }

    // ── Updates (rolling GitHub releases: dev-latest / main-latest) ─────────
    // Pure compare/parse logic lives in UpdateChecker.kt (unit-tested); the APK
    // download + installer handoff lives in ApkDownloader.kt. This is the glue.
    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()
    private var updateFlowJob: Job? = null

    private fun localBuild() =
        LocalBuild(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA)

    fun setUpdateChannel(channel: UpdateChannel) {
        // A check result for the old channel must not linger on screen.
        _updateState.value = UpdateUiState.Idle
        updateSettings { copy(updateChannel = channel.name) }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.value = UpdateUiState.Checking
            val channel = UpdateChannel.fromKey(settings.value.updateChannel)
            runCatching { UpdateApi.service.releaseByTag(channel.releaseTag) }.fold(
                onSuccess = { release ->
                    val remote = buildRemoteUpdate(release, channel)
                    if (remote == null) {
                        _updateState.value = UpdateUiState.Failed("Release ${channel.releaseTag} has no APK asset yet")
                    } else when (val d = decideUpdate(localBuild(), remote)) {
                        is UpdateDecision.Available -> _updateState.value = UpdateUiState.UpdateAvailable(remote, d.reason)
                        UpdateDecision.UpToDate     -> _updateState.value = UpdateUiState.UpToDate(remote)
                        UpdateDecision.NoApkAsset   -> _updateState.value = UpdateUiState.Failed("Release ${channel.releaseTag} has no APK asset yet")
                    }
                },
                onFailure = { _updateState.value = UpdateUiState.Failed("Can't reach GitHub — check the network") }
            )
        }
    }

    /** Download the tracked release's APK, verify its versionCode, then open the installer. */
    fun downloadAndInstallUpdate() {
        val remote = when (val s = _updateState.value) {
            is UpdateUiState.UpdateAvailable -> s.remote
            is UpdateUiState.NeedsInstallPermission -> s.remote   // "Continue" after granting
            is UpdateUiState.ReadyToInstall  -> { installDownloadedUpdate(); return }
            else -> return
        }
        if (updateFlowJob?.isActive == true) return
        val app = getApplication<Application>()
        if (!ApkDownloader.canInstall(app)) {
            _updateState.value = UpdateUiState.NeedsInstallPermission(remote)
            return
        }
        updateFlowJob = viewModelScope.launch {
            _updateState.value = UpdateUiState.Downloading(0)
            try {
                val id = ApkDownloader.download(app, remote.apkUrl, remote.fileName)
                val deadline = System.currentTimeMillis() + 15L * 60_000L   // slow head-unit Wi-Fi
                var st = ApkDownloader.status(app, id)
                while (st.running) {
                    delay(800)
                    if (System.currentTimeMillis() > deadline) {
                        _updateState.value = UpdateUiState.Failed("Download timed out"); return@launch
                    }
                    _updateState.value = UpdateUiState.Downloading(st.percent)
                    st = ApkDownloader.status(app, id)
                }
                if (st.failed) { _updateState.value = UpdateUiState.Failed("Download failed"); return@launch }
                val file = ApkDownloader.downloadedFile(app, remote.fileName)
                val apkCode = file?.let { ApkDownloader.archiveVersionCode(app, it) }
                if (file == null || apkCode == null || apkCode < localBuild().versionCode) {
                    runCatching { file?.delete() }
                    _updateState.value = UpdateUiState.Failed("Downloaded APK couldn't be verified")
                } else {
                    _updateState.value = UpdateUiState.ReadyToInstall(remote)
                    runCatching { app.startActivity(ApkDownloader.installIntent(app, file)) }
                        .onFailure { _updateState.value = UpdateUiState.Failed("Couldn't open the installer") }
                }
            } catch (e: java.util.concurrent.CancellationException) { throw e }
              catch (t: Throwable) { _updateState.value = UpdateUiState.Failed("Update failed: ${t.message}") }
        }
    }

    /** Re-open the installer for an APK that finished downloading but wasn't installed. */
    fun installDownloadedUpdate() {
        val remote = (_updateState.value as? UpdateUiState.ReadyToInstall)?.remote ?: return
        val app = getApplication<Application>()
        if (!ApkDownloader.canInstall(app)) { _updateState.value = UpdateUiState.NeedsInstallPermission(remote); return }
        val file = ApkDownloader.downloadedFile(app, remote.fileName)
        if (file == null || !file.isFile) { checkForUpdates(); return }
        runCatching { app.startActivity(ApkDownloader.installIntent(app, file)) }
            .onFailure { _updateState.value = UpdateUiState.Failed("Couldn't open the installer") }
    }

    // ── Navigation ────────────────────────────────────────────────────────────
    private val _nav = MutableStateFlow(NavDestination.HOME)
    val nav: StateFlow<NavDestination> = _nav

    fun navigate(dest: NavDestination) { _nav.value = dest }

    // ── Shortcut picker ───────────────────────────────────────────────────────
    private val _shortcutPickerSlot = MutableStateFlow<Int?>(null)
    val shortcutPickerSlot: StateFlow<Int?> = _shortcutPickerSlot

    fun startShortcutPicker(slot: Int) {
        _shortcutPickerSlot.value = slot
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun assignShortcut(slot: Int, app: AppInfo) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                while (list.size <= slot) list.add(ShortcutConfig())
                list[slot] = ShortcutConfig(packageName = app.packageName, label = app.appName)
            })
        }
        _shortcutPickerSlot.value = null
        _nav.value = NavDestination.HOME
    }

    fun removeShortcut(slot: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) {
                    list[slot] = defaultShortcuts().getOrNull(slot) ?: ShortcutConfig()
                }
            })
        }
    }

    fun reorderShortcut(from: Int, to: Int) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                val item = list.removeAt(from)
                list.add(to.coerceIn(0, list.size), item)
            })
        }
    }

    fun setShortcutIcon(slot: Int, icon: DefaultShortcutIcon?) {
        updateSettings {
            copy(shortcuts = shortcuts.toMutableList().also { list ->
                if (slot in list.indices) list[slot] = list[slot].copy(customIconOverride = icon)
            })
        }
    }

    fun cancelShortcutPicker() {
        _shortcutPickerSlot.value = null
    }

    // ── CarPlay / Android Auto picker ─────────────────────────────────────────
    enum class AppPickerTarget { CARPLAY, ANDROID_AUTO, PIP, RADIO }

    private val _appPickerTarget = MutableStateFlow<AppPickerTarget?>(null)
    val carPlayPickerActive: StateFlow<Boolean> = MutableStateFlow(false) // kept for compat
    val appPickerTarget: StateFlow<AppPickerTarget?> = _appPickerTarget

    fun startCarPlayPicker() {
        _appPickerTarget.value = AppPickerTarget.CARPLAY
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startAndroidAutoPicker() {
        _appPickerTarget.value = AppPickerTarget.ANDROID_AUTO
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startPipPicker() {
        _appPickerTarget.value = AppPickerTarget.PIP
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun startRadioPicker() {
        _appPickerTarget.value = AppPickerTarget.RADIO
        _nav.value = NavDestination.APP_LIBRARY
    }

    fun assignPickerApp(app: AppInfo) {
        when (_appPickerTarget.value) {
            AppPickerTarget.CARPLAY      -> updateSettings { copy(carPlayPackage = app.packageName) }
            AppPickerTarget.ANDROID_AUTO -> updateSettings { copy(androidAutoPackage = app.packageName) }
            AppPickerTarget.PIP          -> updateSettings { copy(pipAppPackage = app.packageName) }
            AppPickerTarget.RADIO        -> updateSettings { copy(radioPackage = app.packageName) }
            null -> {}
        }
        _appPickerTarget.value = null
        _nav.value = NavDestination.HOME
    }

    fun clearCarPlayApp()      { updateSettings { copy(carPlayPackage = "") } }
    fun clearAndroidAutoApp()  { updateSettings { copy(androidAutoPackage = "") } }
    fun clearPipApp()          { updateSettings { copy(pipAppPackage = "") } }
    fun clearRadioApp()        { updateSettings { copy(radioPackage = "") } }

    /**
     * Commit a corner-drag resize: the UI already ran [computeCornerResize] live during the
     * drag, so the geometry arriving here is final. Clamped defensively against the page
     * bounds; no neighbour pushing (the engine never produces overlapping layouts).
     */
    fun resizeWidget(id: String, gridX: Int, gridY: Int, spanX: Int, spanY: Int) {
        updateSettings {
            val inst = widgets.find { it.id == id } ?: return@updateSettings this
            val pageIndex = inst.page.coerceIn(0, pages.lastIndex)
            val dims      = pages[pageIndex]
            val sx = spanX.coerceIn(1, dims.cols)
            val sy = spanY.coerceIn(1, dims.rows)
            copy(widgets = widgets.map { w ->
                if (w.id != id) w
                else w.copy(
                    gridX = gridX.coerceIn(0, dims.cols - sx),
                    gridY = gridY.coerceIn(0, dims.rows - sy),
                    spanX = sx,
                    spanY = sy
                )
            })
        }
    }

    fun moveWidgetConfig(id: String, gridX: Int, gridY: Int) {
        updateSettings {
            val inst = widgets.find { it.id == id } ?: return@updateSettings this
            val pageIndex = inst.page.coerceIn(0, pages.lastIndex)
            val dims      = pages[pageIndex]
            val onPage    = instancesOnPage(widgets, pageIndex)
            val others    = widgets.filter { it.page != pageIndex }
            copy(widgets = others + computeWidgetMove(onPage, id, gridX, gridY, dims.cols, dims.rows))
        }
    }

    /**
     * Adds a new card of [type] to [page]. Types can be duplicated freely —
     * each add creates an independent instance ("VOLUME", "VOLUME~2", …).
     */
    fun addWidget(type: String, page: Int) {
        updateSettings {
            val pageIndex = page.coerceIn(0, pages.lastIndex)
            val dims      = pages[pageIndex]
            val others    = widgets.filter { it.page != pageIndex }
            var onPage    = instancesOnPage(widgets, pageIndex)
            var cell      = firstFreeCell(onPage, dims.cols, dims.rows)

            // If the page is full, shrink the largest multi-cell card by one span to make room
            if (cell == null) {
                val candidate = onPage
                    .filter { it.spanX * it.spanY > 1 }
                    .maxByOrNull { it.spanX * it.spanY }
                if (candidate != null) {
                    onPage = onPage.map { w ->
                        if (w.id == candidate.id)
                            if (w.spanY > 1) w.copy(spanY = w.spanY - 1) else w.copy(spanX = w.spanX - 1)
                        else w
                    }
                    cell = firstFreeCell(onPage, dims.cols, dims.rows)
                }
            }

            val cell_ = cell ?: return@updateSettings this

            // Duplicates inherit the most recent card of this type's span; if no
            // free area fits it, fall back to 1×1 at the free cell so it can't
            // overlap neighbors
            val template = widgets.lastOrNull { it.type == type }
            val sx = (template?.spanX ?: 1).coerceIn(1, dims.cols)
            val sy = (template?.spanY ?: 1).coerceIn(1, dims.rows)
            val area = if (sx > 1 || sy > 1) firstFreeArea(onPage, dims.cols, dims.rows, sx, sy) else null
            val inst = if (area != null)
                WidgetInstance(nextInstanceId(widgets, type), type, pageIndex, area.first, area.second, sx, sy)
            else
                WidgetInstance(nextInstanceId(widgets, type), type, pageIndex, cell_.first, cell_.second)
            copy(widgets = others + onPage + inst)
        }
    }

    fun removeWidget(id: String) {
        updateSettings { copy(widgets = widgets.filterNot { it.id == id }) }
    }

    /** Moves an existing card to another page; no-op when the target page has no room. */
    fun moveWidgetToPage(id: String, page: Int) {
        updateSettings {
            val inst = widgets.find { it.id == id } ?: return@updateSettings this
            val target = page.coerceIn(0, pages.lastIndex)
            if (inst.page == target) return@updateSettings this
            val dims     = pages[target]
            val onTarget = instancesOnPage(widgets, target)
            // Keep the span when a matching free area exists, otherwise demote to 1×1
            val area = firstFreeArea(onTarget, dims.cols, dims.rows, inst.spanX, inst.spanY)
            val pos  = area ?: firstFreeCell(onTarget, dims.cols, dims.rows) ?: return@updateSettings this
            copy(widgets = widgets.map { w ->
                if (w.id == id) w.copy(
                    page  = target,
                    gridX = pos.first,
                    gridY = pos.second,
                    spanX = if (area != null) w.spanX else 1,
                    spanY = if (area != null) w.spanY else 1
                ) else w
            })
        }
    }

    // ── Home screen pages ─────────────────────────────────────────────────────
    fun addPage() {
        updateSettings {
            if (pages.size >= MAX_PAGES) return@updateSettings this
            copy(pages = pages + GridPage())
        }
    }

    /** Page removal refuses non-empty pages so cards are never silently lost. */
    fun removePage(index: Int) {
        updateSettings {
            if (index !in pages.indices || pages.size <= 1) return@updateSettings this
            if (instancesOnPage(widgets, index).isNotEmpty()) return@updateSettings this
            copy(
                pages   = pages.filterIndexed { i, _ -> i != index },
                widgets = widgets.map { w -> if (w.page > index) w.copy(page = w.page - 1) else w }
            )
        }
    }

    /** Resizes a page's grid; refuses when existing cards can't be re-flowed into the new size. */
    fun setPageSize(index: Int, cols: Int, rows: Int) {
        updateSettings {
            pages.getOrNull(index) ?: return@updateSettings this
            val newCols = cols.coerceIn(MIN_COLS, MAX_COLS)
            val newRows = rows.coerceIn(MIN_ROWS, MAX_ROWS)
            if (pages[index].cols == newCols && pages[index].rows == newRows) return@updateSettings this
            val resized = resizePageLayout(instancesOnPage(widgets, index), newCols, newRows)
                ?: return@updateSettings this
            copy(
                pages   = pages.mapIndexed { i, p -> if (i == index) p.copy(cols = newCols, rows = newRows) else p },
                widgets = widgets.filter { it.page != index } + resized
            )
        }
    }

    fun updateSoundboardPad(index: Int, pad: SoundPadConfig) {
        updateSettings {
            // Persisted lists from older versions may be shorter than the 6 pads
            // the widget displays — pad before assigning to avoid IndexOutOfBounds
            val padded = soundboardPads.toMutableList()
            while (padded.size <= index) padded.add(SoundPadConfig("+", synthType = ""))
            padded[index] = pad
            copy(soundboardPads = padded)
        }
    }

    // ── Screen brightness ─────────────────────────────────────────────────────
    fun setBrightnessPct(slot: String, pct: Int) {
        val v = pct.coerceIn(1, 100)
        updateSettings {
            when (slot) {
                "HIGH"   -> copy(brightnessHighPct = v)
                "MEDIUM" -> copy(brightnessMediumPct = v)
                else     -> copy(brightnessLowPct = v)
            }
        }
    }

    fun setBrightnessAuto(on: Boolean) {
        updateSettings { copy(brightnessAuto = on) }
    }

    /**
     * Target for auto-brightness — reuses the same SunriseSunset maths as the
     * DayNightMode.AUTO theming, but evaluated independently of the theme mode
     * (the theme can be locked to DARK while brightness still follows the sun):
     *   night                      -> LOW preset
     *   day, overcast/fog/rain     -> MEDIUM preset
     *   day, clear/few clouds      -> HIGH preset
     * Returns null when AUTO is off or no GPS fix yet (lat/long are required
     * for the sunrise/sunset computation; without weather data a day counts
     * as clear).
     */
    private fun brightnessTargetPct(s: AppSettings, loc: LocationData?, w: WeatherState?): Int? {
        if (!s.hasWidgetType("BRIGHTNESS") || !s.brightnessAuto) return null
        val l = loc ?: return null
        val day = runCatching { SunriseSunset.isDay(l.latitude, l.longitude) }.getOrDefault(true)
        if (!day) return s.brightnessLowPct
        val code = w?.weatherCode
        // Open-Meteo WMO codes: 0 clear, 1 mainly clear; 2/3 and everything from
        // 45 up (fog, drizzle, rain, snow, thunder) means dim sky
        return if (code == null || code <= 1) s.brightnessHighPct else s.brightnessMediumPct
    }

    private var lastAutoBrightnessPct: Int? = null
    private var autoBrightnessPermWarned = false

    // NOTE: the auto-apply loop is launched in the init block below the
    // location/weather/minuteTicker declarations — viewModelScope runs on
    // Dispatchers.Main.immediate, so a launch earlier in the class would see
    // those properties as null during construction.

    fun cancelCarPlayPicker() {
        _appPickerTarget.value = null
    }

    // ── Rearrange mode ────────────────────────────────────────────────────────
    private val _rearrangeMode = MutableStateFlow(false)
    val rearrangeMode: StateFlow<Boolean> = _rearrangeMode

    fun toggleRearrangeMode() { _rearrangeMode.value = !_rearrangeMode.value }
    fun exitRearrangeMode()   { _rearrangeMode.value = false }

    // ── Installed apps ────────────────────────────────────────────────────────
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val _appsLoading = MutableStateFlow(false)
    val appsLoading: StateFlow<Boolean> = _appsLoading

    fun loadInstalledApps() {
        if (_appsLoading.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _appsLoading.value = true
            val pm = getApplication<Application>().packageManager

            // Use getInstalledApplications — same source Android Settings uses,
            // catches apps with no launcher/ACTION_MAIN activity (e.g. CarPlay companions)
            _apps.value = pm.getInstalledApplications(0)
                .mapNotNull { appInfo ->
                    try {
                        val label = pm.getApplicationLabel(appInfo).toString()
                        if (label.isBlank()) return@mapNotNull null
                        AppInfo(
                            packageName = appInfo.packageName,
                            appName     = label,
                            icon        = pm.getApplicationIcon(appInfo),
                            isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    } catch (_: Exception) { null }
                }
                .distinctBy { it.packageName }
                .sortedBy { it.appName }
            _appsLoading.value = false
        }
    }

    fun launchApp(packageName: String) {
        val app = getApplication<Application>()
        val pm  = app.packageManager
        // Try standard launch intent first; fall back to first ACTION_MAIN activity in package
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).setPackage(packageName), 0
            ).firstOrNull()?.activityInfo?.let { ai ->
                Intent(Intent.ACTION_MAIN).apply {
                    setClassName(ai.packageName, ai.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        intent?.let { app.startActivity(it) }
    }

    // ── Now Playing ───────────────────────────────────────────────────────────
    val nowPlaying: StateFlow<NowPlayingState?> = MediaListenerService.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun playPause() { nowPlaying.value?.controller?.also { ctrl ->
        val state = ctrl.playbackState?.state
        if (state == android.media.session.PlaybackState.STATE_PLAYING)
            ctrl.transportControls?.pause()
        else
            ctrl.transportControls?.play()
    }}

    fun skipNext() { nowPlaying.value?.controller?.transportControls?.skipToNext() }
    fun skipPrev() { nowPlaying.value?.controller?.transportControls?.skipToPrevious() }

    // ── Weather ───────────────────────────────────────────────────────────────
    private val _weather = MutableStateFlow<WeatherState?>(null)
    val weather: StateFlow<WeatherState?> = _weather

    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError

    private var weatherJob: Job? = null

    fun fetchWeather(lat: Double, lon: Double, metric: Boolean) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            try {
                // Always request celsius — the state stores celsius and the widget
                // converts for display, so requesting fahrenheit just round-tripped
                // the value through two lossy conversions
                val resp = WeatherApi.service.getForecast(lat, lon, temperatureUnit = "celsius")
                resp.currentWeather?.let { cw ->
                    _weather.value = WeatherState(
                        temperatureCelsius = cw.temperature,
                        weatherCode       = cw.weathercode,
                        windspeedKmh      = cw.windspeed,
                        isDay             = cw.isDay == 1
                    )
                }
                _weatherError.value = null
            } catch (e: Exception) {
                _weatherError.value = e.message
            }
        }
    }

    // ── Location & Compass ────────────────────────────────────────────────────
    // ── TPMS (USB tyre-pressure receiver) ─────────────────────────────────────
    // Repository is process-scoped, not ViewModel-scoped: the link (and therefore the
    // low-pressure warning) must survive navigation, and the serial port may only be opened
    // once. The ViewModel only mirrors its snapshot and pushes settings into it.
    private val tpms = TpmsRepository.get(getApplication())

    val tpmsSnapshot: StateFlow<TpmsSnapshot> = tpms.snapshot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TpmsSnapshot())

    /** Card action: bring the receiver up (or retry) without opening the settings screen. */
    fun requestTpmsConnect() = tpms.requestConnect("card")

    /** Plug-in hook from MainActivity's USB_DEVICE_ATTACHED intent. */
    fun onUsbDeviceAttached() = tpms.onPlugIn("usb intent")

    // Settings drive the repository: having a TPMS card on any page is what arms auto-connect.
    init {
        viewModelScope.launch {
            settings.collect { s -> tpms.configure(s.hasWidgetType("TPMS"), s.tpmsAutoConnect) }
        }
    }

    // ── Location & Compass ────────────────────────────────────────────────────
    val location: StateFlow<LocationData?> = locationMgr.location
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val compassBearing: StateFlow<Float> = locationMgr.bearing
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    // Re-evaluated every minute: a parked car produces no location updates
    // (minDistance filters), so AUTO mode must also flip on time alone.
    private val minuteTicker = flow { while (true) { emit(Unit); delay(60_000L) } }

    val isDayMode: StateFlow<Boolean> = combine(settings, locationMgr.location, minuteTicker) { s, loc, _ ->
        when (s.dayNightMode) {
            DayNightMode.DARK   -> false
            DayNightMode.LIGHT  -> true
            DayNightMode.AUTO   -> if (loc != null) SunriseSunset.isDay(loc.latitude, loc.longitude) else false
            DayNightMode.SYSTEM -> false // placeholder — overridden in MainActivity via isSystemInDarkTheme()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Auto-brightness loop. Re-evaluated on each minute tick (day/night boundary
    // moves), weather fetch and settings change. Only writes when the target
    // differs from what we last applied, so a manual preset tap between changes
    // is not fought over.
    init {
        viewModelScope.launch {
            combine(settings, locationMgr.location, _weather, minuteTicker) { s, loc, w, _ ->
                brightnessTargetPct(s, loc, w)
            }.collect { target ->
                if (target == null) { lastAutoBrightnessPct = null; return@collect }
                if (target != lastAutoBrightnessPct) {
                    if (SystemBrightness.setPct(getApplication(), target)) {
                        lastAutoBrightnessPct = target
                        autoBrightnessPermWarned = false
                    } else if (!autoBrightnessPermWarned) {
                        autoBrightnessPermWarned = true
                        android.util.Log.e("Brightness", "auto brightness needs WRITE_SETTINGS — tap a card button to grant it")
                    }
                }
            }
        }
    }

    fun startLocationUpdates() = locationMgr.start()
    fun stopLocationUpdates()  = locationMgr.stop()

    // ── Connectivity ──────────────────────────────────────────────────────────
    private val _isWifi = MutableStateFlow(false)
    private val _isData = MutableStateFlow(false)
    val isWifi: StateFlow<Boolean> = _isWifi
    val isData: StateFlow<Boolean> = _isData

    fun refreshMedia() {
        MediaListenerService.requestRefresh()
    }

    // ── Radio ─────────────────────────────────────────────────────────────────
    // Android has no public FM tuner API (the Broadcast Radio HAL is @SystemApi),
    // so the radio widget mirrors the unit's real tuner through two backends:
    //   1. szchoiceway MCU units — Settings.Global JSON + canbus broadcasts.
    //      Full control: seek, band switching, direct frequency tuning.
    //   2. Any other unit — the vendor radio app's MediaSession. Frequency and
    //      station are parsed from session metadata; seek maps to skip next/prev
    //      (how steering-wheel keys drive these apps). No direct tuning.
    data class HardwareRadioState(
        val band: String,
        val freq: String,
        val stationName: String? = null,
        // Direct tuning + FM1/FM2/FM3/AM switching — vendor MCU backend only
        val canTune: Boolean = false
    ) {
        val display get() = "$band  $freq"
        val isAm    get() = band.equals("AM", ignoreCase = true)
    }

    private val _mcuRadio = MutableStateFlow<HardwareRadioState?>(null)

    private fun packageInstalled(pkg: String) = runCatching {
        getApplication<Application>().packageManager.getPackageInfo(pkg, 0)
    }.isSuccess

    private val hasSzchoicewayMcu: Boolean by lazy {
        packageInstalled("com.szchoiceway.radio") ||
        packageInstalled("com.szchoiceway.eventcenter") ||
        runCatching {
            AndroidSettings.Global.getString(
                getApplication<Application>().contentResolver, "SYS_MEDIA_INFO_JSON"
            ) != null
        }.getOrDefault(false)
    }

    private fun parseRadioJson(): HardwareRadioState? {
        return try {
            val json = AndroidSettings.Global.getString(
                getApplication<Application>().contentResolver, "SYS_MEDIA_INFO_JSON"
            ) ?: return null
            val title = org.json.JSONObject(json).optString("mediaTitle", "") // e.g. "FM1 90.10"
            if (title.isEmpty()) return null
            val parts = title.trim().split("\\s+".toRegex())
            if (parts.size < 2) return null
            HardwareRadioState(band = parts[0], freq = parts[1], canTune = true)
        } catch (e: Exception) {
            android.util.Log.e("RadioMcu", "parseRadioJson error", e)
            null
        }
    }

    private var radioObserver: ContentObserver? = null

    private fun startHardwareRadioObserver() {
        if (radioObserver != null) return
        _mcuRadio.value = parseRadioJson()
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                _mcuRadio.value = parseRadioJson()
            }
        }
        getApplication<Application>().contentResolver.registerContentObserver(
            AndroidSettings.Global.getUriFor("SYS_MEDIA_INFO_JSON"),
            false, observer
        )
        radioObserver = observer
    }

    // ── MediaSession radio backend (universal fallback) ───────────────────────

    private fun looksLikeRadioPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return "radio" in p || "fmradio" in p || "tuner" in p || p.endsWith(".fm") || ".fm." in p
    }

    private fun isRadioSessionPackage(pkg: String): Boolean {
        val assigned = settings.value.radioPackage
        return if (assigned.isNotEmpty()) pkg == assigned else looksLikeRadioPackage(pkg)
    }

    // Matches "FM1 90.10", "99.9 MHz", "FM 99.9 WXYZ", "1040 kHz", "99,9" …
    private val sessionFreqPattern =
        Regex("""(?:\b(FM\s?\d?|AM)\b)?\s*(\d{2,4}(?:[.,]\d{1,2})?)\s*(MHz|kHz)?""", RegexOption.IGNORE_CASE)

    private fun parseSessionRadio(np: NowPlayingState): HardwareRadioState? {
        val pkg = np.controller?.packageName ?: return null
        if (!isRadioSessionPackage(pkg)) return null

        val text = listOf(np.title, np.artist)
            .filter { it.isNotBlank() && it != "Unknown" }
            .joinToString("  ")
        val match   = sessionFreqPattern.find(text)
        val rawFreq = match?.groupValues?.get(2)?.replace(',', '.')?.takeIf { it.isNotEmpty() }
        val freqVal = rawFreq?.toFloatOrNull()
        val explicitBand = match?.groupValues?.get(1)?.replace(" ", "")?.uppercase().orEmpty()
        val band = when {
            explicitBand.isNotEmpty()                 -> explicitBand
            freqVal != null && freqVal in 520f..1710f -> "AM"
            else                                      -> "FM"
        }
        // Whatever isn't the frequency is the station / RDS text
        val station = (if (match != null) text.replace(match.value, "") else text)
            .trim(' ', '-', '|', '/', '•')
            .takeIf { it.isNotBlank() }
        return HardwareRadioState(band = band, freq = rawFreq ?: "—", stationName = station, canTune = false)
    }

    // MCU backend wins when present; otherwise mirror the radio app's session
    val hardwareRadio: StateFlow<HardwareRadioState?> =
        combine(_mcuRadio, nowPlaying, settings) { mcu, np, _ ->
            mcu ?: np?.let { parseSessionRadio(it) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private fun radioSessionController() = nowPlaying.value?.controller?.takeIf { c ->
        c.packageName?.let { isRadioSessionPackage(it) } == true
    }

    // Send a radio keyCode to the MCU via the EventService broadcast channel.
    // Permission com.szchoiceway.permission.broadcast (prot=normal) is required and declared.
    // keyCodes: 15=seek_up, 14=seek_down, 30=fm_cycle, 31=am
    private fun sendMcuByteArray(bytes: ByteArray) {
        runCatching {
            val intent = Intent("com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT").apply {
                putExtra("EventUtils.MCU_CMD_DATA", bytes)
            }
            getApplication<Application>().sendBroadcast(intent)
        }
    }

    private fun sendMcuBytes(vararg bytes: Byte) = sendMcuByteArray(bytes)

    // Tune to a specific frequency.
    // Canbus frame: 0x5a 0xa5 0x0d [0x91 bandByte bank(2) zeros... freqAscii] checksum
    // Checksum = low byte of sum of all preceding bytes.
    // Wrapped with 0x0d 0x08 sub-command prefix for MCU_CMD_DATA.
    fun radioTune(band: String, freqMhz: Float) {
        if (!hasSzchoicewayMcu) return // direct tuning is MCU-only
        val bandByte  = when (band) { "FM3" -> 0x03.toByte(); "AM" -> 0x04.toByte(); else -> 0x01.toByte() }
        val bankStr   = if (band == "FM2") "02" else "01"
        val freqStr   = if (band == "AM") String.format(java.util.Locale.US, "%.0f", freqMhz) else String.format(java.util.Locale.US, "%.1f", freqMhz)
        val padding   = 9 - freqStr.length
        val header    = byteArrayOf(0x5a.toByte(), 0xa5.toByte(), 0x0d.toByte())
        val data      = byteArrayOf(0x91.toByte(), bandByte,
                            bankStr[0].code.toByte(), bankStr[1].code.toByte()) +
                        ByteArray(padding) +
                        freqStr.map { it.code.toByte() }.toByteArray()
        val frame     = header + data
        val checksum  = (frame.sumOf { it.toInt() and 0xFF } and 0xFF).toByte()
        val finalPayload = byteArrayOf(0x0d.toByte(), 0x08.toByte()) + frame + byteArrayOf(checksum)
        sendMcuByteArray(finalPayload)
    }

    private val mcuActive get() = _mcuRadio.value != null

    // Seek routes to the MCU when that backend is live, otherwise to the radio
    // app's MediaSession — skip next/prev is how these apps expose seek.
    fun radioSeekUp()   { if (mcuActive) sendMcuBytes(0x02, 0x0f) else radioSessionController()?.transportControls?.skipToNext() }
    fun radioSeekDown() { if (mcuActive) sendMcuBytes(0x02, 0x0e) else radioSessionController()?.transportControls?.skipToPrevious() }
    // Band switching and direct tuning only exist on the MCU backend
    fun radioCycleFm()  { if (mcuActive) sendMcuBytes(0x02, 0x1e) }
    fun radioSwitchAm() { if (mcuActive) sendMcuBytes(0x02, 0x1f) }
    fun radioStart()    { sendMcuBytes(0x01, 0x01) }
    fun radioStop()     { sendMcuBytes(0x01, 0x63) }

    fun stopHardwareRadioApp() {
        if (mcuActive) radioStop()
        else radioSessionController()?.transportControls?.pause()
    }

    fun launchHardwareRadioApp() {
        if (hasSzchoicewayMcu) radioStart()
        val pkg = settings.value.radioPackage.ifEmpty {
            when {
                hasSzchoicewayMcu -> "com.szchoiceway.radio"
                else              -> radioSessionController()?.packageName ?: ""
            }
        }
        if (pkg.isNotEmpty()) {
            runCatching {
                val intent = getApplication<Application>().packageManager
                    .getLaunchIntentForPackage(pkg)
                    ?: Intent(Intent.ACTION_MAIN).apply {
                        `package` = pkg
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            }
        }
        // Resume playback if the session is just paused
        radioSessionController()?.transportControls?.play()
    }

    fun refreshConnectivity() {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            _isWifi.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            _isData.value = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        } else {
            // activeNetwork requires API 23 — legacy path for Android 5.x head units
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            val connected = info?.isConnected == true
            @Suppress("DEPRECATION")
            val type = info?.type
            _isWifi.value = connected && type == ConnectivityManager.TYPE_WIFI
            _isData.value = connected && type == ConnectivityManager.TYPE_MOBILE
        }
    }

    override fun onCleared() {
        super.onCleared()
        locationMgr.stop()
        radioObserver?.let { getApplication<Application>().contentResolver.unregisterContentObserver(it) }
        radioObserver = null
    }

    init {
        loadInstalledApps()
        refreshConnectivity()
        if (hasSzchoicewayMcu) startHardwareRadioObserver()
        // Fetch weather on first location fix, then every 30 minutes.
        // The minute ticker covers the parked case where no location updates arrive.
        viewModelScope.launch {
            var lastFetchMs = 0L
            merge(
                locationMgr.location.filterNotNull(),
                minuteTicker.mapNotNull { locationMgr.location.value }
            ).collect { loc ->
                val now = System.currentTimeMillis()
                if (now - lastFetchMs >= 30 * 60 * 1_000L) {
                    lastFetchMs = now
                    fetchWeather(loc.latitude, loc.longitude, settings.value.unitSystem.name == "METRIC")
                }
            }
        }
    }
}
