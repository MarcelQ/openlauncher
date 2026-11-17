package com.openlauncher.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher

/**
 * Launches a Storage-Access-Framework document picker (`OpenDocument` /
 * `GetContent` contract) without crashing on head-unit ROMs that ship
 * WITHOUT any document provider: the SPD trinket unit (2026-11-17 report)
 * has no `com.android.documentsui` at all, so `launcher.launch(...)` throws
 * [ActivityNotFoundException] straight through the Compose onClick and kills
 * the launcher process (crash log 11-17 22:08, SettingsScreen wallpaper
 * button). Every SAF call site must go through this guard.
 */
fun launchSafPicker(
    launcher: ActivityResultLauncher<Array<String>>,
    mimeTypes: Array<String>,
    context: Context,
) {
    try {
        launcher.launch(mimeTypes)
    } catch (t: ActivityNotFoundException) {
        Toast.makeText(
            context,
            "This ROM has no file-picker app (DocumentsUI removed) — " +
                "custom wallpaper/soundboard files are unavailable",
            Toast.LENGTH_LONG,
        ).show()
    }
}
