package com.openlauncher.app.data

import com.google.gson.annotations.SerializedName
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path

/**
 * In-app update checking against the MarcelQ/openlauncher GitHub releases.
 *
 * GitHub's API cannot tell you an APK's versionCode, so CI writes a marker line
 * into the rolling-release NOTES: `versionCode=7 versionName=0.0.6 commit=<full sha>`.
 * The parser + decision logic here are framework-free (and unit-tested); only
 * [UpdateApi] touches the network, and [ApkDownloader] the Android download/install plumbing.
 */

const val UPDATE_REPO = "MarcelQ/openlauncher"

/** Which rolling GitHub release the app tracks. Mirrors the CI workflow's branch model. */
enum class UpdateChannel(val releaseTag: String, val assetName: String, val label: String) {
    DEV   ("dev-latest",  "openlauncher-dev-debug.apk",  "dev"),
    STABLE("main-latest", "openlauncher-main-debug.apk", "main");

    companion object {
        fun fromKey(key: String): UpdateChannel =
            entries.firstOrNull { it.name == key || it.label == key } ?: DEV
    }
}

// ── GitHub API models (only the fields we use) ───────────────────────────────
data class GitHubAsset(
    @SerializedName("name")                 val name: String = "",
    @SerializedName("browser_download_url") val browserDownloadUrl: String = "",
    @SerializedName("size")                 val sizeBytes: Long = 0
)

data class GitHubRelease(
    @SerializedName("tag_name")         val tagName: String = "",
    @SerializedName("target_commitish") val targetCommitish: String = "",
    @SerializedName("body")             val body: String = "",
    @SerializedName("published_at")     val publishedAt: String = "",
    @SerializedName("assets")           val assets: List<GitHubAsset> = emptyList()
)

// ── Normalised view of "what's available" and "what's installed" ─────────────
data class RemoteUpdate(
    val versionCode: Int?,          // null → CI marker missing, fall back to commit compare
    val versionName: String?,
    val commit: String?,            // full or short sha the APK was built from
    val apkUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val publishedAt: String,
    val notes: String
)

data class LocalBuild(val versionCode: Int, val versionName: String, val gitSha: String)

enum class UpdateReason { NEWER_VERSION, SAME_VERSION_NEW_BUILD, UNKNOWN_VERSION }

/** Result of comparing [LocalBuild] against [RemoteUpdate]. */
sealed class UpdateDecision {
    data object UpToDate : UpdateDecision()
    data class Available(val reason: UpdateReason) : UpdateDecision()
    data object NoApkAsset : UpdateDecision()
}

/** The state machine the Settings screen renders. */
sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data class UpToDate(val remote: RemoteUpdate) : UpdateUiState
    data class UpdateAvailable(val remote: RemoteUpdate, val reason: UpdateReason) : UpdateUiState
    data class Failed(val message: String) : UpdateUiState
    data class Downloading(val percent: Int) : UpdateUiState
    data class ReadyToInstall(val remote: RemoteUpdate) : UpdateUiState
    // Carries the remote so "Continue" after granting can resume without re-checking.
    data class NeedsInstallPermission(val remote: RemoteUpdate) : UpdateUiState
}

// ── Pure logic (unit-tested) ─────────────────────────────────────────────────

private val VERSION_CODE_RE = Regex("""versionCode=(\d+)""")
private val VERSION_NAME_RE = Regex("""versionName=([A-Za-z0-9._\-]+)""")
private val COMMIT_RE       = Regex("""commit=([0-9a-fA-F]{7,40})\b""")

/** true when both shas are hex and one is a prefix of the other (short vs full form). */
fun shaMatches(a: String?, b: String?): Boolean {
    if (a.isNullOrBlank() || b.isNullOrBlank()) return false
    if (a == "unknown" || b == "unknown") return false
    val (x, y) = a.lowercase() to b.lowercase()
    return x.length >= 7 && y.length >= 7 && (x.startsWith(y) || y.startsWith(x))
}

/** Extracts the CI marker (versionCode/versionName/commit) from release NOTES. */
fun parseReleaseMarkers(notes: String): Triple<Int?, String?, String?> {
    val code = VERSION_CODE_RE.find(notes)?.groupValues?.get(1)?.toIntOrNull()
    val name = VERSION_NAME_RE.find(notes)?.groupValues?.get(1)
    val commit = COMMIT_RE.find(notes)?.groupValues?.get(1)
    return Triple(code, name, commit)
}

/** The channel's APK asset, else the first asset ending in .apk, else null. */
fun pickApkAsset(assets: List<GitHubAsset>, channel: UpdateChannel): GitHubAsset? =
    assets.firstOrNull { it.name == channel.assetName }
        ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

fun buildRemoteUpdate(release: GitHubRelease, channel: UpdateChannel): RemoteUpdate? {
    val asset = pickApkAsset(release.assets, channel) ?: return null
    val (code, name, commit) = parseReleaseMarkers(release.body)
    // No explicit commit= marker → use targetCommitish when it looks like a sha
    // (the API returns the branch name when the release was made against one).
    val shaFallback = release.targetCommitish.takeIf { it.matches(Regex("[0-9a-fA-F]{7,40}")) }
    return RemoteUpdate(
        versionCode = code,
        versionName = name,
        commit      = commit ?: shaFallback,
        apkUrl      = asset.browserDownloadUrl,
        fileName    = asset.name,
        sizeBytes   = asset.sizeBytes,
        publishedAt = release.publishedAt,
        notes       = release.body
    )
}

fun decideUpdate(local: LocalBuild, remote: RemoteUpdate): UpdateDecision {
    val rc = remote.versionCode
    val sameCommit = shaMatches(local.gitSha, remote.commit)
    return when {
        rc != null && rc > local.versionCode -> UpdateDecision.Available(UpdateReason.NEWER_VERSION)
        rc != null && rc < local.versionCode -> UpdateDecision.UpToDate
        sameCommit                           -> UpdateDecision.UpToDate
        // Nothing to compare on (no marker, no sha on either side) — don't nag.
        rc == null && remote.commit == null  -> UpdateDecision.UpToDate
        rc == null                           -> UpdateDecision.Available(UpdateReason.UNKNOWN_VERSION)
        // Equal versionCode but a *different known* remote build sha → re-offer.
        remote.commit != null                -> UpdateDecision.Available(UpdateReason.SAME_VERSION_NEW_BUILD)
        else                                 -> UpdateDecision.UpToDate
    }
}

// ── Network ──────────────────────────────────────────────────────────────────

interface GitHubUpdateApiService {
    @GET("repos/$UPDATE_REPO/releases/tags/{tag}")
    suspend fun releaseByTag(
        @Path("tag") tag: String,
        @Header("Accept") accept: String = "application/vnd.github+json"
    ): GitHubRelease
}

object UpdateApi {
    private val client = okhttp3.OkHttpClient.Builder().build()

    val service: GitHubUpdateApiService = Retrofit.Builder()
        .baseUrl("https://api.github.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(GitHubUpdateApiService::class.java)
}
