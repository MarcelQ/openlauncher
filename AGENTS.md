# Project guidance for AI coding agents

## Project
This is the public OpenLauncher Android project. Treat its checked-in source as the implementation source of truth.

## Working rules
- Before changing code, inspect the relevant modules, Gradle configuration, Android manifest and
  existing architecture.
- Prefer small, reviewable changes. Explain the plan before multi-file refactors.
- Do not silently change package IDs, signing, min/target SDK, Gradle/AGP/Kotlin versions, or app permissions.
- Preserve offline-first behaviour and compatibility with aftermarket Android head units, including older Android versions where the project supports them.
- Unit tests exist for the TPMS wire protocol, the low-pressure alarm decision
  (`TpmsAlarmLogicTest.kt`) and the chime cadence (`TpmsChimeCadenceTest.kt`)
  (`app\src\test\java\com\openlauncher\app\tpms\`)
  and for the multi-page widget grid engine — placement, page resize guards, duplicate
  instance ids, legacy migration (`app\src\test\java\com\openlauncher\app\data\GridLayoutTest.kt`;
  the engine itself lives in `app\src\main\java\com\openlauncher\app\data\GridLayout.kt`, framework-free);
  and for the two weather backends' response → `WeatherState` mapping plus the met.no
  symbol-code → WMO translation (`app\src\test\java\com\openlauncher\app\data\WeatherForecastParseTest.kt`;
  pure DTOs, no network);
  and for the Now Playing artwork-contrast decision — average luminance vs the dim
  overlay threshold (`app\src\test\java\com\openlauncher\app\ui\widget\NowPlayingContrastTest.kt`;
  internal pure helpers in `NowPlayingWidget.kt`);
  and for the Now Playing queue/highlight/inline-layout/volume helpers — queue
  current-row highlight, inline-queue ≥4×4 gate, volume-percentage rounding
  (`app\src\test\java\com\openlauncher\app\ui\widget\NowPlayingQueueTest.kt`) plus the
  shuffle custom-action matcher (`app\src\test\java\com\openlauncher\app\model\NowPlayingCustomActionTest.kt`,
  helper `findShuffleActionIndex` in `model\NowPlayingState.kt`);
  and for the device-diagnostics report's pure helpers — head-unit vendor-package
  classification and sensor-type naming (`app\src\test\java\com\openlauncher\app\data\DeviceInfoReportTest.kt`;
  pure helpers split out of `data\DeviceInfoReport.kt`; the Context-driven collector in
  `data\DeviceInfoCollector.kt` is device-verified only);
  and for the sidebar GPS status icon's strength mapping — satellites-used-in-fix tiers
  plus the accuracy fallback (`app\src\test\java\com\openlauncher\app\data\GpsStrengthTest.kt`;
  pure `data\GpsStrength.kt`; the GnssStatus plumbing in `util\LocationCompassManager.kt`
  is device-verified only);
  and for the APP OVERLAY (freeform PiP) card's pure decision helpers — launch-mode chain,
  card-rect math, AOSP bundle-key constants, launch-log ring buffer
  (`app\src\test\java\com\openlauncher\app\ui\widget\PipFreeformTest.kt`;
  framework-free helpers in `ui\widget\PipFreeform.kt`; the Context/ActivityOptions launch chain
  in `ui\widget\PipWidget.kt` is device-verified only); plus the device report's freeform verdict /
  resizability / su-summary helpers (`DeviceInfoReportTest.kt`);
  and for the tyre-card phone web server's wire contract - hotspot-AP host picking, request-line
  parsing, the `/tpms.json` payload (raw kPa + display value, null-not-zero for unknown wheels,
  staleness, the shared `alarmingWheels` verdict, dot-decimal numbers), routing incl. the
  CSP-critical `/theme.css` + `/app.js` asset routes, and accent-token injection with hex
  sanitising (`app\src\test\java\com\openlauncher\app\tpms\TpmsWebReportTest.kt`; pure
  `tpms\TpmsWebReport.kt`; the `ServerSocket` transport `tpms\TpmsWebServer.kt` and the PWA
  assets under `app\src\main\assets\tpmsweb\` are device-verified only);
  and the MAP (LIVE) card's pure helpers — OSM user-agent, accuracy-tiered initial zoom,
  `geo:` handoff URI, lat/lon format
  (`app\src\test\java\com\openlauncher\app\ui\widget\MapCardLogicTest.kt`; pure `ui\widget\MapCardLogic.kt`;
  the osmdroid `AndroidView` card body in `ui\widget\MapNativeWidget.kt` is device-verified on the
  real SPD unit 2026-11-18 (real Mapnik tiles over unit Wi-Fi + green GPS position dot at the unit's
  location). Gotchas from the prototype: keep the card's controls inside the native view subtree —
  classic AndroidView draws views OVER sibling Compose overlays — and Material Button inflation
  crashes under the launcher's DeviceDefault theme).
  JVM only, no device. No `app\src\androidTest` sources. Run them locally with
  `java -cp gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest`
  and build with the same wrapper plus `:app:assembleDebug` (this checkout has no `gradlew.bat`).
  CI (`.github\workflows\build-apk.yml`) runs both on every push/PR.
  Report exactly what was and was not verified.
- Install and drive the app over adb (`:app:installDebug`, then
  `am start -n com.openlauncher.app/.MainActivity`). The local AVD is an API 37 phone, not a head
  unit: it has no vehicle bus, no MCU broadcast service and no real GPS. Emulator results never
  prove a car-hardware feature works.
- Do not invent hardware protocols. For USB/serial/HID devices, inspect USB descriptors and real captured traffic before implementing a decoder.
- For TPMS, separate device discovery/transport, protocol decoding, sensor state, persistence, alarms, and UI. Handle permission denial, unplug/replug, reconnect, malformed packets, stale readings, and sensor-ID learning.
- Keep Car Nebula decompiled material in the sibling `reference\car-nebula-decompiled` folder. It is reference material only. Do not copy proprietary source, assets, keys, or code into this project. Reimplement behaviours independently and respect applicable licences and rights.
- Never commit decompiled files, APKs, credentials, local model files, logs containing personal data, or hardware captures with identifying information.
- Before editing, inspect `git status` and preserve the user's uncommitted work.

## Branch model and test APKs (set up 2026-11-08, on fork `MarcelQ/openlauncher`)
- `dev` = default integration branch; day-to-day feature work goes here (direct pushes allowed).
  `main` = stable; updated only by merging `dev` via PR (branch protection: no direct/force pushes).
  Upstream `origin` is still `dw2lam/openlauncher` (fetch with `git fetch origin`).
- Never commit APKs (see working rules). Installable debug APKs live in GitHub Releases as rolling
  prereleases, updated by CI on every push: tag `dev-latest` (asset `openlauncher-dev-debug.apk`) and
  tag `main-latest` (asset `openlauncher-main-debug.apk`). For other branches, CI uploads an
  `apk-<branch>-<sha>` workflow artifact (requires GitHub login to download).
- Download straight onto a head unit:
  `https://github.com/MarcelQ/openlauncher/releases/download/dev-latest/openlauncher-dev-debug.apk`
- Installing a debug APK makes the app a HOME candidate on the head unit; if that is not wanted,
  revert with `adb shell cmd package set-home-activity <previous launcher>`.
- **Debug signing is shared**: CI and local debug builds sign with `keystore\debug.keystore`
  (untracked — gitignored; CI restores it from the Actions secret `DEBUG_KEYSTORE_B64`). This is
  what lets rolling-release APKs update each other on the device — Android refuses installs over a
  different signature. Release signing remains unset (no release keystore anywhere). If the local
  copy is lost, regenerate with the keytool command documented in `app\build.gradle.kts` and re-set
  the secret (`gh secret set DEBUG_KEYSTORE_B64 --repo MarcelQ/openlauncher`). Never commit it.
  Devices still running an APK signed with an older throwaway debug key need ONE manual uninstall
  before a shared-key build installs.
- **Privileged experiment build (`aospDebug` build type, added 2026-11-17)**: for head units whose
  ROM silently downgrades freeform launch requests to fullscreen (the real SPD unit does, even with
  `enable_freeform_support`/`force_resizable_activities` = 1 and a reboot — the vendor fork strips
  the check for normal apps). The `android:sharedUserId` in `AndroidManifest.xml` is a placeholder:
  empty in debug/release, `android.uid.system` only in `aospDebug` (applicationId
  `com.openlauncher.app.aosp`, coexists with the normal app, no uninstall needed; the
  `src\aospDebug\AndroidManifest.xml` merger overlay strips its HOME/USB intent-filters
  (`tools:node="removeAll"`) and renames it "Open Launcher AOSP-TEST" so it is a plain
  drawer-launchable app — SPD launchers hide HOME apps and two identically-named launchers
  confused the 2026-11-17 test). Signed with the
  **public AOSP testkey** via `keystore\platform.jks` (alias `platform`, store/key passwords
  `android`; gitignored; CI restores it from Actions secret `AOSP_PLATFORM_KEYSTORE_B64`; recreate
  from the public testkey files `build/target/product/security/platform.{pk8,x509.pem}` on the
  aosp-mirror `platform_build` repo: DER PKCS8 + PEM cert → JKS via keytool/JDK, NEVER from
  `reference\car-nebula-decompiled`). CI publishes rolling prerelease `aosp-test-latest`
  (asset `openlauncher-aosp-test-debug.apk`) on dev pushes; the in-app updater must never offer this
  channel (signature mismatch with the normal app). Reading of the experiment: install SUCCEEDS =
  the ROM platform is signed with the public AOSP testkey → launcher runs as uid 1000, hidden-API
  `setLaunchWindowingMode` and `ACTIVITY_EMBEDDING` become available; install fails with
  `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` = ROM uses a private key, this path is closed.
  **VERDICT on the dev's SPD trinket unit (2026-11-17): install via the unit's browser failed with
  "App not installed" → private key → the privileged path is CLOSED on this unit** (the release and
  build type stay for other QCM units that may use the public testkey; do not re-litigate this
  unit). Follow-up probe shipped instead: Diagnostics now enumerates the vendor float-window stack
  (`com.spd.floatwindow` activities/receivers + exported flags + main activity). **FINDINGS from the
  unit's 2026-11-17 22:15 report: `com.spd.floatwindow` v11 has ONE exported MainActivity with no
  MAIN/LAUNCHER filter (invisible in app drawers; `getLaunchIntentForPackage` null) and three
  exported receivers (WindowBroadcastReceiver, BluetoothPairBroadcastReceiver,
  LocalChangeBroadcastReceiver — action strings unknown).** Settings.System keys
  `FLOAT_BUTTON_ABS_X/Y`, `SETTINGS_SHORTCUT_FLOAT`, `SIDEBAR_APPS_FLOAT_BUTTON`,
  `SETTING_LRC_FLOAT_WINDOW_MODE` suggest the feature is a draggable on-screen button opening a
  float-window UI. **2026-11-18 FINAL VERDICT (unit adb forensics + pulled-APK dex analysis, full
  writeup in `docs\float-window-research.md`): `com.spd.floatwindow` is BROKEN on this ROM — its
  classes.dex lacks androidx.constraintlayout, so MainActivity AND the service crash-loop
  (`ClassNotFoundException`), and `Settings.Global/hide_error_dialogs=1` hides the crash dialog
  (that was "opens for a second and bounces back"); its exported receivers are only
  BOOT/SCREEN_ON/BLUETOOTH/LOCALE and `FloatWindowService.onStartCommand` takes no extras →
  interop DEAD. The real app-float feature lives in SpdSystemUI (`com.spd.window.shortcut.FloatButtonWindow`
  / `FloatSourceWindow`: 9 hardcoded app buttons doing plain fullscreen `startActivity` handoff,
  plus back/home/power/volume keys), toggled LIVE by `SpdStatusBarViewManager` ContentObservers on
  Settings.System `SETTINGS_SHORTCUT_FLOAT`/`SIDEBAR_APPS_FLOAT_BUTTON` (both 0 on the unit;
  `FLOAT_BUTTON_ABS_Y` = -138 parked off-screen). **2026-11-18 unit correction: third-party apps
  CANNOT write those keys directly — the SPD unit rejects all four with IllegalArgumentException
  ×4 (the "modify system settings"/WRITE_SETTINGS grant covers only allowlisted keys; the vendor's
  own Settings app writes these because `com.spd.setting` has `sharedUserId=android.uid.system`).
  In-app interop shipped instead: that app's EXPORTED `SettingService` AIDL — independent client in
  `util\VendorSettingService.kt` (descriptor com.spd.setting.aidl.ISettingService, putInt tx=26 /
  getInt tx=27, codes 40033/40040 = SHORTCUT_FLOAT_ENABLE/SIDEBAR_APPS_FLOAT_ENABLE; server-side
  GeneralControllerImpl packed-switch base 0x9c41 maps them to exactly the two enable keys). The
  Diagnostics "Toggle Vendor Float Sidebar (Dev)" button = direct writes first (other ROMs), then
  vendor-service fallback, verdict by RE-READING the raw keys, runs on IO (diagScope). Pure helpers
  `vendorSidebarToggleValues` + `vendorSidebarServiceWrites` are JVM-tested. No vendor service code
  exists for the FLOAT_BUTTON_ABS position keys — un-parking needs one `adb shell settings put
  system FLOAT_BUTTON_ABS_Y 160` (adb shell uid is privileged). **PROVEN ON UNIT 2026-11-18 15:56:
  the toggle enabled via the service and SystemUI drew BOTH overlays live over the launcher — the
  draggable circle (FloatButtonWindow: Recent Apps/Clear/Voice/±volume/Home/Back/Mute panel) and
  the app sidebar (FloatSourceWindow: YouTube/Chrome/Navi/Radio app buttons). The enable keys
  persist in Settings.System, so the panels survive reboot; dragging the circle should make
  SystemUI itself re-write the ABS position keys (verify next adb session). AVD remains the
  no-service graceful path only.** The unit's bootloader is ALREADY UNLOCKED (`ro.boot.flash.locked=0`,
  `verifiedbootstate=orange`) and the build is debuggable (`ro.debuggable=1`, `ro.secure=0`,
  `ro.adb.secure=0`) → `adb root` likely works → the remaining route to true floating app windows
  on this unit is the revertible experiment staged in `tools\freeform-root-experiment\` (declare
  the missing `android.software.freeform_window_management` system feature in
  /system/etc/permissions + boot-image backup rules). If freeform still downgrades after that,
  the SPD fork is unfixably deep for this unit: ship handoff cards + a native osmdroid map card.
  The freeform launch chain itself stays for ROMs that keep AOSP behavior (verified on API 37 AVD).
  Related unit fact: no `com.android.documentsui` exists on it, so every SAF
  (`OpenDocument`/`GetContent`) call site must go through `util\SafPicker.kt`.**
  The Settings ▸ Diagnostics report's `process uid` line confirms uid 1000 without adb.
  (AVD caveat: the API 37 Google-Play AVD rejects this APK exactly with that error — its system
  uses Google keys — which is the expected negative control, not a bug.)
- **In-app updater** (Settings ▸ Updates, `data\UpdateChecker.kt` + `data\ApkDownloader.kt`): polls
  `api.github.com/repos/MarcelQ/openlauncher/releases/tags/<dev-latest|main-latest>` and offers the
  channel's APK (user picks dev or main; debug builds default to dev). Requires the CI release NOTES
  to carry the marker line `versionCode=N versionName=X commit=<sha>` — produced by the
  `:app:printVersionInfo` Gradle task inside `build-apk.yml`. **If the workflow's notes step or the
  regex in `UpdateChecker.kt` change, keep both in sync** (decision logic: `UpdateCheckerTest.kt`).
  The app's own build is identified by `BuildConfig.GIT_SHA` (auto via `git rev-parse` at
  configuration time; "unknown" off git). Downloads need `REQUEST_INSTALL_PACKAGES` (user must also
  allow "install unknown apps" on API 23+).

## Real head unit — hardware profile (from the Settings ▸ Diagnostics report, 2026-11-15)

The developer's actual head unit was captured with the in-app device report. Use this as the
reference device profile; it corrects several assumptions. Personal/credential values that
appeared in the dump (vendor Wi-Fi AP password, factory password) are deliberately NOT recorded
here — never copy them into code, issues or commits.

- **Unit**: Qualcomm "trinket" (SDA/QCM665-class) reference board — brand `qti`, board/device/product
  `trinket`, hardware `qcom`, kernel `4.14.190-perf+`, 8 cores, arm64-v8a, ~6 GB RAM
  (≈2 GB free), 128 GB storage (≈87 GB free). Baseband `BP10.005(SC668SEMUAR02A05)` — Quectel/
  SC668-class 4G LTE module; telephony + SIM present.
- **Android**: API 30 (Android 11 base, fingerprint `qti/trinket/trinket:11/RKQ1.211119.001/…`,
  security patch 2021-10-01) although `Build.VERSION.RELEASE` reports "14" — **trust SDK_INT
  (30), not the release string**. Not an `android.hardware.type.automotive` build, no
  `android.car` framework, UI mode = desk. Keep minSdk 21 compatibility; API 30 is the real floor
  to test against.
- **Screen**: 1280x720 px @ 212 dpi (1.325x, xdpi 248) ≈ 966 x 543 dp, ~6" panel, **native
  landscape** (`rotation = 0`), 60 Hz, capacitive (`jadard-touchscreen`). Layout target: 720p
  landscape; the settings-rail sizing comment's "1440px @ 3.5" worst case does not apply to this
  unit — dp height here is 543, not 411.
- **Sensors**: only accelerometer + gyroscope (STMicro LSM6DSR) plus Qualcomm fusion/virtual
  sensors (game rotation vector, device orientation, pedometer, stationary/motion detect).
  **No magnetometer, no barometric pressure, no proximity/light sensor.** Consequences: the
  compass cannot use a magnetic field — heading must come from GPS motion; the altimeter is
  GPS-altitude only; do not build features that assume TYPE_MAGNETIC_FIELD or TYPE_PRESSURE.
- **Vendor stack is SPD (`com.spd.*`), not szchoiceway.** System apps: `com.spd.radio` +
  `com.spd.spdradio` (FM tuner), `com.spd.media`/`spdmedia`, `com.spd.bluetooth`/`bt`,
  `com.spd.avm` (around-view), `com.spd.dvr`/`spddvr` (dashcam), `com.spd.aircondition` (HVAC),
  `com.spd.carinfo` + `com.spd.carinfo.ui` (vehicle info), `com.spd.txzAdapter`, `com.spd.calibrate`,
  `com.spd.spdeq` (EQ), `com.spd.floatwindow`, `com.spd.uiswitcher`, `com.spd.setting`/`spdsettings`,
  `com.spd.upgrade`, `com.spd.bootlogo`, `com.carletter.car` (phone-link, `carletter_*` settings),
  `com.goodocom.bttek` (Carlinkit/Goodocom phone-mirroring dongle), Huawei HiCar stack
  (`com.huawei.dmsdpdevice`/`hisight`/`nearby`), `com.miniaa.launcher.pro` (AutoKit-style AA).
  → `hasSzchoicewayMcu` is false on this unit: the radio card must use the MediaSession fallback.
  A future SPD radio backend would target `com.spd.radio`/`com.spd.spdradio` (reimplement the
  behaviour independently; do not decompile-and-copy).
- **Steering-wheel controls are vendor-implemented as ADC voltage tables in Settings.System**:
  `SWC_VOLTAGE_{1,2}_{1..15}` (two banks of 15 slots, `255` = unused; bank 1 programmed: 53/84/154/
  194/26/117 → ~6 buttons; bank 2: 26/84/154), paired `AD_VOLTAGE_*` mirrors and `KEY_SWC_STUDY_*`
  learn flags, plus a `gpio-keys` input device (`vid:pid=0001:0001`). Reading those keys via
  ContentResolver/observer is the realistic path to SWC support on this unit — no
  `com.szchoiceway.permission.broadcast` MCU channel here.
- **Vehicle/power signals in Settings.System** (SPD): `SETTING_CURRENT_VOLTAGE = 121` (12.1 V,
  ×10 scaling — 12 V input monitor), `SPD_ACC_OFF_TIME` (epoch ms of last ACC-off → ignition/ACC
  state), `SETTING_LOW_VOL`, `BOOT_FAIL_COUNT`, `SETTING_CANBOX_VERSION` (empty on this unit —
  no CAN box fitted), reverse-camera geometry params `REVERSE_PARA_*` and
  `SETTING_ORIGINAL_REVERSE_MODE` (reverse trigger), HVAC/AVM keys (`SETTING_AVM_*`). These are
  the only vehicle signals this unit exposes — no car sensor manager, no CAN.
- **The unit has an internal battery** (Li-ion 4000 mAh; report showed 80 %, discharging) —
  battery APIs work, but "plugged/status" does not reflect the car's ignition; use `SPD_ACC_OFF_TIME`
  / `SETTING_CURRENT_VOLTAGE` for run-state features instead.
- **Vendor tuning keys** in Settings.System: `SETTING_RADIO_MODE`, `SETTING_RADIO_SORT`,
  `SETTING_LOW_VOL` (subwoofer/volume trims), `SETTING_FRONT_TO_REAR_MIX`, `SETTING_DRIVER_POSITION`,
  `SETTING_DIGITAL_AMPLIFIER_OUTPUT_MODE`, `SETTING_DTS_MODE`, `SETTING_FADE_BALANCE_MODE_DEFAULT`
  (EQ/DSP surface via `com.spd.spdeq`), `SETTING_SPEECH_RECOGNITION` (currently
  `com.google.android.apps.googleassistant`), `SETTING_BLUETOOTH_NAME` (unit advertises as
  vehicle name — do not echo such values into the report). `SETTING_DEVELOPER_MODE` and factory
  password keys exist — treat values as sensitive, do not log them.
- **USB host works** and a serial adapter is attached: `/dev/bus/usb/001/003 vid:pid=1a86:7523
  class=255` → **Qinhai CH340/CH341** USB-serial (class 255 = vendor-specific composite). This is
  the supported CH34x family for TPMS-class sticks; USB host + USB accessory features both present.
- **Bluetooth**: `BluetoothAdapter.isEnabled` threw `SecurityException` — the app declares no
  Bluetooth permission (on API 30 legacy BLUETOOTH perm gates it). Any future Bluetooth feature
  needs a deliberate permission addition (state it explicitly in the change).
- **Sensors/audio config**: audio output + mic present, 48 kHz / 144-frame buffer — fine for the
  chime/soundboard; `volume music = 4/40` (40-step volume curve).
- **Grants on this unit** (Launcher grants section): Open Launcher IS the default home; location
  fine ✓, notification listener ✓, WRITE_SETTINGS ✓; overlay windows ✗ (harmless — the APP
  OVERLAY card needs no overlay grant) and
  "install unknown apps" ✗ (in-app updater install blocked) — the two the user must grant.
- **Installed apps** (219 total / 19 user): competing launchers `app.carlab.carlauncher` and
  `com.miniaa.launcher.pro`; user apps include Spotify, Waze, VLC, Stremio, LocalSend,
  `app.morphe.*` (YouTube ReVanced fork), `btools.routingapp` (BRouter), Google Assistant,
  an Alpicool smart-fridge controller pair (`com.alpicool.news`,
  `com.alpicoolneutral.fridge.controller`) → fridge integration is a plausible future feature;
  `com.androits.gps.test.pro` (GPS test), `com.ms.ms2160` and `com.dolby.dolby234` (vendor audio).
- Report follow-ups — first batch already implemented with this profile entry: `com.spd.*`,
  `carletter`, `goodocom/bttek/dmsdp/miniaa` and `alpicool` added to
  `DeviceInfoReport.subsystemPatterns`; Settings dump cap raised 120 → 300 rows per table;
  sensor-type map corrected against the unit's evidence (gravity=9, linear-accel=10,
  gyro-uncal=16, device-orientation=27, accel-uncal=35). Still open: an SPD radio backend
  (MediaSession fallback works meanwhile), SWC decoding from `SWC_VOLTAGE_*`, run-state from
  `SPD_ACC_OFF_TIME`/`SETTING_CURRENT_VOLTAGE`.
