# Freeform root experiment (SPD trinket unit, 2026-11-18)

Goal: make AOSP freeform windows exist on a ROM that ships the code but hides the
feature flag, so the launcher's existing APP OVERLAY freeform chain gets a real
floating, resizable Maps/Chrome window. Everything here is owner-bench only —
never shipped in the app or the updater.

Unit preconditions already verified on 2026-11-17 (getprop capture):
`ro.boot.flash.locked=0` (bootloader unlocked), `verifiedbootstate=orange`,
`ro.debuggable=1`, `ro.secure=0`, `ro.adb.secure=0`, API 30.

Run with adb over wireless debugging (`adb -s <serial> ...`). Do ONE rung at a
time; stop at the first rung that fails and report where it stopped. Rung 0-2
are settings-only and fully revertible. Rung 3 modifies /system (revertible).
Rung 4 touches the boot partition — back it up FIRST.

## Rung 0 — does root adb just work?
    adb -s <S> root
    adb -s <S> shell id          # want uid=0(root)
If adbd refuses ("not allowed on this device"), go to Rung 4 (Magisk) directly.

## Rung 1 — backup (do this even before touching settings)
Dump the boot partition while root is available (rollback insurance):
    adb -s <S> shell ls -l /dev/block/by-name/          # find boot (+ recovery, vbmeta)
    adb -s <S> shell "dd if=/dev/block/by-name/boot of=/sdcard/Download/boot_stock.img"
    adb -s <S> shell "dd if=/dev/block/by-name/vbmeta of=/sdcard/Download/vbmeta_stock.img"
    adb -s <S> pull /sdcard/Download/boot_stock.img <PC backup dir>
    adb -s <S> pull /sdcard/Download/vbmeta_stock.img <PC backup dir>
Copy both off the unit immediately.

## Rung 2 — enable the freeform dev switches (already partially done, make sure)
    adb -s <S> shell settings put global enable_freeform_support 1
    adb -s <S> shell settings put global force_resizable_activities 1
(Revert = set both to 0.)

## Rung 3 — declare the missing system feature in /system
    adb -s <S> disable-verity      # may require reboot before remount works
    adb -s <S> reboot; adb -s <S> wait-for-device
    adb -s <S> root                # re-run after reboot
    adb -s <S> remount             # want: "Verity was successfully disabled..."
    adb -s <S> push android.software.freeform_window_management.xml /system/etc/permissions/
    adb -s <S> shell chmod 644 /system/etc/permissions/android.software.freeform_window_management.xml
    adb -s <S> reboot
    # after boot, verify the feature is now declared:
    adb -s <S> shell pm list features | findstr freeform     # want: Feature android.software.freeform_window_management: true
Then test the launcher's APP OVERLAY card (set an app, tap it) and get the verdict:
    adb -s <S> shell dumpsys window windows | findstr /i "WindowingMode mWindowingMode freeform"
WindowingMode=5 on the Maps task = SUCCESS. fullscreen = the SPD fork strips freeform
deeper (then only Rung 5 / Xposed can fix it — probably not worth it).
Revert Rung 3: `adb root && adb remount && adb shell rm /system/etc/permissions/android.software.freeform_window_management.xml && adb reboot`
(if disable-verity was needed and you want it back: flash vbmeta_stock.img via fastboot).

## Rung 4 — Magisk (only if Rung 0 refused root)
1. Patch: install the Magisk APK (Chrome is on the unit), open it, "Select and Patch a
   File" → `Download/boot_stock.img` → it writes magisk_patched-*.img to the unit.
2. Pull it, flash it:
    adb -s <S> pull /sdcard/Download/magisk_patched-*.img .
    adb -s <S> reboot bootloader
    fastboot flash boot magisk_patched-<x>.img
    fastboot --disable-verity --disable-verification flash vbmeta vbmeta_stock.img   # keep stock vbmeta bytes, just disable flags
    fastboot reboot
3. In Magisk app: enable Zygisk; install the freeform module in this folder
   (`tools/freeform-root-experiment/module/` — zip it) via Flash from /sdcard.
4. Same Rung-3 verification after reboot. Rollback: `fastboot flash boot boot_stock.img`.

## Rung 5 — only if Rung 3 succeeded in declaring the feature but freeform is STILL downgraded
LSPosed module hooking `com.android.server.wm.ActivityStarter` to force
`WINDOWING_MODE_FREEFORM` for calls from our launcher. (Not written; decide after
reading the Rung-3 dumpsys verdict. This is deep-framework territory — maintenance
risk, and every future unit would need root too, so it must never be the shipped design.)

## Interpretation guide
- Feature declared + freeform window appears → the dream works on THIS unit; keep the
  APP OVERLAY card as-is (it already tries freeform first). Consider a Settings ▸
  Diagnostics line: "freeform feature flag now true (bench-modified)".
- Feature declared but requests still downgrade → SPD fork removed freeform launch
  support in its forked WindowManager code (they actively did modify it once —
  the silent downgrade proves it). Then ship options A/B/C from
  docs/float-window-research.md and stop here.

Safety rules: never flash anything you didn't dump from THIS unit first; never run
fastboot erase commands; if the unit boots to anything unexpected, flash boot_stock.img.
