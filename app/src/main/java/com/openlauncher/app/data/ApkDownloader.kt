package com.openlauncher.app.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Android-side plumbing for the in-app updater: DownloadManager fetch into the app's
 * own external files dir (no storage permissions needed — this keeps working on every
 * API level down to 21), plus the install handoff via FileProvider
 * (authority ${applicationId}.fileprovider, see res/xml/file_paths.xml).
 */
object ApkDownloader {

    private fun authority(context: Context) = context.packageName + ".fileprovider"

    /** Deletes a previous partial/completed copy then enqueues a fresh download. */
    fun download(context: Context, url: String, fileName: String): Long {
        downloadedFile(context, fileName)?.delete()
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("OpenLauncher update")
            setMimeType("application/vnd.android.package-archive")
            setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
            setNotificationVisibility(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    DownloadManager.Request.VISIBILITY_HIDDEN
                else DownloadManager.Request.VISIBILITY_VISIBLE
            )
            setRequiresDeviceIdle(false)
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

    /** On API 23+ the user must allow "install unknown apps" for this package. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || context.packageManager.canRequestPackageInstalls()

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
}
