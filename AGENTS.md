# Project guidance for AI coding agents

## Project
This is the public OpenLauncher Android project. Treat its checked-in source as the implementation source of truth.

## Working rules
- Before changing code, inspect the relevant modules, Gradle configuration, Android manifest and
  existing architecture.
- Prefer small, reviewable changes. Explain the plan before multi-file refactors.
- Do not silently change package IDs, signing, min/target SDK, Gradle/AGP/Kotlin versions, or app permissions.
- Preserve offline-first behaviour and compatibility with aftermarket Android head units, including older Android versions where the project supports them.
- Unit tests exist only for the TPMS wire protocol: `app\src\test\java\com\openlauncher\app\tpms\`
  (JVM only, no device). No `app\src\androidTest` sources and no CI. Run them with
  `java -cp gradle\wrapper\gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain :app:testDebugUnitTest`
  and build with the same wrapper plus `:app:assembleDebug` (this checkout has no `gradlew.bat`).
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
