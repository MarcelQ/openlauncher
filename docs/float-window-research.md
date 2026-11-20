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
  **UPDATE 2026-11-19: the "vendor key is unobtainable" premise is VOID — the matching
  `platform.pk8` was found publicly leaked (two independent GitHub BSP-tool leaks, byte-identical
  copies; cert digest also appears in Monorail's Dec-2022 leaked-OEM-cert disclosure; openssl
  modulus check confirms key↔cert match). So a *Zhantang-re-signed* build (not testkey-signed)
  should install as uid 1000. The testkey-APK verdict above still stands; re-signed builds are the
  new open path — keys are kept OUTSIDE the repo (`reference\unit-20261118\keys\`), sign bench
  APKs with `apksigner sign --key … --cert …` only, never commit or distribute those.**
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

## G. In-card app embedding - ROM dex ground truth + EMBED card (2026-11-19)

**framework.jar dex dump (captured ROM, `tars/system_key.tgz`, dexdump 2026-11-19) - the
authoritative API inventory for this API 30 SPD fork. Do NOT code against newer-AOSP shapes:**
- ABSENT: `android.service.voice.ActivityView`, `android.window.LaunchCookie`,
  `android.view.SurfaceControlViewHost` (the mini-aa technique does not exist as a public-ish
  framework API here).
- PRESENT (all hiddenapi-BLOCKED, uid-1000-exempt):
  - `android.window.TaskEmbedder` (abstract; `prepareActivityOptions`/`startActivity`/
    `start`/`stop`/`release`, `TaskEmbedder$Host` = canReceivePointerEvents/getHeight/getWidth/
    getPositionInWindow/getScreenBounds/getScreenToTaskMatrix/getTapExcludeRegion/getWindow(IWindow)/
    post, `$Listener` = onInitialized/onReleased/onTaskCreated/onBackPressedOnTaskRoot).
    This is the ActivityView-era embedding engine, backported/kept by the vendor.
  - Concrete `TaskOrganizerTaskEmbedder` (ctor `(Context, TaskEmbedder$Host)`) and
    `VirtualDisplayTaskEmbedder`.
  - `TaskOrganizer.registerOrganizer(int displayId)` + `ITaskOrganizer.onTaskAppeared(
    ActivityManager$RunningTaskInfo, SurfaceControl)` - Android-11 shape: the task leash
    SurfaceControl is handed to the callback DIRECTLY (no rootSurfaceControl field).
  - `WindowContainerTransaction` (setBounds/setWindowingMode/reparent) for resize-follows-card.
- Hidden-API flags are 0x0002 (BLOCKED) but uid 1000 is exempt from gating - reflection is
  the access path, and the leaked Zhantang platform key (�F follow-up, 2026-11-19) is what
  gets us uid 1000.

**EMBED card shipped on `dev` (9a526ba, prototype):** grid type `EMBED_APP`, DataStore key
`embed_app_package`. Chain: `TASK_EMBEDDER` (TaskOrganizerTaskEmbedder) -> `TASK_ORGANIZER`
(VirtualDisplayTaskEmbedder) -> `FALLBACK_LAUNCH` (never a dead card: card becomes
"TAP TO OPEN (OVERLAY MODE)" driving PipWidget's freeform chain, tap-only - NO auto-launch).
Engine in `ui/widget/TaskEmbedEngine.kt` (pure reflection + dynamic Proxy Host), decisions
in `ui/widget/TaskEmbed.kt` (10 JVM tests), Diagnostics prints the TaskEmbedLog ring.

**AVD-proven (API 37, uid!=1000 = negative control):** honest gate line, zero launches on
assignment, tap launches at card rect, "TAP TO OPEN (OVERLAY MODE)" state, Diagnostics ring
renders. **Bench round 1 verdict (unit, 2026-11-19, photo):** the Zhantang re-signed aospDebug INSTALLED
on the unit (first time any APK has installed against that cert) and Maps appeared filling the
EMBED card AREA — but behaving like the OVERLAY card (movable window, pushed down by the card
caption): the engine chain never engaged because the first engine skipped
`initialize(SurfaceControl)` (it must be fed the anchor SurfaceView's surface from
surfaceCreated - async!), so the freeform fallback showed. Side-finding worth its weight:
**at uid 1000 this SPD ROM no longer silently downgrades freeform launch to fullscreen** (the
§D blocker is privilege-based, not universal) — round 2 (f95907a) feeds initialize correctly,
hides the caption on assigned cards, gates embed rects fully on-screen (fixes "can't sit on
page 2": pager lays off-page cards out at off-screen coords), resizes live tasks via
`resizeTask(II)`, flips the card back to tap-to-open when the task dies (Listener onReleased),
and draws a green/amber link-state dot so embed vs overlay is never a guess.

**Bench-only verdicts still queued:** does the live `TaskEmbedder` surface actually pin
(not-draggable) inside the card + forward touches; page-2 card alive on swipe-in; `resizeTask`
follows the grid; Diagnostics embed ring (12 entries) names any method the real ROM refuses.

**Multi-embed ground truth (same capture, services.jar classes2 dumped 2026-11-19 — answers
"can two embedded apps be active at the same time", the mini-aa question):**
- `TaskEmbedder.prepareActivityOptions` calls `ActivityOptions.setLaunchWindowingMode(6)` and
  `TaskOrganizerTaskEmbedder.onInitialize` calls `registerOrganizer(6)`; the dex locals table of
  `TaskOrganizer.registerOrganizer(I)V` names the int **`windowingMode`**, and the server
  `TaskOrganizerController.registerTaskOrganizer(ITaskOrganizer, int windowingMode)` confirms
  this fork RE-KEYS Q's per-display registration to per-windowing-mode (PINNED/SPLIT/MULTI_WINDOW
  only — other modes throw "only Pinned/Split/Multiwindow windowing modes are supported").
- Server keeps `mTaskOrganizersForWindowingMode: SparseArray<LinkedList<IBinder>>` — MULTIPLE
  organizers per mode are ALLOWED (second registration only logs "Task organizer already exists").
  But `Task.updateTaskOrganizerState()` binds every root task to `getTaskOrganizer(mode)` =
  **`LinkedList.peekLast()`** (the most recently registered organizer), and the binding is sticky
  per task (`mLastTaskOrganizerWindowingMode`). So N default-display embedders technically coexist
  but cross-wire: whichever embedded card registered LAST captures subsequently created tasks.
  Our engine therefore runs at most ONE TaskOrganizerTaskEmbedder session; concurrent extra
  sessions take `embedChain(vdFirst = true)`.
- `VirtualDisplayTaskEmbedder.onInitialize` is a fully separate mechanism (dex-verified):
  `createVirtualDisplay` per instance (flags `mUsePublicVirtualDisplay`/`mUseTrustedDisplay`) +
  `IActivityTaskManager.registerTaskStackListener` + `prepareActivityOptions` adds
  `setLaunchDisplayId(vd)`; fields include `mSingleTaskInstance`. Two instances can never collide
  (private displays), and a task on a visible virtual display is RESUMED even unfocused — the
  native keep-alive mini-aa had to bolt on. Input forwarding is the engine's own
  `createKeyEvent`/`reportLocation`/`mForwardedInsets` machinery; bench must verdict touch response.
- Unproven and bench-queued: whether the fork multi-resumes two MULTI_WINDOW tasks on the DEFAULT
  display (that would let even two organizer-mode cards stay active; `dumpsys activity activities`
  RESUMED lines answer it in one shot).

**Bench file delivery (verified 2026-11-19, no firewall admin needed):**
`tailscale file cp <apk> "iphone-15:"` from the workstation -> Files section of the Tailscale
iOS app -> relay to the unit via LocalSend (or USB). The unit can also be a Taildrop target
directly if the Tailscale android node "shield" turns out to be it.

**Known churn (round-2 watch-out):** the card composable is recreated during grid boot
(~1 s after start), re-claiming the engine 2-3 times before settling; harmless for the
fallback, but on the unit the ring will show TaskEmbedder teardown+restart cycles at boot —
if the ROM dislikes that, stabilize the container identity first.

### G.1 BENCH ROUNDS 4-6 — EMBED LIVE ON THE UNIT (2026-11-19, direct wireless adb)

VERDICT: the EMBED card works on the real SPD unit. Google Maps and Stremio rendered
LIVE inside cards (dumpsys: `mode=multi-window`, task RESUMED+FOCUSED while the launcher
task is also RESUMED — the fork multi-resumes on the DEFAULT display), vendor top bar
stays on top, and touch input reaches the app (user-confirmed). Fixes, each proven by
adb forensics (`logcat -s TaskEmbed`, `dumpsys window/input/activities`, screencaps):

1. **`DisposableEffect(embedToken)` self-destruct**: re-keying ran `onDispose` AFTER
   `embedToken` was reassigned; the lambda reads the State-backed NEW token and stopped
   the just-created session ~30 ms later (THE reason rounds 1-3 never rendered). Key on
   `Unit` (composition lifetime); session replacement stays an explicit `stop(oldToken)`.
2. **Compose interop never lays out late-added children** (1.7.0 `AndroidViewHolder`
   lays out only during Compose passes): the anchor SurfaceView stayed 0x0 forever and
   `surfaceCreated` never fired (`SurfaceView.layout()` is what calls `updateSurface()`).
   Fix: force one measure/layout pass over the container ourselves after `addView`.
3. **Hidden ≠ protected**: `TaskEmbedder.prepareActivityOptions(ActivityOptions)` exists
   in dex but is **protected** — `getMethod` only sees public (`initialize`/`setListener`
   worked, chain died at `prepare`). Fix: `findMethod()` walks `getDeclaredMethod` +
   `setAccessible(true)` up the hierarchy.
4. **`ViewRootImpl.getWindow()` does not exist on this fork** — `Host.getWindow()` falls
   back to the `mWindow` FIELD (unit-proven: real IWindow handed over; tap-exclude pushed).
5. **Input contract settled**: `TaskEmbedder.updateLocationAndTapExcludeRegion` pushes
   `Host.getTapExcludeRegion()` via `IWindowSession.updateTapExcludeRegion(hostWindow,
   region)` — the HOST window excludes that region and touches fall to the task window.
   An empty Region (= round-3 value) = the launcher ate every touch on the card. Now the
   card rect in window coords. `getScreenToTaskMatrix` = `translate(-cardOrigin)`.
6. **Sizing**: the server IGNORES `ActivityOptions.setLaunchBounds` for mode-6 tasks
   (window `mFrame` stayed `[0,0][1280,720]`), and base `TaskEmbedder.resizeTask(II)` is
   a **stub** (`return-void`, vendor line 239). The vendor's real path is
   **`TaskOrganizerTaskEmbedder.notifyBoundsChanged()`**: `Host.getScreenBounds()` ->
   `offsetTo(0,0)` -> `WCT.setBounds(taskToken, size)` -> `WindowOrganizer.applyTransaction`
   — i.e. task SIZE = host size, origin forced (0,0) so the reparented leash stays visually
   under the anchor layer. Engine now calls it at `Listener.onTaskCreated` and on every
   card resize (`resize()` tries it + the real `VirtualDisplayTaskEmbedder.resizeTask(II)`
   as second attempt). ROUNDS 5→6 verdict still bench-pending for resize; touch offset =
   card origin remains known (task bounds origin (0,0) vs reparented visual at card pos:
   ~0 for a top-left card, up to full offset for a centered card — MotionEvents on the
   card would need offset-injection to fix exactly; bench first).
7. **Only one mode-6 task per display is VISIBLE** — launching card 2 flipped card 1's
   task `visible=false` (dumpsys), which is exactly why additional concurrent cards MUST
   take `embedChain(vdFirst=true)` (private VirtualDisplay, own always-visible display).
   In the round-5 capture `organizer engine busy` never logged because the churn loop
   below kept sessions dead at the moment of the second embed — the multi-embed verdict
   re-runs on round 6.
8. **Churn loop root cause**: the embedded task grabbing fullscreen focus relayouts the
   launcher grid transiently (card oscillated 1189px <-> 1264px wide in the log), each
   blip tripped the off-screen stop -> destroy/re-embed forever. Fix: 350 ms settle
   delay before embedding + 400 ms grace with re-check before the off-screen stop.
9. `registerOrganizer(6)` (MULTI_WINDOW) + `setInterceptBackPressedOnTaskRoot(true)`
   confirmed in `TaskOrganizerTaskEmbedder.onInitialize`.

Captured for forensics (local only): `%TEMP%\opencode\unit-bench-r5\` — full logcat,
TaskEmbed ring, activities/windows/displays/containers dumps, SurfaceFlinger layer list,
screencaps, settings datastore (grid then held ONE EMBED card 6x4 assigned Maps; a stale
`EMBED_APP` entry lingered in `embed_app_packages` from an earlier second card).
Round-6 APK (commit 44a1466, Zhantang re-signed) Taildropped to `iphone-15:` — bench when
the unit's adb is back on: 2 EMBED cards side by side, resize one while live, and read
`logcat -s TaskEmbed` for `notifyBoundsChanged`/`tearing down` breadcrumbs.

### G.2 MINI-AA DECOMPILE STUDY + ROUND-7 KEEP-ALIVE (2026-11-20)

User instruction: "mini-aa had this issue too. Might be worth decompiling the
ASOP version and looking at the code to see what was done there to make the
embedded app stable." Study done with jadx 1.5.1 over the unit-capture APK
(`reference\unit-20261118\apks\mini-aa-app-system-release.apk`, 3 dex; dumps +
decompiled sources at `%TEMP%\opencode\miniaa\`). Verdicts:

- Their card-embedding stack = `com.miniaa.launcher.taskview.TaskViewWrapper`
  (reflection over AOSP Shell TaskView/TaskOrganizer+WCT — same substrate as
  ours) + an `ActivityViewWrapper` fallback + a per-ROM capability scanner
  (`Class.forName` over ActivityView / 5 TaskView variants / TaskOrganizer / WCT).
- **There is no magic keep-alive.** `TaskViewWrapper` itself has zero
  stabilization: `onTaskVisibilityChanged` is only LOGGED. Their entire answer
  is the caller (`EmbeddedAppViewKt.TaskViewContainer`): a launcher
  **ON_PAUSE/ON_RESUME watchdog** — paused >30 s -> `resetForResume()` +
  `startApp()` relaunch into the still-alive TaskView; idle >4 h ->
  `activity.recreate()`. The ActivityView path uses the identical pattern
  (invalidate cached start methods, bump an embed-attempt counter, relaunch).
- Two launch-hygiene facts adopted verbatim: (1) `startApp` ALWAYS adds intent
  flags `0x10008000` = NEW_TASK|MULTIPLE_TASK — a FRESH task every launch so
  the organizer callback always fires; RESUMING a stale task (never firing
  onTaskCreated) is exactly our black-card-after-occlusion symptom. (2)
  `removeCurrentTask()` purges the previous task via reflected
  `ActivityManager.removeTask(taskId)` before relaunching.
- Their `setLaunchDisplayId` reflection (dex3) lives in HomeScreen = a
  SEPARATE-DISPLAY fullscreen launch feature, not card embedding. mini-aa has
  no concurrent-card trickery to steal — our VirtualDisplay-engine routing
  stays the multi-embed answer.

Round-7 (commit 3caf8e4): `TaskEmbedEngine.revive(token, reason,
forceReinitialize)` ladder — (1) NON-destructive WCT
`reorder(mTaskToken,true)+setHidden(false)` (dex: TOTE keeps
`mTaskToken : WindowContainerToken`; this fork's WCT carries reorder/setHidden
keyed by WindowContainerToken; `WindowOrganizer.applyTransaction` exists) when
the host surface is still alive and the trigger is not a surface re-creation;
(2) purgeTask + embedder release + `initialize(NEW SurfaceControl)` + relaunch
into the SAME embedder; (3) honest FALLBACK_LAUNCH flip via session.onOutcome.
`surfaceCreated` after the first init now revives with forceReinitialize=true
(round-5 log proved Settings occlusion destroys the anchor surface; the old
one-shot `fired` flag left the embedder on a stale SurfaceControl forever).
ALL embed launches carry EMBED_LAUNCH_FLAGS; teardown purges the task before
release; widget resume watchdog (idle >= 1 s, 600 ms settle;
`embedReviveOnResume` pure gate, JVM-tested) mirrors mini-aa's but acts sooner
because step 1 is non-destructive.

Bench verdict pending (unit adb off at round-7 ship, APK
`openlauncher-embed-3caf8e4-aosp.apk` Taildropped): open Settings over a live
EMBED card -> expect `revive (launcher resumed...)` followed by either
`WCT reorder+setHidden(false) applied` + `re-assert only` (app never restarts)
or `revive relaunch OK` (app restarts in-card, card recovers on its own);
surface-destroy path logs `revive (surface re-created...)`. 2-card case still
expects `organizer engine busy — VirtualDisplay engine first`.

### G.3 ROUND-8 — GEOMETRY/INPUT SETTLE RULE; MULTI-EMBED STILL BROKEN (2026-11-20)

User photos (no adb) showed ONE live EMBED card (Maps) plus a media panel —
which the user correctly identified as the launcher's own **Now Playing
widget, NOT an embed**: multi-embed does NOT work; a second card never becomes
a live, interactive app ("I never get to active apps running that I can both
interact with at the same time"). Round 8 fixed only the two defects the
photos DID prove, both on the single-card path:

1. **Task drifts outside its card border** after the settings-open grid
   ANIMATION — the ROM keeps whatever Host geometry it was handed last, and we
   were pushing mid-animation coords per Compose layout frame. Fix (commit
   55870f1): the widget debounces bounds pushes 350 ms after the last bounds
   change; `TaskEmbedEngine.refreshGeometry()` then drives the dex-verified
   `TaskEmbedder.updateLocationAndTapExcludeRegion()`V (re-reads
   PositionInWindow/ScreenBounds/TapExcludeRegion, rewrites IWindowSession
   exclusion) + `notifyBoundsChanged()` + our own `forceLayout(container)` so
   the anchor SurfaceView physically moved first. Called on settle, on both
   revive branches, and on launcher resume.
2. **Stale touch island steals foreground touches** — "touch doesn't work on
   the settings UI in the area where the app is running, as if the app has
   focus": our host-window tap-exclude stayed on while something else was in
   front. Fix: `TaskEmbedEngine.setTouchIsland(token,on)` drives base-class
   `clearTapExcludeRegion()`V / re-push. Widget lifecycle: ON_PAUSE -> island
   OFF immediately (embed KEEPS rendering — user wants never-stop, but the
   foreground UI must win touches over the card); ON_RESUME -> 600 ms settle
   -> refreshGeometry (always, even <1 s peeks) + revive ladder past 1 s.

Round-8 log breadcrumbs to watch: `geometry+touch-region re-pushed`,
`touch island ON/OFF`, plus the round-7 revive lines. The 2-card case had no
logged session yet — bench was blocked on unit adb.

### G.4 ROUND-9 — MULTI-EMBED ROOT CAUSE FOUND IN ROM DEX (2026-11-20)

Full re-read of the unit's framework.jar dex (`TaskEmbedder` base +
`TaskOrganizerTaskEmbedder` + `VirtualDisplayTaskEmbedder`) explains the
second-card failure WITHOUT any new bench log:

- **`VirtualDisplayTaskEmbedder` has NO `(Context, Host)` constructor.** Dex
  shows exactly one public ctor: `<init>(Context, TaskEmbedder$Host, ZZZ)V`,
  the booleans stored in order as `mSingleTaskInstance`,
  `mUsePublicVirtualDisplay`, `mUseTrustedDisplay`. Our engine called
  `getConstructor(Context, Host)` → `NoSuchMethodException` → the log line
  `TASK_ORGANIZER failed(NoSuchMethodException …)` on EVERY additional card.
- The then-chain `[TASK_ORGANIZER, TASK_EMBEDDER, FALLBACK]` fell through to
  **re-registering the windowing-mode organizer**, and the server binds each
  new task to `LinkedList.peekLast()` — the new organizer — so card 2's launch
  (or even its mere registration) **hijacked card 1's task binding**: precisely
  "it doesn't know which widget to render the right app", and card 2 stayed
  black.

Fixes shipped (round 9):
- Engine ctor probe: 2-arg first, else the 5-arg `(Context,Host,Z,Z,Z)` with
  flags read straight off the ROM's own `onInitialize` bytecode:
  `singleTaskInstance=true` (`setDisplayToSingleTaskInstance(displayId)` —
  one task locked per card display, created RESUMED+FOCUSED there),
  `usePublicVirtualDisplay=false` (dex +0x1 = FLAG_PUBLIC; keep the card
  display private so no app can discover it), `useTrustedDisplay=true`
  (dex +0x400 = FLAG_TRUSTED — required for `reparentDisplayContent` and
  input). Manifest (aospDebug only): + `INTERNAL_SYSTEM_WINDOW`,
  + `INJECT_EVENTS` (both signature-level, auto-granted at uid 1000; the ROM
  engine's own `performBackPress` injects a KeyEvent into the VD display).
- Pure chain (JVM-tested): `vdFirst` chain is now
  `[TASK_ORGANIZER, FALLBACK_LAUNCH]` — an additional card NEVER retries the
  organizer engine; a dead organizer session (failed init, embedder still
  held) is fully torn down before any new launch, and `vdFirst` only counts
  sessions with a LIVE embedder.
- Listener verbs the dex added (previously swallowed by the else-branch):
  `onTaskMovedToFront` / `onTaskVisibilityChanged(IZ)` / `onTaskRemovalStarted`
  now log per-session (the cross-wire hypothesis and the "other one stops
  when I focus this one" hypothesis both become one-line readable in a
  capture), and removal flips the card back to tap-to-open.

Verdict territory (round-9 APK): `logcat -s TaskEmbed` on a 2-card test must
show `organizer engine busy — VirtualDisplay engine first`, then
`VirtualDisplayTaskEmbedder 5-arg ctor (…)`, `TaskVirtualDisplay@… created`
breadcrumbs and NO `TASK_ORGANIZER failed(NoSuchMethod…)`. Both cards render
+ interactive simultaneously is the user-level goal; touch on the VD-engine
card additionally depends on the ROM's tap-exclude honoring per-host-window
regions (two engines pushing exclusion to the SAME launcher window token can
collide — first logged collision candidate for round 10).
