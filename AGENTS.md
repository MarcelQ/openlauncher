# Project guidance for AI coding agents

## Project
This is the public OpenLauncher Android project. Treat its checked-in source as the implementation source of truth.

## Working rules
- Before changing code, inspect the relevant modules, Gradle configuration, Android manifest and
  existing architecture.
- Prefer small, reviewable changes. Explain the plan before multi-file refactors.
- Do not silently change package IDs, signing, min/target SDK, Gradle/AGP/Kotlin versions, or app permissions.
- Preserve offline-first behaviour and compatibility with aftermarket Android head units, including older Android versions where the project supports them.
- Unit tests exist for the TPMS wire protocol (`app\src\test\java\com\openlauncher\app\tpms\`)
  and for the multi-page widget grid engine — placement, page resize guards, duplicate
  instance ids, legacy migration (`app\src\test\java\com\openlauncher\app\data\GridLayoutTest.kt`;
  the engine itself lives in `app\src\main\java\com\openlauncher\app\data\GridLayout.kt`, framework-free).
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
- **In-app updater** (Settings ▸ Updates, `data\UpdateChecker.kt` + `data\ApkDownloader.kt`): polls
  `api.github.com/repos/MarcelQ/openlauncher/releases/tags/<dev-latest|main-latest>` and offers the
  channel's APK (user picks dev or main; debug builds default to dev). Requires the CI release NOTES
  to carry the marker line `versionCode=N versionName=X commit=<sha>` — produced by the
  `:app:printVersionInfo` Gradle task inside `build-apk.yml`. **If the workflow's notes step or the
  regex in `UpdateChecker.kt` change, keep both in sync** (decision logic: `UpdateCheckerTest.kt`).
  The app's own build is identified by `BuildConfig.GIT_SHA` (auto via `git rev-parse` at
  configuration time; "unknown" off git). Downloads need `REQUEST_INSTALL_PACKAGES` (user must also
  allow "install unknown apps" on API 23+).
