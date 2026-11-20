package com.openlauncher.app.data

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Logcat tag for the updater plumbing: `adb logcat -s Updater`. Breadcrumbs
 *  only — the GitHub token is never part of any log line. */
const val UPDATER_TAG = "Updater"

/**
 * Android-side plumbing for the in-app updater: DownloadManager fetch into the app's
 * own external files dir (no storage permissions needed — this keeps working on every
 * API level down to 21), plus the install handoff via FileProvider
 * (authority ${applicationId}.fileprovider, see res/xml/file_paths.xml).
 */
object ApkDownloader {

    private fun authority(context: Context) = context.packageName + ".fileprovider"

    /** Deletes a previous partial/completed copy then enqueues a fresh download.
     *  [authHeader] (e.g. "Bearer <PAT>") is only ever set for the PRIVATE
     *  GitHub asset-API URL — GitHub then needs the octet-stream Accept so it
     *  hands the raw bytes instead of the JSON metadata, and its 302 to the
     *  CDN carries no credentials of its own. Headers go ONLY to api.github.com;
     *  the token itself must never be logged. */
    fun download(context: Context, url: String, fileName: String, authHeader: String? = null): Long {
        downloadedFile(context, fileName)?.delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("OpenLauncher update")
            setMimeType("application/vnd.android.package-archive")
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            // VISIBILITY_HIDDEN is NOT allowed for normal apps (throws
            // "Invalid value for visibility: 2" at enqueue unless system/WRITE_SETTINGS) —
            // VISIBLE just shows the system progress notification while downloading.
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            setRequiresDeviceIdle(false)
            if (authHeader != null) {
                addRequestHeader("Authorization", authHeader)
                addRequestHeader("Accept", "application/octet-stream")
                addRequestHeader("X-GitHub-Api-Version", "2022-11-28")
            }
        }
        return dm.enqueue(request)
    }

    data class DownloadStatus(val exists: Boolean, val running: Boolean, val percent: Int, val failed: Boolean)

    fun status(context: Context, downloadId: Long): DownloadStatus {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(downloadId)).use { c ->
            if (!c.moveToFirst()) return DownloadStatus(exists = false, running = false, percent = 0, failed = true)
            val s = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val pct = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            return when (s) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_PAUSED -> DownloadStatus(true, true, pct, false)
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus(true, false, 100, false)
                else -> DownloadStatus(false, false, pct, true)   // FAILED — or vanished
            }
        }
    }

    /** The file DownloadManager wrote via setDestinationInExternalFilesDir. */
    fun downloadedFile(context: Context, fileName: String): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, fileName) }

    /**
     * versionCode inside a downloaded (not yet installed) APK, or null if the file
     * is missing/unreadable. Guards against installing stale/partial files.
     */
    @Suppress("DEPRECATION")
    fun archiveVersionCode(context: Context, file: File): Int? {
        if (!file.isFile) return null
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
               else info.versionCode
    }

    /** On API 23+ the user must allow "install unknown apps" for this package.
     *  Platform bench builds (uid 1000, INSTALL_PACKAGES) skip this entirely —
     *  the caller checks [hasInstallPackages] first. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || context.packageManager.canRequestPackageInstalls()

    /** True on the platform-signed aospDebug install (INSTALL_PACKAGES is declared
     *  only in that manifest overlay and is signature-auto-granted at uid 1000;
     *  normal builds can never reach this route). ContextCompat handles the
     *  pre-runtime-API manifest-declaration semantics. */
    fun hasInstallPackages(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.INSTALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED

    /** Install result callback action for the silent session (private to this
     *  app; delivered through an explicit PendingIntent, receiver NOT exported). */
    private const val INSTALL_RESULT_ACTION = "com.openlauncher.app.INTERNAL_INSTALL_RESULT"

    /**
     * Suspends until the PackageInstaller session reports its result (the
     * process is normally killed mid-way when the APK replaces THIS package —
     * a successful silent update therefore looks like an app restart, and the
     * fresh process reports the new version when opened again). Throws with
     * the installer's status message on anything but success.
     */
    suspend fun silentInstall(context: Context, file: File): Unit =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    runCatching { context.unregisterReceiver(this) }
                    val status = intent?.extras?.getInt(PackageInstaller.EXTRA_STATUS, -1) ?: -1
                    if (status == PackageInstaller.STATUS_SUCCESS) cont.resume(Unit)
                    else cont.resumeWithException(RuntimeException(
                        "installer status $status: " +
                        (intent?.extras?.getString(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no message")))
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(INSTALL_RESULT_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            try {
                val installer = context.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setSize(file.length()) }
                val sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    session.openWrite("base.apk", 0, file.length()).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                        session.fsync(out)   // flushes session storage (API 22+)
                    }
                    val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                        // S+ requires an explicit mutability flag; the installer
                        // adds its status extras at send time.
                        (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pi = PendingIntent.getBroadcast(
                        context, sessionId, Intent(INSTALL_RESULT_ACTION), piFlags)
                    Log.i(UPDATER_TAG, "silent session committed id=$sessionId size=${file.length()}")
                    session.commit(pi.intentSender)
                }
            } catch (t: Throwable) {
                runCatching { context.unregisterReceiver(receiver) }
                if (cont.isActive) cont.resumeWithException(t)
            }
        }

    fun requestInstallPermissionIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(context: Context, file: File): Intent {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            FileProvider.getUriForFile(context, authority(context), file)
        else Uri.fromFile(file)
        return Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
    }

    /** ACTION_INSTALL_PACKAGE fallback for ROMs whose installer only resolves
     *  the deprecated action (deprecated at API 26, still functional on many
     *  OEM installers of the API 22-30 era; only tried after ACTION_VIEW). */
    fun installIntentLegacy(context: Context, file: File): Intent {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            FileProvider.getUriForFile(context, authority(context), file)
        else Uri.fromFile(file)
        return Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
