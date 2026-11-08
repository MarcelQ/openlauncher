package com.openlauncher.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Screen brightness via Settings.System.SCREEN_BRIGHTNESS (0..255).
 * Writing needs android.permission.WRITE_SETTINGS: a normal permission on
 * API < 23 (pre-granted, so old aftermarket units just work) but an app-op
 * from API 23 on — the user grants it on the "Modify system settings" page,
 * which canWrite()/grantIntent() route to.
 *
 * Percent (1..100) is the app-facing unit; it is scaled to the 0..255 raw
 * range only at the provider boundary.
 */
object SystemBrightness {

    fun canWrite(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
        runCatching { Settings.System.canWrite(context) }.getOrDefault(false)

    /** Settings page where the user toggles "Allow modifying system settings". API 23+. */
    fun grantIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Current brightness as 1..100, or -1 if the provider holds no usable value. */
    fun currentPct(context: Context): Int {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        }.getOrDefault(-1)
        return if (raw in 0..255) (raw * 100 / 255).coerceAtLeast(1) else -1
    }

    /** True when the system's own auto-brightness is on — direct writes won't stick. */
    fun systemAutoEnabled(context: Context): Boolean =
        runCatching {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        }.getOrDefault(false)

    /**
     * Forces manual brightness mode (otherwise the automatic controller ignores
     * our value) and writes pct. Returns false without effect when WRITE_SETTINGS
     * has not been granted.
     */
    fun setPct(context: Context, pct: Int): Boolean {
        if (!canWrite(context)) return false
        val raw = pct.coerceIn(1, 100) * 255 / 100
        return runCatching {
            val resolver = context.contentResolver
            Settings.System.putInt(
                resolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, raw)
            true
        }.getOrElse { e ->
            android.util.Log.e("Brightness", "setPct failed", e)
            false
        }
    }
}
