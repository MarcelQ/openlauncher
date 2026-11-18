# Floating other apps in/over the launcher — research & decision (2026-11-18)

Research question: how can OpenLauncher show "other apps" (Google Maps first, later
Chrome, CarPlay/CarLetter) **as floating windows or embedded surfaces** on the dev's SPD
trinket head unit — and what should the fallback design be for every other head unit?

Everything below was established from on-device evidence (wireless adb, 2026-11-17) and
offline analysis of pulled vendor APKs (behavior only — no vendor code copied). No claim
is made beyond what the evidence supports; unverified items are marked **[bench]**.

## Unit facts that shape every option

| Fact | Evidence |
|---|---|
| API 30, SPD `trinket` (QCM665-class), user build but **`ro.debuggable=1`, `ro.secure=0`, `ro.adb.secure=0`** | getprop capture 2026-11-17 |
| **Bootloader already unlocked** (`ro.boot.flash.locked=0`, `verifiedbootstate=orange`) | getprop capture |
| No `android.software.freeform_window_management` system feature | device report |
| Freeform launch requests are **silently downgraded to fullscreen** even with `enable_freeform_support=1` + `force_resizable_activities=1` + reboot | freeform probe + `dumpsys window` |
| AOSP platform testkey rejected (private ROM key) → `sharedUserId=android.uid.system` / ActivityView / ACTIVITY_EMBEDDING path **closed** | "App not installed" on unit |
| **`com.spd.floatwindow` is broken on this ROM**: its dex lacks `androidx.constraintlayout`; MainActivity + service crash-loop (`ClassNotFoundException`), and `Settings.Global/hide_error_dialogs=1` hides crash dialogs | crash buffer 22:44–23:03 |
| The real app-float feature lives in **SpdSystemUI** (`/spdproject/priv-app/SpdSystemUI`): `com.spd.window.shortcut.FloatButtonWindow` + `FloatSourceWindow`, driven by live `ContentObserver`s on `Settings.System` keys `SETTINGS_SHORTCUT_FLOAT` / `SIDEBAR_APPS_FLOAT_BUTTON` | sui dex: `SpdStatusBarViewManager.mContentObserverShortcutFloat/mContentObserverSourceFloat` |
| Those windows are **system window types only SystemUI (uid 1000) may add**; they are *overlay panels that launch fullscreen apps*, not app hosts | FloatSourceWindow `getFloatMainLayoutParams` (type 2008), `SpdStatusBarViewManager$11.onFloatAppOnClick` → `getLaunchIntentForPackage("com.android.chrome"/"com.google.android.youtube"/…)` → plain `startActivity` |
| The panel button positions are `FLOAT_BUTTON_ABS_X/Y` — currently **263 / −138 = parked off-screen**; both enable keys are `0` | live Settings.System dump |
| Unit has **Chrome, Google WebView, Google Maps, CarLetter (CarPlay)** installed; **no DocumentsUI** at all | packages capture + `resolve-activity OPEN_DOCUMENT` |
| Launcher already holds (on unit): default-home, location, notification-listener, **WRITE_SETTINGS pending grant**, overlay permission pending | device report |

Verdicts on the two "borrow the ROM's mechanism" hopes:
- `com.spd.floatwindow`: **dead** (broken vendor build, crash-loop; and it only ever
  hosted system-status overlays, not app windows).
- SpdSystemUI sidebar: **alive, and remote-controllable** — toggling the two Settings.System
  keys makes SystemUI show/hide its floating button + 9-app panel live. But its model is
  *floating shortcut panel + fullscreen handoff*, exactly the design already sketched
  below; it never hosts another app's UI.
- **2026-11-18 unit correction (in-app test):** the FIRST Diagnostics toggle wrote the keys
  directly — the SPD unit **refuses** custom Settings.System keys from third-party apps too
  (`IllegalArgumentException` ×4, guard kept the launcher alive). The vendor writes them as
  `android.uid.system` (`com.spd.setting` manifest: `sharedUserId=android.uid.system`).
  The interop door IS shipped: that app's **exported** `SettingService` exposes AIDL
  `putInt(int code,int)`/`getInt(int code,int)` (tx 26/27), codes 40033/40040 server-side
  write exactly the two enable keys (`GeneralControllerImpl` packed-switch base `0x9c41`).
  The Diagnostics button now tries direct writes first (other ROMs), then falls back to that
  service, and judges the outcome by re-reading the raw keys. No vendor code exists for the
  `FLOAT_BUTTON_ABS_*` position keys — parking/un-parking the button stays a one-time
  `adb shell settings put system FLOAT_BUTTON_ABS_Y 160` (adb shell uid is privileged).

## Option matrix

### A. Handoff (launch apps properly, make the card the command surface) — ★ recommended baseline
No embedding at all: cards become launchers/launch-controllers.
- Maps: `google.navigation:q=<destination>` (turn-by-turn instantly),
  `http://maps.google.com/maps?daddr=...` (route planner), geo: URIs; Waze/BRouter same idea.
- Chrome: plain launch or ACTION_VIEW with a URL (search/nav/history chips).
- CarPlay: launch CarLetter by component; it owns its own projection UX.
Works on **every** ROM from minSdk 21 up, zero permissions, zero risk. The vendor's own
premium UX (SystemUI float panel) is exactly this. This is the user-suggested "embed into
the launcher themselves rather than try to draw them inside" direction.

### B. Launcher-owned overlay panels (`SYSTEM_ALERT_WINDOW`, window type 2038)
Our own always-on-top panels above any fullscreen app: a mini-map, a Now-Playing chip, or
a clone of the vendor 9-app sidebar — with OUR app list, look and controls.
- Same mechanism the vendor sidebar uses (that one runs at type 2008 in uid 1000; we use
  2038 which any app can get via the "display over other apps" grant).
- Cost: one user grant (Diagnostics already reports `overlay windows = false`; add a
  request button → `ACTION_MANAGE_OVERLAY_PERMISSION`). API 30 has none of the Android 12+
  overlay restrictions.
- This is "floating windows over the launcher's *experience*" without any platform hack,
  and it survives ROM differences. **[bench: verify spdsettings exposes the grant page]**

### C. Native embedded content inside cards (no other apps involved)
For the map card specifically, render the map *ourselves*:
- **osmdroid** (OSM raster, Apache-2, no API key, offline MBTiles support, minSdk 21) —
  simplest safe choice; JVM-testable plumbing, testable on AVD with fake GPS.
- MapLibre Native (vector, offline, heavier) or a WebView+Leaflet card (system WebView is
  installed; zero new deps, full styling control).
- Google Maps SDK for Android: needs a Google Cloud API key + GMS billing (Essential
  credits model since 2025); on free hobby projects OSM backends avoid that entirely.
Then deep-link handoff (A) for turn-by-turn. This gives a live interactive map inside the
grid on **every** unit, rooted or not.

### D. Freeform windows — DEAD as-is, **live one file away under root**
Root is realistic because the bootloader is already unlocked and the build is debuggable:
1. Try `adb root` (with `ro.secure=0` adbd likely accepts it) → `adb disable-verity` +
   `adb remount` (needs one reboot) → drop a system-features XML declaring
   `android.software.freeform_window_management` (standard OEM enable; WMS reads it at boot
   via `PackageManager.hasSystemFeature`) → set the two globals → reboot → our
   **already-shipped freeform launch chain** (`PipWidget`/`PipFreeform`) opens Maps in a
   real draggable, resizable floating window.
2. If verity/remount resists: Magisk route — `dd` the boot partition (via root shell),
   patch with Magisk, `fastboot flash boot`, keep the stock boot dump for rollback. A
   Magisk *module* delivers the same feature XML systemlessly (toggleable, safe-mode-able).
3. Risks: the SPD framework fork already broke *something* in the freeform path once
   (silent downgrade) — it may have removed more; the experiment is decisive and each step
   is revertible (stock boot.img backup; `fastboot flash vbmeta --disable-verification` is
   revertible too). AOSP 11 freeform is beta-quality (focus/IME quirks) — expect rough
   edges even on success. Never offer this in the updater: it is one owner's bench.
**[bench]** exact commands staged in `tools/freeform-root-experiment/`.

### E. Root-only stronger variants (if D works, these also open)
- `/system/priv-app` install or `appops` grants → our launcher could hold
  ACTIVITY_EMBEDDING-equivalent surfaces… but ActivityView needs *signature* permissions
  (uid-1000), which root alone doesn't grant; that path stays closed unless we patch the
  framework (Xposed/LSPosed hook of `ActivityStarter`/`ActivityView` — deep mod, high
  maintenance, don't).
- LSPosed hook forcing `WINDOWING_MODE_FREEFORM` in `ActivityStarter` for our launches —
  only needed if the feature-XML route is stripped further by SPD.

### F. Dead ends (proven, don't revisit on this unit)
- AOSP-testkey `sharedUserId=android.uid.system` (private vendor key). **DEFINITIVE 2026-11-18:**
  the unit's platform cert was extracted from the captured `framework-res.apk` —
  `CN=release, OU=Zhantang` SHA-256 `5304915c…2888cb7` ≠ AOSP testkey `c8a2e9bc…`. Any
  testkey-signed system-uid app (our aospDebug, or third-party launchers like mini-aa's
  system-release that embed app tasks via `TaskOrganizer`+`SurfaceControlViewHost`) is
  `INSTALL_FAILED_SHARED_USER_INCOMPATIBLE` here — do not bench-test those APKs again.
- `com.spd.floatwindow` interop (broken APK; service crash-loops; no app-window classes).
- Broadcasting to vendor float-window receivers (its exported receivers are only
  BOOT/SCREEN_ON/BLUETOOTH/LOCALE; `FloatWindowService.onStartCommand` takes no extras).
- MediaProjection "mirror a card" (view-only, no input routing, per-session consent — bad UX).
- PiP (Maps/Chrome aren't PIP providers on this ROM).
- `cmd package set-home-activity`-style hacks (unrelated).

## Recommended stack (decide, don't choose one)
1. **Now (no root, all ROMs):** A (handoff) + B (our overlay mini-panels) + vendor-sidebar
   *enable/disable* dev switch in Diagnostics (one `Settings.System` write; the sidebar is
   the vendor's own feature, reversible by setting the key back to 0 — and the launcher
   should reposition `FLOAT_BUTTON_ABS_Y` from −138 to on-screen when enabling it).
2. **Next release:** C (osmdroid map card with GPS + destination chips + "Navigate" handoff
   to Maps/Waze/BRouter). This makes MAP work on any unit without any grants beyond location.
3. **Owner bench experiment (D):** freeform feature XML under root → resurrect APP OVERLAY
   as a *real* floating Maps/Chrome window where the ROM allows it; the card should
   auto-detect: freeform works → floating window; else → handoff (A). One card, graceful
   everywhere.
4. **Keep for other QCM units:** the existing freeform chain stays in the codebase; on ROMs
   with the feature present it works with no root at all (verified on API 37 AVD).

## Bench checklist for the next adb session (in order, cheap → risky)
1. `adb root` → `id` → if uid=0: `adb shell appops set com.openlauncher.app SYSTEM_ALERT_WINDOW allow`
   (or ask user to grant in spdsettings "display over other apps") → test our overlay panels.
2. Sidebar: DONE ON UNIT 2026-11-18 (in-app, no adb): Diagnostics ▸ "Toggle Vendor Float
   Sidebar" enabled it via the vendor SettingService; SystemUI drew the drag-circle button AND
   the app sidebar (YouTube/Chrome/Navi/Radio) live. Next time it's needed from adb:
   `settings put system SETTINGS_SHORTCUT_FLOAT 1` (+ `SIDEBAR_APPS_FLOAT_BUTTON 1`, position
   via `FLOAT_BUTTON_ABS_X 40`/`FLOAT_BUTTON_ABS_Y 200` — or just drag the circle on-device and
   check whether SystemUI persists the ABS keys itself). Set back to 0 to revert.
3. `pm path com.spd.media` → pull `MediaService.apk` → check `SETTING_LRC_FLOAT_WINDOW_MODE`
   owner (lyrics float window = the only other known vendor true-float feature; see if it
   exposes an interop surface). Also pull `com.carletter.car` (`/spd/app/CarLetter`) to check
   whether the CarPlay app exposes any launch/embed surface before settling for plain handoff.
4. Freeform root experiment (`tools/freeform-root-experiment/README.md`), one step at a time,
   boot image dumped before any flash.
5. After any freeform success: launch APP OVERLAY card → `dumpsys window windows | grep -i freeform`
   + screenshot = the final verdict for this ROM.

## What the app already does toward this
- APP OVERLAY card with 3-stage launch chain + freeform/free-resize probes and Diagnostics
  (works as designed on ROMs that keep AOSP freeform; degrades to fullscreen elsewhere).
- Diagnostics freeform probe now identifies float-window owners (`appFloatOwnerSummary`).
- SAF pickers guarded (unit has no DocumentsUI; unguarded taps crash the launcher — fixed).
- Vendor float sidebar toggle shipped in Diagnostics (branch `dev`) for the next bench session.

## Option C prototype — BUILT and AVD-proven (branch `exp-mapcard`, 2026-11-18)
`MAP_NATIVE` card (widget-library entry "MAP (LIVE)", first instance defaults to 2×2):
- osmdroid 6.1.20 (Apache-2, no API key, inline Gradle dep) + OSM MAPNIK tiles with an
  honest User-Agent (tile etiquette); tile cache = offline-friendly; edit mode never
  instantiates the MapView (no tile traffic, no touch fights with resize handles).
- Live GPS marker via the existing `LauncherViewModel.location` StateFlow; first fix frames
  the view (accuracy-tiered zoom, pure `MapCardLogic.initialZoomFor` + JVM tests
  `MapCardLogicTest`), later fixes only move the marker so driver pan/zoom survives.
- NAVIGATE chip = standard `geo:` handoff (AVD-verified: Google Maps MapsActivity opened);
  RECENTRE chip re-frames; `geo:` resolves on the AVD and is understood by every maps app.
- Card body is ONE native view subtree (FrameLayout > MapView + TextView chips): the classic
  Compose `AndroidView` compositing path draws the view subtree OVER sibling Compose
  overlays, which mis-placed Compose-rendered chips (screenshot bug 2026-11-18 00:47);
  native chips fix it on all API levels (InteropView interop needs 29+, our floor is 21).
  Chips are plain DeviceDefault-safe TextViews, not Material Buttons (launcher Activity
  theme is not MaterialComponents → Button inflation would crash).
- AVD-verified: tiles, marker, lat/lon line, chips placed, NAVIGATE→Maps. Head-unit GPU
  cost and real GPS NOT verified yet (AVD only). Not merged to `dev` — owner review first;
  CI only builds dev/main pushes + PRs, so review happens on draft PR #1 (artifact from that run).
